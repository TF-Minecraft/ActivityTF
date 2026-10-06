package net.tfminecraft.activitytf.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.tfminecraft.activitytf.models.RewardEntry;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityConfigurationRewardLockTest {

    private static final String WEEK = "2026-10-05";
    private static final String NEXT_WEEK = "2026-10-12";

    private static final String PROLOGUE = """
        bar:
          milestones: [10, 20, 30]
        rewards:
          multiplier: 2
          multiplier-pools: [pool_prologue]
          drops:
            drop_1: pool_prologue
            drop_2: pool_prologue
            drop_3: pool_skin
          pool_prologue:
            - weight: 65
              display: "x2 Ignitium"
              items:
                - item: IRON_INGOT
                  amount: 2
          pool_skin:
            - weight: 1
              display: "Skin"
              items:
                - item: PAPER
        daily-reward:
          groups:
            default: pool_prologue
        """;

    private static final String ACT_ONE = """
        bar:
          milestones: [10, 20, 40]
        rewards:
          multiplier: 3
          multiplier-pools: [pool_act1]
          drops:
            drop_1: pool_act1
            drop_2: pool_act1
            drop_3: pool_skin
          pool_act1:
            - weight: 65
              display: "x6 Ignitium"
              items:
                - item: GOLD_INGOT
                  amount: 6
          pool_skin:
            - weight: 1
              display: "Skin"
              items:
                - item: PAPER
        daily-reward:
          groups:
            default: pool_act1
        """;

    @TempDir
    Path dir;

    private final List<String> logged = new ArrayList<>();
    private JavaPlugin plugin;
    private ActivityConfiguration config;
    private File lock;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        plugin = TestPlugins.capturing(logged);
        Field dataFolder = JavaPlugin.class.getDeclaredField("dataFolder");
        dataFolder.setAccessible(true);
        dataFolder.set(plugin, dir.toFile());

        config = new ActivityConfiguration(plugin);
        set("barMax", 50);
        set("resetHour", 0);
        set("resetDay", java.time.DayOfWeek.MONDAY);
        lock = dir.resolve(ActivityConfiguration.REWARD_LOCK_FILE).toFile();
    }

    private void set(String field, Object value) throws ReflectiveOperationException {
        Field f = ActivityConfiguration.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(config, value);
    }

    private static YamlConfiguration yaml(String content) {
        return YamlConfiguration.loadConfiguration(new StringReader(content));
    }

    private void stageAndLock(String content, String week) {
        config.stageRewards(yaml(content));
        config.lockRewards(lock, week, false);
    }

    private String firstItem(String pool) {
        List<RewardEntry> entries = config.rewardPool(pool);
        return entries.isEmpty() ? null : entries.get(0).items().get(0).path();
    }

    private boolean loggedContains(String fragment) {
        return logged.stream().anyMatch(message -> message != null && message.contains(fragment));
    }

    @Test
    void theFirstLoadLocksConfigRewardsForTheWeek() {
        stageAndLock(PROLOGUE, WEEK);

        assertTrue(lock.isFile());
        YamlConfiguration written = YamlConfiguration.loadConfiguration(lock);
        assertEquals(WEEK, written.getString("week"));
        assertEquals(2, written.getInt("rewards-config.rewards.multiplier"));
        assertEquals(1, written.getMapList("rewards-config.rewards.pool_prologue").size());
        assertEquals(List.of(10, 20, 30), written.getIntegerList("rewards-config.bar.milestones"));
        assertEquals(WEEK, config.rewardWeek());
        assertFalse(config.rewardsPending());
        assertEquals("IRON_INGOT", firstItem("pool_prologue"));
    }

    @Test
    void aReloadInTheSameWeekKeepsTheLockedRewards() {
        stageAndLock(PROLOGUE, WEEK);
        logged.clear();

        stageAndLock(ACT_ONE, WEEK);

        assertTrue(config.rewardsPending());
        assertEquals("IRON_INGOT", firstItem("pool_prologue"));
        assertTrue(config.rewardPool("pool_act1").isEmpty());
        assertEquals(2, config.rewardMultiplier());
        assertEquals(List.of(10, 20, 30), config.milestones());
        assertEquals("pool_prologue", ActivityConfiguration.referencedPool(config.milestoneDrops().get(10)));
        assertEquals("pool_prologue", ActivityConfiguration.referencedPool(config.dailyRewards().get("default")));
        assertTrue(loggedContains("differ from the ones locked for the week of " + WEEK));
        assertTrue(loggedContains(NEXT_WEEK + " 00:00"));
        assertEquals(WEEK, YamlConfiguration.loadConfiguration(lock).getString("week"));
    }

    @Test
    void theLockedParseDoesNotRepeatConfigWarnings() {
        stageAndLock(PROLOGUE.replace("IRON_INGOT", "NOT_A_MATERIAL"), WEEK);
        assertTrue(loggedContains("NOT_A_MATERIAL"));
        logged.clear();

        stageAndLock(ACT_ONE, WEEK);

        assertFalse(loggedContains("NOT_A_MATERIAL"));
    }

    @Test
    void theNextWeekSwitchesToTheStagedRewards() {
        stageAndLock(PROLOGUE, WEEK);
        stageAndLock(ACT_ONE, WEEK);
        logged.clear();

        config.lockRewards(lock, NEXT_WEEK, false);

        assertFalse(config.rewardsPending());
        assertEquals(NEXT_WEEK, config.rewardWeek());
        assertEquals("GOLD_INGOT", firstItem("pool_act1"));
        assertEquals(3, config.rewardMultiplier());
        assertEquals(List.of(10, 20, 40), config.milestones());
        assertEquals(NEXT_WEEK, YamlConfiguration.loadConfiguration(lock).getString("week"));
        assertTrue(loggedContains("New activity week " + NEXT_WEEK));
    }

    @Test
    void rollRewardWeekLocksOnceTheWeekChanges() {
        stageAndLock(PROLOGUE, WEEK);
        stageAndLock(ACT_ONE, WEEK);

        config.rollRewardWeek(WEEK);
        assertTrue(config.rewardsPending());

        config.rollRewardWeek(NEXT_WEEK);
        assertEquals(NEXT_WEEK, config.rewardWeek());
        assertEquals("GOLD_INGOT", firstItem("pool_act1"));
    }

    @Test
    void rollRewardWeekDoesNothingBeforeTheFirstLoad() {
        config.rollRewardWeek(WEEK);

        assertNull(config.rewardWeek());
        assertFalse(lock.exists());
    }

    @Test
    void applyNowUsesTheStagedRewardsThisWeek() {
        stageAndLock(PROLOGUE, WEEK);
        config.stageRewards(yaml(ACT_ONE));

        assertTrue(config.lockRewards(lock, WEEK, true));

        assertFalse(config.rewardsPending());
        assertEquals("GOLD_INGOT", firstItem("pool_act1"));

        // A later reload in the same week keeps the applied rewards.
        stageAndLock(ACT_ONE, WEEK);
        assertFalse(config.rewardsPending());
        assertEquals("GOLD_INGOT", firstItem("pool_act1"));
    }

    @Test
    void applyNowKeepsTheLockWhenItCannotBeSaved() throws IOException {
        stageAndLock(PROLOGUE, WEEK);
        stageAndLock(ACT_ONE, WEEK);
        File blocked = dir.resolve("blocked").toFile();
        Files.createDirectories(blocked.toPath().resolve(ActivityConfiguration.REWARD_LOCK_FILE));

        assertFalse(config.lockRewards(new File(blocked, ActivityConfiguration.REWARD_LOCK_FILE), WEEK, true));

        assertEquals("IRON_INGOT", firstItem("pool_prologue"));
        assertTrue(loggedContains("Could not save " + ActivityConfiguration.REWARD_LOCK_FILE));
    }

    @Test
    void aLockWithoutAWeekIsReplaced() throws IOException {
        Files.writeString(lock.toPath(), "something: else\n");
        logged.clear();

        stageAndLock(PROLOGUE, WEEK);

        assertTrue(loggedContains("has no week or rewards-config"));
        assertEquals(WEEK, YamlConfiguration.loadConfiguration(lock).getString("week"));
        assertEquals("IRON_INGOT", firstItem("pool_prologue"));
    }

    @Test
    void identicalRewardsAreNotPending() {
        stageAndLock(PROLOGUE, WEEK);

        stageAndLock(PROLOGUE, WEEK);

        assertFalse(config.rewardsPending());
    }

    @Test
    void theSnapshotIgnoresJarDefaultRewards() {
        YamlConfiguration live = yaml("bar:\n  max: 50\n");
        live.setDefaults(yaml(PROLOGUE));

        YamlConfiguration snapshot = ActivityConfiguration.rewardSnapshot(live);

        assertFalse(snapshot.contains("rewards"));
        assertFalse(snapshot.contains("daily-reward.groups"));
        assertEquals(List.of(10, 20, 30), snapshot.getIntegerList("bar.milestones"));
    }

    @Test
    void theSnapshotKeepsFixedDropBlocks() {
        stageAndLock(PROLOGUE.replace("drop_3: pool_skin", "drop_3:\n      item: PAPER\n      amount: 3"), WEEK);
        stageAndLock(ACT_ONE, WEEK);

        RewardEntry fixed = config.milestoneDrops().get(30);
        assertEquals(List.of(new RewardEntry.Item("PAPER", 3)), fixed.items());
    }

    @Test
    void nextResetIsOneWeekAfterTheLockedWeek() throws ReflectiveOperationException {
        set("resetHour", 6);

        assertEquals("2026-10-12 06:00", config.nextReset(WEEK));
    }

    @Test
    void activeRewardsReflectTheLockedTable() {
        stageAndLock(PROLOGUE, WEEK);
        stageAndLock(ACT_ONE, WEEK);

        ActivityConfiguration.RewardTable table = config.activeRewards();
        assertEquals(java.util.Set.of("pool_prologue", "pool_skin"), table.pools().keySet());
        assertEquals(2, table.multiplier());
        assertEquals(java.util.Set.of("pool_prologue"), table.multiplierPools());
    }
}
