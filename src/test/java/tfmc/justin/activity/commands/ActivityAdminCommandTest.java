package tfmc.justin.activity.commands;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.managers.TestManagers;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.models.RecordResult;
import tfmc.justin.activity.models.Recorded;
import tfmc.justin.activity.store.PlayerStore;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityAdminCommandTest {

    private static final String ADMIN = "activity.admin";
    private static final String CHECK = "activity.check";

    private static final class Sender {
        private final List<String> sent = new ArrayList<>();
        private final CommandSender bukkit;

        private Sender(String name, Set<String> permissions) {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "hasPermission" -> permissions.contains(String.valueOf(args[0]));
                case "getName" -> name;
                case "sendMessage" -> {
                    sent.add(String.valueOf(args[0]));
                    yield null;
                }
                case "toString" -> "stub-sender";
                case "hashCode" -> name.hashCode();
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(
                    "unexpected call to CommandSender#" + method.getName());
            };
            bukkit = (CommandSender) Proxy.newProxyInstance(
                ActivityAdminCommandTest.class.getClassLoader(), new Class<?>[] {CommandSender.class}, handler);
        }

        private String all() {
            return String.join("\n", sent).replaceAll("\u00a7.", "");
        }
    }

    private static Sender admin() {
        return new Sender("Justin", Set.of(ADMIN, CHECK));
    }

    private static Sender checkOnly() {
        return new Sender("Mod", Set.of(CHECK));
    }

    private static OfflinePlayer stubTarget(UUID uuid, String name) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getName" -> name;
            case "toString" -> "stub-target";
            case "hashCode" -> uuid.hashCode();
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(
                "unexpected call to OfflinePlayer#" + method.getName());
        };
        return (OfflinePlayer) Proxy.newProxyInstance(
            ActivityAdminCommandTest.class.getClassLoader(), new Class<?>[] {OfflinePlayer.class}, handler);
    }

    private static final String UNKNOWN = "Ghost";

    private static ActivityCommand command(ActivityManager manager, UUID target, String targetName) {
        return new ActivityCommand(manager, null) {
            @Override
            OfflinePlayer resolve(CommandSender sender, String name) {
                if (name.equals(UNKNOWN)) {
                    sender.sendMessage(manager.getConfiguration().messages()
                        .get("admin.unknown-player", "%player%", name));
                    return null;
                }
                return stubTarget(target, targetName);
            }

            @Override
            List<String> onlineNames() {
                return List.of(targetName, "Somebody");
            }
        };
    }

    private static ActivityManager manager() {
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 2, 1, 0),
            new ActivityDef("quest", "Quest", Material.PAPER, null, 2, 1, 0));
        TestManagers.messages(manager);
        TestManagers.rerollsPerDay(manager, 1);
        return manager;
    }

    private static boolean dirty(PlayerStore store) {
        try {
            Field field = PlayerStore.class.getDeclaredField("dirty");
            field.setAccessible(true);
            return (boolean) field.get(store);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void clean(PlayerStore store) {
        try {
            Field field = PlayerStore.class.getDeclaredField("dirty");
            field.setAccessible(true);
            field.set(store, false);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> audit(Runnable action) {
        List<String> lines = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage() != null && record.getMessage().startsWith("ACTIVITY-AUDIT ")) {
                    lines.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = TestManagers.logger();
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return lines;
    }

    @Test
    void aForcedAddThatFillsTheWeeklyBarSaysHowMuchLanded() {
        TestManagers.bukkit();
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5));
        TestManagers.messages(manager);
        TestManagers.limits(manager, 10, 10);
        UUID player = UUID.randomUUID();

        Sender sender = admin();
        List<String> lines = audit(() -> command(manager, player, "Steve").onCommand(
            sender.bukkit, null, "activity", new String[] {"add", "Steve", "vote", "50", "--force"}));

        assertEquals("Added 50 to vote for Steve, but only 10 points fit: "
            + "Steve's weekly bar hit its maximum of 10.", sender.all());
        assertEquals(10, manager.getStore().get(player).points());
        assertEquals(1, lines.size(), String.valueOf(lines));
        assertEquals("ACTIVITY-AUDIT sender=\"Justin\" action=add target=\"Steve\" uuid=" + player
            + " activity=\"vote\" count=50 force=true points=10 result=CLAMPED_WEEKLY", lines.get(0));
    }

    @Test
    void aForcedAddReportsThePointsItAwarded() {
        TestManagers.bukkit();
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5));
        TestManagers.messages(manager);
        TestManagers.limits(manager, 100, 10);
        UUID player = UUID.randomUUID();

        Sender sender = admin();
        command(manager, player, "Steve").onCommand(
            sender.bukkit, null, "activity", new String[] {"add", "Steve", "vote", "50", "--force"});

        assertEquals("Added 50 to vote for Steve: 50 points.", sender.all());
        assertEquals(50, manager.getStore().get(player).points());
    }

    @Test
    void anAddThatAwardsNoPointsYetOmitsThePointsFigure() {
        TestManagers.bukkit();
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("instrument", "Notes", Material.NOTE_BLOCK, null, 20, 1, 5));
        TestManagers.messages(manager);
        UUID player = UUID.randomUUID();

        Sender sender = admin();
        command(manager, player, "Steve").onCommand(
            sender.bukkit, null, "activity", new String[] {"add", "Steve", "instrument", "1", "--force"});

        assertEquals("Added 1 to instrument for Steve.", sender.all());
        assertEquals(1, manager.getStore().get(player).count("instrument"));
    }

    private static ActivityManager pointsManager(int barMax) {
        TestManagers.bukkit();
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5));
        TestManagers.messages(manager);
        TestManagers.limits(manager, barMax, 5);
        return manager;
    }

    @Test
    void addpointsCreditsExactlyThePointsAsked() {
        ActivityManager manager = pointsManager(100);
        UUID player = UUID.randomUUID();

        Sender sender = admin();
        List<String> lines = audit(() -> command(manager, player, "Steve").onCommand(
            sender.bukkit, null, "activity", new String[] {"addpoints", "Steve", "13"}));

        assertEquals("Added 13 points to Steve.", sender.all());
        PlayerData data = manager.getStore().peek(player);
        assertEquals(13, data.points());
        assertEquals(Map.of(), data.daily());
        assertEquals(0, data.dailyPoints());
        assertTrue(dirty(manager.getStore()), "the award was not marked for saving");
        assertEquals(List.of("ACTIVITY-AUDIT sender=\"Justin\" action=addpoints target=\"Steve\" uuid=" + player
            + " requested=13 points=13 result=RECORDED"), lines);
    }

    @Test
    void addpointsIsNotLimitedByTheDailyMax() {
        ActivityManager manager = pointsManager(100);
        UUID player = UUID.randomUUID();
        PlayerData data = manager.getStore().get(player);
        data.record(5, new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 0), 100, 5, List.of());
        assertEquals(5, data.dailyPoints());

        Sender sender = admin();
        command(manager, player, "Steve").onCommand(
            sender.bukkit, null, "activity", new String[] {"addpoints", "Steve", "20"});

        assertEquals("Added 20 points to Steve.", sender.all());
        assertEquals(25, data.points());
    }

    @Test
    void addpointsIsClampedAtTheWeeklyMaxAndSaysSo() {
        ActivityManager manager = pointsManager(50);
        UUID player = UUID.randomUUID();
        manager.getStore().get(player).addPoints(40, 50);

        Sender sender = admin();
        List<String> lines = audit(() -> command(manager, player, "Steve").onCommand(
            sender.bukkit, null, "activity", new String[] {"addpoints", "Steve", "13"}));

        assertEquals("Added 10 of 13 points to Steve (weekly max 50 reached).", sender.all());
        assertEquals(50, manager.getStore().peek(player).points());
        assertEquals(List.of("ACTIVITY-AUDIT sender=\"Justin\" action=addpoints target=\"Steve\" uuid=" + player
            + " requested=13 points=10 result=WEEKLY_CLAMPED"), lines);

        Sender again = admin();
        command(manager, player, "Steve").onCommand(
            again.bukkit, null, "activity", new String[] {"addpoints", "Steve", "13"});
        assertEquals("Added 0 of 13 points to Steve (weekly max 50 reached).", again.all());
    }

    @Test
    void addpointsRejectsAnythingButAPositiveWholeNumber() {
        for (String value : List.of("0", "-5", "abc", "1.5", "99999999999")) {
            ActivityManager manager = pointsManager(100);
            UUID player = UUID.randomUUID();

            Sender sender = admin();
            List<String> lines = audit(() -> command(manager, player, "Steve").onCommand(
                sender.bukkit, null, "activity", new String[] {"addpoints", "Steve", value}));

            assertEquals("Points must be a whole number above 0: " + value, sender.all(), value);
            assertNull(manager.getStore().peek(player), value + ": a row was created");
            assertFalse(dirty(manager.getStore()), value);
            assertEquals(List.of("ACTIVITY-AUDIT sender=\"Justin\" action=addpoints target=\"Steve\" uuid="
                + player + " value=\"" + value + "\" result=invalid-number"), lines, value);
        }
    }

    @Test
    void anInvalidAddpointsValueIsEchoedWithoutFormatting() {
        ActivityManager manager = pointsManager(100);
        Sender sender = admin();
        command(manager, UUID.randomUUID(), "Steve").onCommand(
            sender.bukkit, null, "activity", new String[] {"addpoints", "Steve", "&k&c#ff0000five"});

        assertEquals(1, sender.sent.size(), String.valueOf(sender.sent));
        assertEquals("§cPoints must be a whole number above 0: five", sender.sent.get(0));
    }

    @Test
    void addpointsWithTheWrongArgumentCountIsAUsageError() {
        for (String[] args : List.of(
            new String[] {"addpoints"},
            new String[] {"addpoints", "Steve"},
            new String[] {"addpoints", "Steve", "5", "--force"})) {
            ActivityManager manager = pointsManager(100);
            UUID player = UUID.randomUUID();

            Sender sender = admin();
            command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", args);

            assertTrue(sender.all().contains("Usage:"), sender.all());
            assertTrue(sender.all().contains("addpoints <player> <points>"), sender.all());
            assertNull(manager.getStore().peek(player), args.length + " args: a row was created");
        }
    }

    @Test
    void addpointsCrossesAMilestoneTheSameWayAForcedAddDoes() {
        List<String> byAdd = milestoneCrossedBy(100, 0, admin(),
            new String[] {"add", "Steve", "vote", "12", "--force"});
        List<String> byPoints = milestoneCrossedBy(100, 0, admin(), new String[] {"addpoints", "Steve", "12"});

        assertEquals(List.of("+12 Vote (12/100)", "REWARD READY! Open /activity to claim."), byAdd);
        assertEquals(List.of("+12 Bonus (12/100)", "REWARD READY! Open /activity to claim."), byPoints);
    }

    @Test
    void aClampedAddpointsStillReachesTheMilestoneAtTheWeeklyMax() {
        Sender sender = admin();
        List<String> got = milestoneCrossedBy(20, 15, sender, new String[] {"addpoints", "Steve", "100"});

        assertEquals("Added 5 of 100 points to Steve (weekly max 20 reached).", sender.all());
        assertEquals(List.of("+5 Bonus (20/20)", "REWARD READY! Open /activity to claim."), got);
    }

    @Test
    void aRerollDoesNotTakeAddpointsBack() {
        ActivityManager manager = pointsManager(100);
        UUID player = UUID.randomUUID();
        command(manager, player, "Steve").onCommand(
            admin().bukkit, null, "activity", new String[] {"addpoints", "Steve", "13"});

        PlayerData data = manager.getStore().peek(player);
        data.reroll(List.of("vote"), 100);

        assertEquals(13, data.points());
        assertEquals(0, data.dailyPoints());
    }

    @Test
    void recordPointsRefusesANonPositiveAwardWithoutCreatingARow() {
        ActivityManager manager = pointsManager(100);
        UUID player = UUID.randomUUID();

        for (int points : new int[] {0, -1}) {
            assertEquals(new RecordResult(0, 0, Recorded.UNKNOWN), manager.recordPoints(player, points));
        }
        assertNull(manager.getStore().peek(player), "a row was created");
        assertFalse(dirty(manager.getStore()));
    }

    private static List<String> milestoneCrossedBy(int barMax, int startPoints, Sender sender, String[] args) {
        ActivityManager manager = pointsManager(barMax);
        UUID uuid = UUID.randomUUID();
        if (startPoints > 0) {
            PlayerData data = manager.getStore().get(uuid);
            data.addPoints(startPoints, barMax);
            data.setClaimedPoints(PlayerData.due(startPoints, 0, manager.getConfiguration().milestones())
                .stream().max(Integer::compare).orElse(0));
        }
        List<String> got = new ArrayList<>();
        InvocationHandler handler = (proxy, method, a) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "sendMessage" -> {
                got.add(String.valueOf(a[0]).replaceAll("\u00a7.", ""));
                yield null;
            }
            default -> throw new UnsupportedOperationException("unexpected call to Player#" + method.getName());
        };
        TestManagers.online((Player) Proxy.newProxyInstance(
            ActivityAdminCommandTest.class.getClassLoader(), new Class<?>[] {Player.class}, handler));
        try {
            command(manager, uuid, "Steve").onCommand(sender.bukkit, null, "activity", args);
        } finally {
            TestManagers.offline(uuid);
        }
        assertEquals(1, manager.getStore().peek(uuid).claimable(manager.getConfiguration().milestones()));
        return got;
    }

    @Test
    void checkPrintsEveryFieldOfThePlayersDay() {
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 1),
            new ActivityDef("quest", "Quest", Material.PAPER, null, 2, 1, 0));
        TestManagers.messages(manager);
        TestManagers.rerollsPerDay(manager, 1);
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));
        PlayerData data = manager.getStore().peek(player);
        data.record(3, new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 1), 50, 10, List.of(10, 20));

        Sender sender = admin();
        command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", new String[] {"check", "Steve"});
        String out = sender.all();

        assertTrue(out.contains("Steve"), out);
        assertTrue(out.contains(data.points() + "/50"), out);
        assertTrue(out.contains("0 already claimed"), out);
        assertTrue(out.contains(data.dailyPoints() + "/10"), out);
        assertTrue(out.contains("Rerolls used today: 0/1"), out);
        assertTrue(out.contains(data.weekKey()), out);
        assertTrue(out.contains(data.dayKey()), out);
        assertTrue(out.contains("Vote"), out);
        assertTrue(out.contains("revealed"), out);
        assertTrue(out.contains("count 3"), out);
        assertTrue(out.contains("that count is worth 3 points before any cap"), out);
        assertEquals(data.tasks().size(),
            sender.sent.stream().filter(line -> line.contains("revealed") || line.contains("hidden")).count());
        assertTrue(out.contains("hidden"), out);
    }

    @Test
    void checkStripsColourCodesFromTheTaskName() {
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "#e6ca40&lCarve Basic Handles", Material.PAPER, null, 2, 1, 0),
            new ActivityDef("quest", "Quest", Material.PAPER, null, 2, 1, 0));
        TestManagers.messages(manager);
        TestManagers.rerollsPerDay(manager, 1);
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));

        Sender sender = admin();
        command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", new String[] {"check", "Steve"});
        String out = sender.all();

        assertTrue(out.contains("Carve Basic Handles"), out);
        assertFalse(out.contains("#e6ca40"), out);
        assertFalse(out.contains("&l"), out);
    }

    @Test
    void checkMutatesNothing() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.reveal(player, 0);
        PlayerData data = manager.getStore().peek(player);
        int points = data.points();
        int rerolls = data.rerolls();
        List<String> tasks = List.copyOf(data.tasks());
        Set<String> revealed = Set.copyOf(data.revealed());
        String day = data.dayKey();
        clean(manager.getStore());

        command(manager, player, "Steve")
            .onCommand(admin().bukkit, null, "activity", new String[] {"check", "Steve"});

        assertSame(data, manager.getStore().peek(player));
        assertEquals(points, data.points());
        assertEquals(rerolls, data.rerolls());
        assertEquals(tasks, data.tasks());
        assertEquals(revealed, data.revealed());
        assertEquals(day, data.dayKey());
        assertFalse(dirty(manager.getStore()), "check marked the store dirty");
    }

    @Test
    void checkOnAStaleRowRollsNothingAndReportsThePostRolloverDay() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.tasks(player);
        PlayerData data = manager.getStore().peek(player);
        String week = data.weekKey();
        data.roll(week, "1999-01-01");
        data.reroll(List.of("vote"), 50);
        data.record(3, new ActivityDef("vote", "Vote", Material.PAPER, null, 2, 1, 0), 50, 10, List.of(10, 20));
        assertEquals(1, data.points());
        clean(manager.getStore());

        Sender sender = admin();
        command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", new String[] {"check", "Steve"});

        assertSame(data, manager.getStore().peek(player));
        assertEquals("1999-01-01", data.dayKey());
        assertEquals(List.of("vote"), data.tasks());
        assertEquals(1, data.rerolls());
        assertEquals(3, data.count("vote"));
        assertFalse(dirty(manager.getStore()), "check marked the store dirty");

        String out = sender.all();
        assertTrue(out.contains("Stale: stored week " + week + ", day 1999-01-01"), out);
        assertTrue(out.contains("Today: 0/10 points"), out);
        assertFalse(out.contains("Today: 1/10 points"), out);
        assertTrue(out.contains("Rerolls used today: 0/1"), out);
        assertTrue(out.contains("No tasks drawn yet today"), out);
        assertTrue(out.contains("Weekly: 1/50 points"), out);
    }

    @Test
    void checkShowsTodaysDailyRewardAndIgnoresAStaleDaysFlag() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.tasks(player);
        PlayerData data = manager.getStore().peek(player);

        Sender before = admin();
        command(manager, player, "Steve").onCommand(before.bukkit, null, "activity", new String[] {"check", "Steve"});
        assertTrue(before.all().contains("Daily reward: not claimed today"), before.all());

        data.setDailyRewardClaimed(true);
        Sender claimed = admin();
        command(manager, player, "Steve").onCommand(claimed.bukkit, null, "activity", new String[] {"check", "Steve"});
        assertTrue(claimed.all().contains("Daily reward: claimed today"), claimed.all());

        data.roll(data.weekKey(), "1999-01-01");
        data.setDailyRewardClaimed(true);
        clean(manager.getStore());
        Sender stale = admin();
        command(manager, player, "Steve").onCommand(stale.bukkit, null, "activity", new String[] {"check", "Steve"});
        assertTrue(stale.all().contains("Daily reward: not claimed today"), stale.all());
        assertTrue(data.dailyRewardClaimed(), "check rolled the stale row");
        assertFalse(dirty(manager.getStore()), "check marked the store dirty");
    }

    @Test
    void checkClampsPointsAndClaimedToALoweredBar() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.tasks(player);
        PlayerData data = manager.getStore().peek(player);
        data.addPoints(50, 50);
        data.setClaimedPoints(40);
        TestManagers.limits(manager, 20, 10);

        Sender sender = admin();
        command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", new String[] {"check", "Steve"});

        assertTrue(sender.all().contains("Weekly: 20/20 points, 20 already claimed"), sender.all());
        assertEquals(50, data.points(), "check clamped the stored value");
        assertEquals(40, data.claimedPoints(), "check clamped the stored value");
    }

    @Test
    void checkClampsDailyPointsToALoweredDailyMax() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.tasks(player);
        PlayerData data = manager.getStore().peek(player);
        data.record(15, new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 0), 50, 100, List.of());
        TestManagers.limits(manager, 50, 10);

        Sender sender = admin();
        command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", new String[] {"check", "Steve"});

        assertTrue(sender.all().contains("Today: 10/10 points"), sender.all());
        assertEquals(15, data.dailyPoints(), "check clamped the stored value");
    }

    @Test
    void checkOnAPlayerWithNoDataSaysSoAndCreatesNoRow() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        clean(manager.getStore());

        Sender sender = admin();
        command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", new String[] {"check", "Steve"});

        assertTrue(sender.all().contains("no activity data"), sender.all());
        assertNull(manager.getStore().peek(player), "check created a row");
        assertFalse(dirty(manager.getStore()));
    }

    @Test
    void checkWithoutAPlayerIsAUsageError() {
        ActivityManager manager = manager();
        Sender sender = admin();
        command(manager, UUID.randomUUID(), "Steve")
            .onCommand(sender.bukkit, null, "activity", new String[] {"check"});

        assertTrue(sender.all().contains("Usage:"), sender.all());
    }

    @Test
    void onlyCheckAnswersToTheCheckPermission() {
        assertEquals(CHECK, ActivityCommand.permissionFor("check"));
        assertEquals(ADMIN, ActivityCommand.permissionFor("reload"));
        assertEquals(ADMIN, ActivityCommand.permissionFor("reset"));
        assertEquals(ADMIN, ActivityCommand.permissionFor("givereroll"));
        assertEquals(ADMIN, ActivityCommand.permissionFor("add"));
        assertEquals(ADMIN, ActivityCommand.permissionFor("addpoints"));
    }

    @Test
    void checkIsAllowedByTheCheckPermissionAloneAndByAnAdmin() {
        for (Sender sender : List.of(checkOnly(), admin())) {
            ActivityManager manager = manager();
            UUID player = UUID.randomUUID();
            manager.tasks(player);

            command(manager, player, "Steve")
                .onCommand(sender.bukkit, null, "activity", new String[] {"check", "Steve"});

            assertFalse(sender.all().contains("No permission"), sender.all());
            assertTrue(sender.all().contains("Steve"), sender.all());
        }
    }

    @Test
    void adminImpliesCheckInPluginYml() {
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(new File("src/main/resources/plugin.yml"));
        assertNotNull(plugin.get("permissions." + CHECK), "activity.check is not declared");
        assertTrue(plugin.getBoolean("permissions." + ADMIN + ".children." + CHECK),
            "activity.admin does not imply activity.check");
    }

    @Test
    void everyMutatingSubcommandIsRefusedToACheckOnlySender() {
        for (String[] args : List.of(
            new String[] {"reset", "Steve"},
            new String[] {"givereroll", "Steve"},
            new String[] {"add", "Steve", "vote", "1"},
            new String[] {"addpoints", "Steve", "5"},
            new String[] {"reload"})) {

            ActivityManager manager = manager();
            UUID player = UUID.randomUUID();
            manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));
            manager.getStore().peek(player).reroll(List.of("vote"), 50);
            clean(manager.getStore());

            Sender sender = checkOnly();
            List<String> lines = audit(() -> command(manager, player, "Steve")
                .onCommand(sender.bukkit, null, "activity", args));

            assertTrue(sender.all().contains("No permission"), args[0] + ": " + sender.all());
            assertEquals(List.of("ACTIVITY-AUDIT sender=\"Mod\" action=" + args[0] + " result=denied"),
                lines, args[0] + ": the refusal was not audited");
            assertEquals(1, manager.getStore().peek(player).rerolls(), args[0]);
            assertEquals(0, manager.getStore().peek(player).count("vote"), args[0]);
            assertEquals(0, manager.getStore().peek(player).points(), args[0]);
            assertFalse(dirty(manager.getStore()), args[0]);
        }
    }

    @Test
    void rerollGivesOneBackAndChangesNothingElse() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));
        PlayerData data = manager.getStore().peek(player);
        data.reroll(List.of("vote"), 50);
        List<String> tasks = List.copyOf(data.tasks());
        int points = data.points();
        assertEquals(1, data.rerolls());
        clean(manager.getStore());

        Sender sender = admin();
        command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", new String[] {"givereroll", "Steve"});

        assertEquals(0, data.rerolls());
        assertTrue(sender.all().contains("from 1 to 0"), sender.all());
        assertTrue(dirty(manager.getStore()), "the give-back was not marked for saving");
        assertEquals(tasks, data.tasks());
        assertEquals(points, data.points());
        assertEquals(0, data.dailyPoints());
        assertEquals(Set.of(), data.revealed());
    }

    @Test
    void rerollFloorsAtZeroAndSaysThereWasNothingToGiveBack() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.tasks(player);
        assertEquals(0, manager.getStore().peek(player).rerolls());
        clean(manager.getStore());

        Sender sender = admin();
        command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", new String[] {"givereroll", "Steve"});

        assertEquals(0, manager.getStore().peek(player).rerolls());
        assertTrue(sender.all().contains("no rerolls today"), sender.all());
        assertFalse(dirty(manager.getStore()), "a no-op give-back marked the store dirty");
    }

    @Test
    void rerollOnAPlayerWithNoDataIsANoOpAndCreatesNoRow() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        clean(manager.getStore());

        Sender sender = admin();
        command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", new String[] {"givereroll", "Steve"});

        assertTrue(sender.all().contains("no rerolls today"), sender.all());
        assertNull(manager.getStore().peek(player), "reroll created a row");
    }

    @Test
    void givererollOnAStaleRowRefusesAndRollsNothing() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));
        PlayerData data = manager.getStore().peek(player);
        data.roll(data.weekKey(), "1999-01-01");
        data.reroll(List.of("vote"), 50);
        clean(manager.getStore());

        Sender sender = admin();
        List<String> lines = audit(() -> command(manager, player, "Steve")
            .onCommand(sender.bukkit, null, "activity", new String[] {"givereroll", "Steve"}));

        assertTrue(sender.all().contains("no rerolls today"), sender.all());
        assertEquals(List.of("ACTIVITY-AUDIT sender=\"Justin\" action=givereroll target=\"Steve\" uuid="
            + player + " rerolls=0->0 result=noop"), lines);
        assertEquals("1999-01-01", data.dayKey());
        assertEquals(1, data.rerolls());
        assertEquals(List.of("vote"), data.tasks());
        assertFalse(dirty(manager.getStore()), "a refused give-back marked the store dirty");
    }

    @Test
    void rerollWithoutAPlayerIsAUsageError() {
        ActivityManager manager = manager();
        Sender sender = admin();
        command(manager, UUID.randomUUID(), "Steve")
            .onCommand(sender.bukkit, null, "activity", new String[] {"givereroll"});

        assertTrue(sender.all().contains("Usage:"), sender.all());
    }

    @Test
    void anUnknownPlayerStopsEveryHandlerAndIsAuditedUnlessItIsCheck() {
        for (String[] args : List.of(
            new String[] {"check", UNKNOWN},
            new String[] {"reset", UNKNOWN},
            new String[] {"givereroll", UNKNOWN},
            new String[] {"add", UNKNOWN, "vote", "1"},
            new String[] {"addpoints", UNKNOWN, "5"})) {

            ActivityManager manager = manager();
            UUID player = UUID.randomUUID();
            manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));
            manager.getStore().peek(player).reroll(List.of("vote"), 50);
            clean(manager.getStore());

            Sender sender = admin();
            List<String> lines = audit(() -> command(manager, player, "Steve")
                .onCommand(sender.bukkit, null, "activity", args));

            assertTrue(sender.all().contains("Unknown player: " + UNKNOWN), args[0] + ": " + sender.all());
            assertEquals(args[0].equals("check")
                    ? List.of()
                    : List.of("ACTIVITY-AUDIT sender=\"Justin\" action=" + args[0]
                        + " typed=\"" + UNKNOWN + "\" result=unknown-player"),
                lines, args[0]);
            assertEquals(1, manager.getStore().peek(player).rerolls(), args[0]);
            assertEquals(0, manager.getStore().peek(player).count("vote"), args[0]);
            assertEquals(List.of("vote"), manager.getStore().peek(player).tasks(), args[0]);
            assertFalse(dirty(manager.getStore()), args[0]);
        }
    }

    @Test
    void everyMutatingSubcommandLogsExactlyOneAuditLine() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));
        ActivityCommand command = command(manager, player, "Steve");
        Sender sender = admin();

        List<String> reset = audit(() ->
            command.onCommand(sender.bukkit, null, "activity", new String[] {"reset", "Steve"}));
        assertEquals(1, reset.size(), String.valueOf(reset));
        assertEquals("ACTIVITY-AUDIT sender=\"Justin\" action=reset target=\"Steve\" uuid=" + player
            + " result=done", reset.get(0));

        List<String> add = audit(() ->
            command.onCommand(sender.bukkit, null, "activity", new String[] {"add", "Steve", "vote", "1", "--force"}));
        assertEquals(1, add.size(), String.valueOf(add));
        assertEquals("ACTIVITY-AUDIT sender=\"Justin\" action=add target=\"Steve\" uuid=" + player
            + " activity=\"vote\" count=1 force=true points=0 result=ADDED", add.get(0));

        List<String> noop = audit(() ->
            command.onCommand(sender.bukkit, null, "activity", new String[] {"givereroll", "Steve"}));
        assertEquals(1, noop.size(), String.valueOf(noop));
        assertEquals("ACTIVITY-AUDIT sender=\"Justin\" action=givereroll target=\"Steve\" uuid=" + player
            + " rerolls=0->0 result=noop", noop.get(0));

        manager.getStore().peek(player).reroll(List.of("vote"), 50);
        List<String> reroll = audit(() ->
            command.onCommand(sender.bukkit, null, "activity", new String[] {"givereroll", "Steve"}));
        assertEquals(1, reroll.size(), String.valueOf(reroll));
        assertEquals("ACTIVITY-AUDIT sender=\"Justin\" action=givereroll target=\"Steve\" uuid=" + player
            + " rerolls=1->0 result=done", reroll.get(0));
    }

    @Test
    void aHostileNameIsSanitisedAndQuotedInTheAuditLine() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        Sender sender = new Sender("Ev\u001bil Name result=denied", Set.of(ADMIN, CHECK));

        List<String> lines = audit(() -> command(manager, player, "Steve result=done")
            .onCommand(sender.bukkit, null, "activity", new String[] {"reset", "Steve"}));

        assertEquals(1, lines.size(), String.valueOf(lines));
        assertEquals("ACTIVITY-AUDIT sender=\"Ev?il Name result=denied\" action=reset "
            + "target=\"Steve result=done\" uuid=" + player + " result=done", lines.get(0));
    }

    @Test
    void aQuoteAndABackslashInANameAreEscapedInTheAuditLine() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        Sender sender = new Sender("Ste\"ve\\", Set.of(ADMIN, CHECK));

        List<String> lines = audit(() -> command(manager, player, "Ste\"ve\\")
            .onCommand(sender.bukkit, null, "activity", new String[] {"reset", "Ste\"ve\\"}));

        assertEquals(1, lines.size(), String.valueOf(lines));
        assertEquals("ACTIVITY-AUDIT sender=\"Ste\\\"ve\\\\\" action=reset "
            + "target=\"Ste\\\"ve\\\\\" uuid=" + player + " result=done", lines.get(0));
    }

    @Test
    void checkLogsNothing() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.tasks(player);
        Sender sender = admin();

        assertEquals(List.of(), audit(() -> command(manager, player, "Steve")
            .onCommand(sender.bukkit, null, "activity", new String[] {"check", "Steve"})));
        assertEquals(List.of(), audit(() -> command(manager, UUID.randomUUID(), "Nobody")
            .onCommand(sender.bukkit, null, "activity", new String[] {"check", "Nobody"})));

        Sender nobody = new Sender("Player", Set.of());
        assertEquals(List.of(), audit(() -> command(manager, player, "Steve")
            .onCommand(nobody.bukkit, null, "activity", new String[] {"check", "Steve"})));
        assertTrue(nobody.all().contains("No permission"), nobody.all());
    }

    @Test
    void anUnknownSubcommandIsAUsageErrorForAnyoneAllowedIn() {
        ActivityManager manager = manager();
        ActivityCommand command = command(manager, UUID.randomUUID(), "Steve");

        for (Sender sender : List.of(checkOnly(), admin())) {
            command.onCommand(sender.bukkit, null, "activity", new String[] {"wat"});
            assertTrue(sender.all().contains("Usage:"), sender.all());
        }

        Sender nobody = new Sender("Player", Set.of());
        List<String> lines = audit(() ->
            command.onCommand(nobody.bukkit, null, "activity", new String[] {"wat"}));
        assertTrue(nobody.all().contains("No permission"), nobody.all());
        assertFalse(nobody.all().contains("Usage:"), nobody.all());
        assertEquals(List.of(), lines, "an unknown subcommand was audited");
    }

    @Test
    void theConsoleIsNamedInTheAuditLine() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        Sender console = new Sender("CONSOLE", Set.of(ADMIN, CHECK));

        List<String> lines = audit(() -> command(manager, player, "Steve")
            .onCommand(console.bukkit, null, "activity", new String[] {"reset", "Steve"}));

        assertEquals(1, lines.size(), String.valueOf(lines));
        assertTrue(lines.get(0).startsWith("ACTIVITY-AUDIT sender=\"CONSOLE\" action=reset"), lines.get(0));
    }

    @Test
    void aCheckOnlySenderIsOfferedCheckAndNothingElse() {
        ActivityManager manager = manager();
        ActivityCommand command = command(manager, UUID.randomUUID(), "Steve");

        assertEquals(List.of("check"),
            command.onTabComplete(checkOnly().bukkit, null, "activity", new String[] {""}));
        assertEquals(List.of(),
            command.onTabComplete(checkOnly().bukkit, null, "activity", new String[] {"re"}));
        assertEquals(List.of(),
            command.onTabComplete(checkOnly().bukkit, null, "activity", new String[] {"reset", ""}));
        assertEquals(List.of(),
            command.onTabComplete(checkOnly().bukkit, null, "activity", new String[] {"add", "Steve", ""}));
        assertEquals(List.of("Steve"),
            command.onTabComplete(checkOnly().bukkit, null, "activity", new String[] {"check", "Ste"}));
    }

    @Test
    void anAdminIsOfferedEverySubcommand() {
        ActivityManager manager = manager();
        ActivityCommand command = command(manager, UUID.randomUUID(), "Steve");

        assertEquals(List.of("reload", "check", "reset", "givereroll", "add", "addpoints"),
            command.onTabComplete(admin().bukkit, null, "activity", new String[] {""}));
        assertEquals(List.of("Steve"),
            command.onTabComplete(admin().bukkit, null, "activity", new String[] {"addpoints", "Ste"}));
        assertEquals(List.of(),
            command.onTabComplete(admin().bukkit, null, "activity", new String[] {"addpoints", "Steve", ""}));
        assertEquals(List.of("Steve"),
            command.onTabComplete(admin().bukkit, null, "activity", new String[] {"givereroll", "Ste"}));
    }

    @Test
    void aSenderWithNeitherPermissionIsOfferedNothing() {
        ActivityManager manager = manager();
        Sender nobody = new Sender("Player", Set.of());

        assertEquals(List.of(), command(manager, UUID.randomUUID(), "Steve")
            .onTabComplete(nobody.bukkit, null, "activity", new String[] {""}));
    }

    @Test
    void everyNewAdminMessageIsShipped() {
        YamlConfiguration messages = YamlConfiguration
            .loadConfiguration(new File("src/main/resources/messages.yml"));

        for (String key : List.of("check-no-data", "check-header", "check-points", "check-daily",
            "check-rerolls", "check-keys", "check-stale", "check-no-tasks", "check-task-revealed",
            "check-task-hidden",
            "reroll-given", "reroll-none-used", "addpoints-done", "addpoints-clamped", "addpoints-invalid")) {
            String value = messages.getString("admin." + key);
            assertFalse(value == null || value.isBlank(), "admin." + key + " is missing");
        }

        String usage = messages.getString("admin.usage", "");
        assertTrue(usage.contains("check"), usage);
        assertTrue(usage.contains("givereroll"), usage);

        String pluginUsage = YamlConfiguration
            .loadConfiguration(new File("src/main/resources/plugin.yml"))
            .getString("commands.activity.usage", "");
        assertTrue(pluginUsage.contains("check"), pluginUsage);
        assertTrue(pluginUsage.contains("givereroll"), pluginUsage);
        String addpointsUsage = messages.getString("admin.usage-addpoints", "");
        assertTrue(addpointsUsage.contains("addpoints <player> <points>"), addpointsUsage);
        assertFalse(usage.contains("addpoints"), usage);
        assertTrue(pluginUsage.contains("addpoints <player> <points>"), pluginUsage);
    }
}
