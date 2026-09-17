package tfmc.justin.activity.commands;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.managers.TestManagers;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.store.PlayerStore;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
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

// ====================================
// The admin half of /activity driven end to end without a server: the sender
// and the target are Proxy stubs (the technique the listener tests use), and
// the one thing onCommand does that needs a live server - resolving a name to
// an OfflinePlayer - is overridden here. Everything else, including the
// permission split, the messages and the audit lines, is the real code.
//
// Not reachable headless, and so not covered: Bukkit.getPlayerExact and
// Bukkit.getOfflinePlayerIfCached inside resolve(), and the online-player list
// the completer offers as a player argument - both are overridden below.
// ====================================
class ActivityAdminCommandTest {

    private static final String ADMIN = "activity.admin";
    private static final String CHECK = "activity.check";

    // ====================================
    // A sender that answers only what the command asks it: its permissions,
    // its name, and where its replies went.
    // ====================================
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

        // Colour codes sit between the words a message is checked for, so
        // they come off before anything is asserted about the text
        private String all() {
            return String.join("\n", sent).replaceAll("\u00a7.", "");
        }
    }

    private static Sender admin() {
        // What plugin.yml's child grant gives an operator
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

    // The command with the two server-bound seams stubbed out
    private static ActivityCommand command(ActivityManager manager, UUID target, String targetName) {
        return new ActivityCommand(manager, null) {
            @Override
            OfflinePlayer resolve(CommandSender sender, String name) {
                return stubTarget(target, targetName);
            }

            @Override
            List<String> onlineNames() {
                return List.of(targetName, "Somebody");
            }
        };
    }

    private static ActivityManager manager() {
        // every: 2 with one action added, so no point is ever awarded and the
        // record path stays away from Bukkit.getPlayer
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

    // Everything the plugin logged at INFO carrying the audit marker
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

    // ====================================
    // check
    // ====================================

    @Test
    void checkPrintsEveryFieldOfThePlayersDay() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));
        PlayerData data = manager.getStore().peek(player);
        // Straight through the POJO: crediting a point through the manager
        // would announce it, and that needs a running server
        data.record(3, new ActivityDef("vote", "Vote", Material.PAPER, null, 2, 1, 0), 50, 10, List.of(10, 20));

        Sender sender = admin();
        command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", new String[] {"check", "Steve"});
        String out = sender.all();

        assertTrue(out.contains("Steve"), out);
        // weekly points / bar max / claimed
        assertTrue(out.contains(data.points() + "/50"), out);
        assertTrue(out.contains("0 already claimed"), out);
        // daily points / daily max
        assertTrue(out.contains(data.dailyPoints() + "/10"), out);
        // rerolls used / allowance
        assertTrue(out.contains("0/1"), out);
        assertTrue(out.contains(data.weekKey()), out);
        assertTrue(out.contains(data.dayKey()), out);
        // the one revealed task, with its count and what it is worth today
        assertTrue(out.contains("Vote"), out);
        assertTrue(out.contains("revealed"), out);
        assertTrue(out.contains("count 3"), out);
        assertTrue(out.contains("worth 1 points"), out);
        // and every slot of the draw is listed
        assertEquals(data.tasks().size(),
            sender.sent.stream().filter(line -> line.contains("revealed") || line.contains("hidden")).count());
        assertTrue(out.contains("hidden"), out);
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

    // ====================================
    // the permission split
    // ====================================

    @Test
    void onlyCheckAnswersToTheCheckPermission() {
        assertEquals(CHECK, ActivityCommand.permissionFor("check"));
        assertEquals(ADMIN, ActivityCommand.permissionFor("reload"));
        assertEquals(ADMIN, ActivityCommand.permissionFor("reset"));
        assertEquals(ADMIN, ActivityCommand.permissionFor("reroll"));
        assertEquals(ADMIN, ActivityCommand.permissionFor("add"));
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
            new String[] {"reroll", "Steve"},
            new String[] {"add", "Steve", "vote", "1"},
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
            assertEquals(List.of(), lines, args[0] + " was audited despite being refused");
            // nothing was touched
            assertEquals(1, manager.getStore().peek(player).rerolls(), args[0]);
            assertEquals(0, manager.getStore().peek(player).count("vote"), args[0]);
            assertFalse(dirty(manager.getStore()), args[0]);
        }
    }

    // ====================================
    // reroll give-back
    // ====================================

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
        command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", new String[] {"reroll", "Steve"});

        assertEquals(0, data.rerolls());
        assertTrue(sender.all().contains("from 1 to 0"), sender.all());
        assertTrue(dirty(manager.getStore()), "the give-back was not marked for saving");
        // the draw, the points and the reveals are untouched
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
        command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", new String[] {"reroll", "Steve"});

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
        command(manager, player, "Steve").onCommand(sender.bukkit, null, "activity", new String[] {"reroll", "Steve"});

        assertTrue(sender.all().contains("no rerolls today"), sender.all());
        assertNull(manager.getStore().peek(player), "reroll created a row");
    }

    @Test
    void rerollWithoutAPlayerIsAUsageError() {
        ActivityManager manager = manager();
        Sender sender = admin();
        command(manager, UUID.randomUUID(), "Steve")
            .onCommand(sender.bukkit, null, "activity", new String[] {"reroll"});

        assertTrue(sender.all().contains("Usage:"), sender.all());
    }

    // ====================================
    // audit logging
    // ====================================

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
        assertEquals("ACTIVITY-AUDIT sender=Justin action=reset target=Steve uuid=" + player + " result=done",
            reset.get(0));

        List<String> add = audit(() ->
            command.onCommand(sender.bukkit, null, "activity", new String[] {"add", "Steve", "vote", "1", "--force"}));
        assertEquals(1, add.size(), String.valueOf(add));
        assertEquals("ACTIVITY-AUDIT sender=Justin action=add target=Steve uuid=" + player
            + " activity=vote count=1 force=true result=ADDED", add.get(0));

        manager.getStore().peek(player).reroll(List.of("vote"), 50);
        List<String> reroll = audit(() ->
            command.onCommand(sender.bukkit, null, "activity", new String[] {"reroll", "Steve"}));
        assertEquals(1, reroll.size(), String.valueOf(reroll));
        assertEquals("ACTIVITY-AUDIT sender=Justin action=reroll target=Steve uuid=" + player
            + " rerolls=1->0 result=done", reroll.get(0));
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
    }

    // A console sender has no Player behind it, and its name still has to
    // reach the log line
    @Test
    void theConsoleIsNamedInTheAuditLine() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        Sender console = new Sender("CONSOLE", Set.of(ADMIN, CHECK));

        List<String> lines = audit(() -> command(manager, player, "Steve")
            .onCommand(console.bukkit, null, "activity", new String[] {"reset", "Steve"}));

        assertEquals(1, lines.size(), String.valueOf(lines));
        assertTrue(lines.get(0).startsWith("ACTIVITY-AUDIT sender=CONSOLE action=reset"), lines.get(0));
    }

    // ====================================
    // tab completion
    // ====================================

    @Test
    void aCheckOnlySenderIsOfferedCheckAndNothingElse() {
        ActivityManager manager = manager();
        ActivityCommand command = command(manager, UUID.randomUUID(), "Steve");

        assertEquals(List.of("check"),
            command.onTabComplete(checkOnly().bukkit, null, "activity", new String[] {""}));
        assertEquals(List.of(),
            command.onTabComplete(checkOnly().bukkit, null, "activity", new String[] {"re"}));
        // ...and cannot complete a mutating subcommand's arguments either
        assertEquals(List.of(),
            command.onTabComplete(checkOnly().bukkit, null, "activity", new String[] {"reset", ""}));
        assertEquals(List.of(),
            command.onTabComplete(checkOnly().bukkit, null, "activity", new String[] {"add", "Steve", ""}));
        // but does get a player for 'check'
        assertEquals(List.of("Steve"),
            command.onTabComplete(checkOnly().bukkit, null, "activity", new String[] {"check", "Ste"}));
    }

    @Test
    void anAdminIsOfferedEverySubcommand() {
        ActivityManager manager = manager();
        ActivityCommand command = command(manager, UUID.randomUUID(), "Steve");

        assertEquals(List.of("reload", "check", "reset", "reroll", "add"),
            command.onTabComplete(admin().bukkit, null, "activity", new String[] {""}));
        assertEquals(List.of("Steve"),
            command.onTabComplete(admin().bukkit, null, "activity", new String[] {"reroll", "Ste"}));
    }

    @Test
    void aSenderWithNeitherPermissionIsOfferedNothing() {
        ActivityManager manager = manager();
        Sender nobody = new Sender("Player", Set.of());

        assertEquals(List.of(), command(manager, UUID.randomUUID(), "Steve")
            .onTabComplete(nobody.bukkit, null, "activity", new String[] {""}));
    }

    // ====================================
    // the shipped text
    // ====================================

    @Test
    void everyNewAdminMessageIsShipped() {
        YamlConfiguration messages = YamlConfiguration
            .loadConfiguration(new File("src/main/resources/messages.yml"));

        for (String key : List.of("check-no-data", "check-header", "check-points", "check-daily",
            "check-rerolls", "check-keys", "check-no-tasks", "check-task-revealed", "check-task-hidden",
            "reroll-given", "reroll-none-used")) {
            String value = messages.getString("admin." + key);
            assertFalse(value == null || value.isBlank(), "admin." + key + " is missing");
        }

        String usage = messages.getString("admin.usage", "");
        assertTrue(usage.contains("check"), usage);
        assertTrue(usage.contains("reroll"), usage);

        String pluginUsage = YamlConfiguration
            .loadConfiguration(new File("src/main/resources/plugin.yml"))
            .getString("commands.activity.usage", "");
        assertTrue(pluginUsage.contains("check"), pluginUsage);
        assertTrue(pluginUsage.contains("reroll"), pluginUsage);
    }
}
