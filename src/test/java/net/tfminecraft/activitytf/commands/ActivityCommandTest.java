package net.tfminecraft.activitytf.commands;

import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.models.PlayerData;
import net.tfminecraft.activitytf.commands.ActivityCommand.Outcome;
import net.tfminecraft.activitytf.gui.ActivityGui;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.managers.TestManagers;
import net.tfminecraft.activitytf.models.ActivityDef;
import net.tfminecraft.activitytf.models.RewardEntry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityCommandTest {

    private static String[] add(String... trailing) {
        String[] args = new String[4 + trailing.length];
        args[0] = "add";
        args[1] = "Justin";
        args[2] = "vote";
        args[3] = "1";
        System.arraycopy(trailing, 0, args, 4, trailing.length);
        return args;
    }

    @Test
    void addIsGatedUnlessForceIsAskedFor() {
        assertFalse(ActivityCommand.forced(add()));
        assertTrue(ActivityCommand.forced(add("--force")));
    }

    @Test
    void onlyTheExactFlagCountsAsForce() {
        assertFalse(ActivityCommand.forced(add("--FORCE")));
        assertFalse(ActivityCommand.forced(add("--Force")));
        assertFalse(ActivityCommand.forced(add("force")));
        assertFalse(ActivityCommand.forced(add("-f")));
        assertFalse(ActivityCommand.forced(add("")));

        assertFalse(ActivityCommand.wellFormedAdd(add("--FORCE")));
    }

    @Test
    void theUsageStringsDocumentForce() {
        String usage = YamlConfiguration
            .loadConfiguration(new File("src/main/resources/messages.yml"))
            .getString("admin.usage", "");
        assertTrue(usage.contains("--force"), usage);

        String pluginUsage = YamlConfiguration
            .loadConfiguration(new File("src/main/resources/plugin.yml"))
            .getString("commands.activity.usage", "");
        assertTrue(pluginUsage.contains("--force"), pluginUsage);
    }

    @Test
    void nothingButOneTrailingForceIsAWellFormedAdd() {
        assertTrue(ActivityCommand.wellFormedAdd(add()));
        assertTrue(ActivityCommand.wellFormedAdd(add("--force")));

        assertFalse(ActivityCommand.wellFormedAdd(add("--froce")));
        assertFalse(ActivityCommand.wellFormedAdd(add("-force")));
        assertFalse(ActivityCommand.wellFormedAdd(add("force")));
        assertFalse(ActivityCommand.wellFormedAdd(add("")));
        assertFalse(ActivityCommand.wellFormedAdd(add("--force", "extra")));
        assertFalse(ActivityCommand.wellFormedAdd(add("--force", "extra", "junk")));
        assertFalse(ActivityCommand.wellFormedAdd(new String[] {"add", "Justin", "vote"}));
    }

    private static ActivityCommand command(ActivityManager manager) {
        return new ActivityCommand(manager, null);
    }

    private static ActivityManager manager() {
        return TestManagers.manager(new ActivityDef("vote", "Vote", Material.PAPER, null, 2, 1, 0));
    }

    @Test
    void aGatedAddToATaskThatIsNotRevealedCreditsNothingAndSaysSo() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();

        assertEquals(Outcome.NOT_A_TASK, command(manager).add(player, "vote", 5, false).outcome());
        assertNull(manager.getStore().peek(player));
    }

    @Test
    void aGatedAddToADrawnButHiddenTaskCreditsNothing() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.tasks(player);

        assertEquals(Outcome.NOT_A_TASK, command(manager).add(player, "vote", 5, false).outcome());
        assertEquals(0, manager.getStore().get(player).count("vote"));
    }

    @Test
    void aGatedAddToARevealedTaskIsCredited() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));

        assertEquals(Outcome.ADDED, command(manager).add(player, "vote", 1, false).outcome());
        assertEquals(1, manager.getStore().get(player).count("vote"));
    }

    @Test
    void forceCreditsATaskThatWasNeverDrawnOrRevealed() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();

        assertEquals(Outcome.ADDED, command(manager).add(player, "vote", 1, true).outcome());
        assertEquals(1, manager.getStore().get(player).count("vote"));
    }

    @Test
    void anActivityNobodyLoadedIsUnknownEitherWay() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();

        assertEquals(Outcome.UNKNOWN_ACTIVITY, command(manager).add(player, "nope", 1, false).outcome());
        assertEquals(Outcome.UNKNOWN_ACTIVITY, command(manager).add(player, "nope", 1, true).outcome());
    }

    @Test
    void aSpentDailyBudgetIsReportedRatherThanCountedAsAdded() {
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 0));
        TestManagers.limits(manager, 50, 0);
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));

        assertEquals(Outcome.CAPPED_DAILY, command(manager).add(player, "vote", 5, false).outcome());
        assertEquals(5, manager.getStore().get(player).count("vote"));
        assertEquals(0, manager.getStore().get(player).points());
    }

    @Test
    void aFullWeeklyBarIsReportedRatherThanCountedAsAdded() {
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 0));
        TestManagers.limits(manager, 0, 10);
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));

        assertEquals(Outcome.CAPPED_WEEKLY, command(manager).add(player, "vote", 5, false).outcome());
        assertEquals(0, manager.getStore().get(player).points());
    }

    @Test
    void anActivityThatHasGivenAllItCanTodayIsReported() {
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 1));
        TestManagers.limits(manager, 0, 10);
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));
        ActivityCommand command = command(manager);

        assertEquals(Outcome.CAPPED_WEEKLY, command.add(player, "vote", 1, false).outcome());
        assertEquals(Outcome.CAPPED_ACTIVITY, command.add(player, "vote", 1, false).outcome());
    }

    @Test
    void forceAwardsTheFullWorthPastTheActivityCapAndTheDailyBudget() {
        TestManagers.bukkit();
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5));
        TestManagers.limits(manager, 100, 10);
        UUID player = UUID.randomUUID();

        ActivityCommand.Added added = command(manager).add(player, "vote", 50, true);

        assertEquals(Outcome.ADDED, added.outcome());
        assertEquals(50, added.points());
        assertEquals(50, manager.getStore().get(player).points());
        assertEquals(50, manager.getStore().get(player).count("vote"));
        assertEquals(0, manager.getStore().get(player).dailyPoints());
    }

    @Test
    void theSameAddUnforcedIsStillHeldToTheActivityCap() {
        TestManagers.bukkit();
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5));
        TestManagers.limits(manager, 100, 10);
        UUID player = UUID.randomUUID();
        manager.reveal(player, manager.tasks(player).tasks().indexOf("vote"));

        ActivityCommand.Added added = command(manager).add(player, "vote", 50, false);

        assertEquals(Outcome.ADDED, added.outcome());
        assertEquals(5, added.points());
        assertEquals(5, manager.getStore().get(player).points());
        assertEquals(5, manager.getStore().get(player).dailyPoints());
    }

    @Test
    void forceStillStopsAtTheWeeklyMaximum() {
        TestManagers.bukkit();
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5));
        TestManagers.limits(manager, 10, 10);
        UUID player = UUID.randomUUID();

        ActivityCommand.Added added = command(manager).add(player, "vote", 50, true);

        assertEquals(Outcome.CLAMPED_WEEKLY, added.outcome());
        assertEquals(10, added.points());
        assertEquals(10, manager.getStore().get(player).points());

        ActivityCommand.Added again = command(manager).add(player, "vote", 5, true);
        assertEquals(Outcome.CAPPED_WEEKLY, again.outcome());
        assertEquals(0, again.points());
    }

    @Test
    void partialProgressTowardsTheNextPointIsStillAdded() {
        ActivityManager manager = manager();
        UUID player = UUID.randomUUID();

        assertEquals(Outcome.ADDED, command(manager).add(player, "vote", 1, true).outcome());
    }

    @Test
    void everyOutcomeHasAShippedMessage() {
        YamlConfiguration messages = YamlConfiguration
            .loadConfiguration(new File("src/main/resources/messages.yml"));

        assertEquals("admin.add-done", Outcome.ADDED.messageKey());
        assertEquals("admin.add-not-a-task", Outcome.NOT_A_TASK.messageKey());
        assertEquals("admin.add-capped-activity", Outcome.CAPPED_ACTIVITY.messageKey());
        assertEquals("admin.add-capped-daily", Outcome.CAPPED_DAILY.messageKey());
        assertEquals("admin.add-capped-vote-share", Outcome.CAPPED_VOTE_SHARE.messageKey());
        assertEquals("admin.add-capped-weekly", Outcome.CAPPED_WEEKLY.messageKey());
        assertEquals("admin.add-clamped-weekly", Outcome.CLAMPED_WEEKLY.messageKey());
        assertEquals("admin.unknown-activity", Outcome.UNKNOWN_ACTIVITY.messageKey());

        for (Outcome outcome : Outcome.values()) {
            String value = messages.getString(outcome.messageKey());
            assertFalse(value == null || value.isBlank(), outcome.messageKey() + " is missing");
        }

        String plain = messages.getString("admin.add-done");
        assertFalse(plain == null || plain.contains("%points%"), plain);
        String withPoints = messages.getString("admin.add-done-points");
        assertTrue(withPoints != null && withPoints.contains("%points%"), withPoints);

        String notATask = messages.getString("admin.add-not-a-task");
        assertTrue(notATask.contains("%activity%"), notATask);
        assertTrue(notATask.contains("%player%"), notATask);
        assertTrue(notATask.contains("--force"), notATask);
    }

    @Test
    void openingTheMenuAsksForTheDailyReward() {
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("a", "a", Material.PAPER, null, 1, 1, 0));
        TestManagers.messages(manager);
        TestManagers.dailyRewards(manager, Map.of("vip", new RewardEntry(1, "Steel", List.of(),
            List.of(new RewardEntry.Item("m.material.steel", 1)))));
        UUID uuid = UUID.randomUUID();
        manager.reveal(uuid, 0);
        List<String> chat = new ArrayList<>();
        Player player = TestManagers.player(uuid, Set.of("activity.use", "group.vip"), chat);
        ActivityGui gui = new ActivityGui(manager) {
            @Override
            public Inventory build(Player who) {
                return null;
            }
        };

        new ActivityCommand(manager, gui).onCommand(player, null, "activity", new String[0]);

        assertTrue(chat.stream().anyMatch(line -> line.contains("could not be handed over")), chat.toString());
    }

    @TempDir
    Path directory;

    @Test
    void menuAccessRejectsConsoleAndPlayersWithoutUsePermission() {
        ActivityManager manager = manager();
        TestManagers.messages(manager);
        Sender console = new Sender(Set.of("activity.admin"));
        assertTrue(command(manager).onCommand(console.bukkit, null, "activity", new String[0]));
        assertEquals(List.of(manager.getConfiguration().messages().get("admin.players-only")), console.sent);
        List<String> chat = new ArrayList<>();
        Player player = TestManagers.player(UUID.randomUUID(), Set.of(), chat);
        assertTrue(command(manager).onCommand(player, null, "activity", new String[0]));
        assertEquals(List.of(manager.getConfiguration().messages().get("admin.no-permission")), chat);
    }

    @Test
    void rewardsBeforeConfigurationInitializationHasNoLockToReport() {
        ActivityManager manager = manager();
        TestManagers.messages(manager);
        Sender admin = new Sender(Set.of("activity.admin"));
        assertTrue(command(manager).onCommand(admin.bukkit, null, "activity", new String[] {"rewards"}));
        assertTrue(admin.sent.isEmpty());
    }

    @Test
    void reloadUsesDiskConfigurationButKeepsRewardsLockedUntilAnExplicitApply() throws Exception {
        try (CommandFixture f = new CommandFixture(directory)) {
            f.settings.set("bar.max", 40);
            f.settings.set("rewards.pool", List.of(Map.of("display", "New reward", "commands", List.of("say new"))));
            f.saveSettings();
            Sender admin = f.admin();

            f.run(admin, "ReLoAd");

            assertEquals(40, f.manager.getConfiguration().barMax());
            assertEquals("Original reward", f.manager.getConfiguration().rewardPool().getFirst().display());
            assertTrue(f.manager.getConfiguration().rewardsPending());
            assertEquals(f.message("admin.reloaded"), admin.sent.getFirst());
            assertTrue(admin.plain().contains("rewards are locked"));
            assertTrue(admin.plain().contains("different rewards"));
            assertEquals(List.of("ACTIVITY-AUDIT sender=\"Operator\" action=reload result=done"), f.audit());
            admin.sent.clear();
            f.logs.clear();

            f.run(admin, "rewards", "ApPlY");

            ActivityConfiguration config = f.manager.getConfiguration();
            assertEquals("New reward", config.rewardPool().getFirst().display());
            assertFalse(config.rewardsPending());
            assertEquals(List.of(config.messages().get("admin.rewards-applied", "%week%", config.rewardWeek())),
                admin.sent);
            assertTrue(Files.readString(f.rewardLock()).contains("New reward"));
            assertEquals(List.of("ACTIVITY-AUDIT sender=\"Operator\" action=rewards-apply week="
                + config.rewardWeek() + " result=done"), f.audit());
        }
    }

    @Test
    void applyingRewardsReportsAnUnwritableLockAndKeepsThePreviouslyLockedRewards() throws Exception {
        try (CommandFixture f = new CommandFixture(directory)) {
            String original = Files.readString(f.rewardLock());
            f.settings.set("rewards.pool", List.of(Map.of("display", "Uncommitted", "commands", List.of("say new"))));
            f.saveSettings();
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(f.rewardLock());
            Sender admin = f.admin();
            try {
                Files.setPosixFilePermissions(f.rewardLock(), Set.of(PosixFilePermission.OWNER_READ));

                f.run(admin, "rewards", "apply");

            } finally {
                Files.setPosixFilePermissions(f.rewardLock(), permissions);
            }
            assertEquals(List.of(f.message("admin.rewards-apply-failed")), admin.sent);
            assertEquals("Original reward", f.manager.getConfiguration().rewardPool().getFirst().display());
            assertTrue(f.manager.getConfiguration().rewardsPending());
            assertEquals(original, Files.readString(f.rewardLock()));
            assertEquals(List.of("ACTIVITY-AUDIT sender=\"Operator\" action=rewards-apply week="
                + f.manager.getConfiguration().rewardWeek() + " result=save-failed"), f.audit());
        }
    }

    @Test
    void playerResolutionUsesExactOnlineNamesAndOnlyPreviouslyPlayedCachedPlayers() throws Exception {
        try (CommandFixture f = new CommandFixture(directory)) {
            Player online = f.online("Steve");
            OfflinePlayer cached = f.cached("Alex", true);
            f.cached("NeverJoined", false);
            Sender admin = f.admin();

            f.manager.getStore().get(online.getUniqueId()).addPoints(3, 50);
            f.cached("Steve", false);
            f.run(admin, "check", "Steve");
            assertEquals(f.manager.getConfiguration().messages().get("admin.check-header", "%player%", "Steve"),
                admin.sent.getFirst(), "an exact online player must take precedence over the offline cache");
            admin.sent.clear();
            f.run(admin, "check", "Alex");
            assertEquals(List.of(f.manager.getConfiguration().messages().get("admin.check-no-data", "%player%", "Alex")),
                admin.sent);
            admin.sent.clear();
            f.run(admin, "check", "NeverJoined");
            f.run(admin, "check", "Unknown");

            assertEquals(List.of(f.manager.getConfiguration().messages().get("admin.unknown-player", "%player%", "NeverJoined"),
                f.manager.getConfiguration().messages().get("admin.unknown-player", "%player%", "Unknown")), admin.sent);
            assertNull(f.manager.getStore().peek(cached.getUniqueId()));
        }
    }

    @Test
    void resetCanUseACachedPlayerWhoseNameIsMissingAndRejectsMissingArguments() throws Exception {
        try (CommandFixture f = new CommandFixture(directory)) {
            OfflinePlayer target = f.cached("TypedName", true);
            when(target.getName()).thenReturn(null);
            PlayerData data = f.manager.getStore().get(target.getUniqueId());
            data.addPoints(8, 50);
            Sender admin = f.admin();

            f.run(admin, "reset", "TypedName");

            assertEquals(0, data.points());
            assertEquals(List.of(f.manager.getConfiguration().messages().get("admin.reset-done", "%player%", "TypedName")),
                admin.sent);
            assertTrue(f.audit().getFirst().contains("target=\"TypedName\" uuid=" + target.getUniqueId()));
            admin.sent.clear();
            f.logs.clear();
            f.run(admin, "reset");
            assertEquals(List.of(f.message("admin.usage"), f.message("admin.usage-addpoints")), admin.sent);
            assertEquals(List.of("ACTIVITY-AUDIT sender=\"Operator\" action=reset result=usage"), f.audit());
        }
    }

    @Test
    void addRejectsMalformedArgumentsAndInvalidCountsWithoutCreatingPlayerData() throws Exception {
        try (CommandFixture f = new CommandFixture(directory)) {
            Player target = f.online("Steve");
            for (String[] args : List.of(new String[] {"add"}, new String[] {"add", "Steve", "vote"},
                new String[] {"add", "Steve", "vote", "1", "--FORCE"})) {
                Sender admin = f.admin();
                f.logs.clear();
                f.run(admin, args);
                assertEquals(List.of(f.message("admin.usage"), f.message("admin.usage-addpoints")), admin.sent);
                assertTrue(f.audit().getFirst().endsWith("action=add result=usage"));
            }
            for (String value : List.of("many", "2147483648", "0", "-1", "1000001")) {
                Sender admin = f.admin();
                f.logs.clear();
                f.run(admin, "add", "Steve", "vote", value);
                boolean number = value.equals("many") || value.equals("2147483648");
                assertEquals(List.of(number
                    ? f.manager.getConfiguration().messages().get("admin.invalid-number", "%value%", value)
                    : f.manager.getConfiguration().messages().get("admin.out-of-range", "%max%", 1_000_000)), admin.sent);
                assertTrue(f.audit().getFirst().endsWith(number ? "result=invalid-number" : "result=out-of-range"));
                assertNull(f.manager.getStore().peek(target.getUniqueId()));
            }
        }
    }

    @Test
    void addReportsUnknownActivitiesHiddenTasksAndTheReservedVoteBudget() throws Exception {
        try (CommandFixture f = new CommandFixture(directory)) {
            Player target = f.online("Steve");
            UUID uuid = target.getUniqueId();
            Sender admin = f.admin();
            f.run(admin, "add", "Steve", "gone", "1");
            assertEquals(List.of(f.manager.getConfiguration().messages().get("admin.unknown-activity", "%activity%", "gone")), admin.sent);
            admin.sent.clear();
            f.run(admin, "add", "Steve", "quest", "1");
            assertEquals(List.of(f.manager.getConfiguration().messages().get("admin.add-not-a-task",
                "%activity%", "quest", "%player%", "Steve")), admin.sent);
            assertNull(f.manager.getStore().peek(uuid));
            f.manager.reveal(uuid, f.manager.tasks(uuid).tasks().indexOf("quest"));
            f.run(admin, "add", "Steve", "quest", "5");
            admin.sent.clear();
            f.logs.clear();

            f.run(admin, "add", "Steve", "quest", "1");

            assertEquals(5, f.manager.getStore().peek(uuid).points());
            assertEquals(List.of(f.manager.getConfiguration().messages().get("admin.add-capped-vote-share",
                "%count%", 1, "%activity%", "quest", "%player%", "Steve", "%points%", 0, "%max%", 50)), admin.sent);
            assertTrue(f.audit().getFirst().endsWith("result=CAPPED_VOTE_SHARE"));
        }
    }

    @Test
    void completionsUseOnlineNamesLoadedActivitiesAndExactForceFlagWithoutMutatingData() throws Exception {
        try (CommandFixture f = new CommandFixture(directory)) {
            Player steve = f.online("Steve");
            Player alex = f.online("Alex");
            f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(steve, alex));
            Sender admin = f.admin();
            assertEquals(List.of("Steve"), f.complete(admin, "reset", "sT"));
            assertEquals(List.of("Steve", "Alex"), f.complete(admin, "check", ""));
            assertEquals(List.of("quest"), f.complete(admin, "ADD", "Steve", "Q"));
            assertEquals(List.of("vote", "quest"), f.complete(admin, "add", "Steve", ""));
            assertEquals(List.of("--force"), f.complete(admin, "add", "Steve", "vote", "1", "--F"));
            assertEquals(List.of(), f.complete(admin, "add", "Steve", "vote", "1", "typo"));
            assertEquals(List.of(), f.complete(admin, "add", "Steve", "vote", ""));
            assertEquals(List.of(), f.complete(admin, "unknown", ""));
            assertEquals(List.of(), f.complete(admin, "rewards", "apply", ""));
            Sender checker = new Sender(Set.of("activity.check"));
            assertEquals(List.of("check"), f.complete(checker, "C"));
            assertEquals(List.of(), f.complete(checker, "add", "Steve", ""));
            assertEquals(List.of(), f.complete(new Sender(Set.of()), ""));
            assertNull(f.manager.getStore().peek(steve.getUniqueId()));
            assertTrue(f.audit().isEmpty());
        }
    }

    private static final class Sender {
        final CommandSender bukkit = mock(CommandSender.class);
        final List<String> sent = new ArrayList<>();

        Sender(Set<String> permissions) {
            when(bukkit.getName()).thenReturn("Operator");
            when(bukkit.hasPermission(anyString())).thenAnswer(call -> permissions.contains(call.getArgument(0)));
            doAnswer(call -> { sent.add(call.getArgument(0)); return null; }).when(bukkit).sendMessage(anyString());
        }

        String plain() { return net.md_5.bungee.api.ChatColor.stripColor(String.join("\n", sent)); }
    }

    private static final class CommandFixture implements AutoCloseable {
        final Path folder;
        final YamlConfiguration settings = new YamlConfiguration();
        final List<String> logs = new ArrayList<>();
        final MockedStatic<Bukkit> bukkit;
        final ActivityManager manager;
        final ActivityCommand command;

        CommandFixture(Path folder) throws Exception {
            this.folder = folder;
            Files.copy(Path.of("src/main/resources/messages.yml"), folder.resolve("messages.yml"));
            settings.set("bar.max", 50);
            settings.set("bar.daily-max", 10);
            settings.set("bar.milestones", List.of(10, 20));
            settings.set("bar.vote-share", 50);
            settings.set("activities.vote.points", 1);
            settings.set("activities.vote.daily-cap", 5);
            settings.set("activities.quest.points", 1);
            settings.set("rewards.pool", List.of(Map.of("display", "Original reward", "commands", List.of("say original"))));
            saveSettings();
            JavaPlugin plugin = mock(JavaPlugin.class);
            when(plugin.getDataFolder()).thenReturn(folder.toFile());
            when(plugin.getConfig()).thenAnswer(call -> YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile()));
            when(plugin.getResource("messages.yml")).thenAnswer(call -> Files.newInputStream(Path.of("src/main/resources/messages.yml")));
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) { logs.add(record.getMessage()); }
                @Override public void flush() { }
                @Override public void close() { }
            });
            when(plugin.getLogger()).thenReturn(logger);
            bukkit = mockStatic(Bukkit.class);
            try {
                bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
                var constructor = ActivityManager.class.getDeclaredConstructor(JavaPlugin.class);
                constructor.setAccessible(true);
                manager = constructor.newInstance(plugin);
                manager.getConfiguration().load();
                manager.getStore().load();
                command = new ActivityCommand(manager, null);
                logs.clear();
            } catch (Exception | Error failure) {
                bukkit.close();
                throw failure;
            }
        }

        void saveSettings() throws Exception { settings.save(folder.resolve("config.yml").toFile()); }
        Path rewardLock() { return folder.resolve(ActivityConfiguration.REWARD_LOCK_FILE); }
        Sender admin() { return new Sender(Set.of("activity.admin", "activity.check")); }
        String message(String key) { return manager.getConfiguration().messages().get(key); }
        List<String> audit() { return logs.stream().filter(line -> line.startsWith("ACTIVITY-AUDIT ")).toList(); }
        void run(Sender sender, String... args) { assertTrue(command.onCommand(sender.bukkit, null, "activity", args)); }
        List<String> complete(Sender sender, String... args) { return command.onTabComplete(sender.bukkit, null, "activity", args); }

        Player online(String name) {
            Player player = mock(Player.class);
            UUID uuid = UUID.randomUUID();
            when(player.getName()).thenReturn(name);
            when(player.getUniqueId()).thenReturn(uuid);
            bukkit.when(() -> Bukkit.getPlayerExact(name)).thenReturn(player);
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            return player;
        }

        OfflinePlayer cached(String name, boolean played) {
            OfflinePlayer player = mock(OfflinePlayer.class);
            when(player.getName()).thenReturn(name);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.hasPlayedBefore()).thenReturn(played);
            bukkit.when(() -> Bukkit.getOfflinePlayerIfCached(name)).thenReturn(player);
            return player;
        }

        @Override public void close() { bukkit.close(); }
    }
}
