package tfmc.justin.activity.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import sun.reflect.ReflectionFactory;
import tfmc.justin.activity.models.RewardEntry;

import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// rewards.pool and bar.milestones parsing: private instance methods
// (loadRewardPool, loadMilestones) reached the same way
// ActivityConfigurationStationTest reaches loadActivities/loadStation -
// load() itself needs a live Bukkit server, so a bypass-allocated JavaPlugin
// with a real Logger stands in and the private parser is invoked directly.
// ====================================
class ActivityConfigurationRewardsTest {

    private static final class TestPlugin extends JavaPlugin {
    }

    private static JavaPlugin stubPlugin() {
        try {
            ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
            Constructor<Object> objectCtor = Object.class.getDeclaredConstructor();
            Constructor<?> bypass = rf.newConstructorForSerialization(TestPlugin.class, objectCtor);
            JavaPlugin plugin = (JavaPlugin) bypass.newInstance();

            Field loggerField = JavaPlugin.class.getDeclaredField("logger");
            loggerField.setAccessible(true);
            loggerField.set(plugin, Logger.getLogger("ActivityConfigurationRewardsTest"));

            return plugin;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static FileConfiguration yaml(String content) {
        return YamlConfiguration.loadConfiguration(new StringReader(content));
    }

    private static List<RewardEntry> loadRewardPool(String yamlContent) {
        try {
            ActivityConfiguration config = new ActivityConfiguration(stubPlugin());
            Method method = ActivityConfiguration.class.getDeclaredMethod("loadRewardPool", FileConfiguration.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<RewardEntry> result = (List<RewardEntry>) method.invoke(config, yaml(yamlContent));
            return result;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Integer> loadMilestones(int barMax, List<Integer> raw) {
        try {
            ActivityConfiguration config = new ActivityConfiguration(stubPlugin());
            Field barMaxField = ActivityConfiguration.class.getDeclaredField("barMax");
            barMaxField.setAccessible(true);
            barMaxField.setInt(config, barMax);

            Method method = ActivityConfiguration.class.getDeclaredMethod("loadMilestones", List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Integer> result = (List<Integer>) method.invoke(config, raw);
            return result;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ====================================
    // rewards.pool
    // ====================================

    @Test
    void aZeroWeightEntryIsSkipped() {
        List<RewardEntry> pool = loadRewardPool(
            "rewards:\n  pool:\n    - weight: 0\n      display: 'Nothing'\n      commands: ['say hi']\n"
                + "    - weight: 2\n      display: 'Something'\n      commands: ['say hi']\n");

        assertEquals(1, pool.size());
        assertEquals("Something", pool.get(0).display());
    }

    @Test
    void aNegativeWeightEntryIsSkipped() {
        List<RewardEntry> pool = loadRewardPool(
            "rewards:\n  pool:\n    - weight: -5\n      display: 'Nothing'\n      commands: ['say hi']\n");

        assertTrue(pool.isEmpty());
    }

    @Test
    void aMissingWeightDefaultsToOne() {
        List<RewardEntry> pool = loadRewardPool(
            "rewards:\n  pool:\n    - display: 'Default weight'\n      commands: ['say hi']\n");

        assertEquals(1, pool.size());
        assertEquals(1, pool.get(0).weight());
    }

    @Test
    void anEntryWithoutCommandsIsSkipped() {
        List<RewardEntry> pool = loadRewardPool(
            "rewards:\n  pool:\n    - weight: 5\n      display: 'No commands'\n"
                + "    - weight: 3\n      display: 'Has commands'\n      commands: ['say hi']\n");

        assertEquals(1, pool.size());
        assertEquals("Has commands", pool.get(0).display());
    }

    @Test
    void anEntryWithAnEmptyCommandsListIsSkipped() {
        List<RewardEntry> pool = loadRewardPool(
            "rewards:\n  pool:\n    - weight: 5\n      display: 'Empty commands'\n      commands: []\n");

        assertTrue(pool.isEmpty());
    }

    @Test
    void aNonMapListItemIsSkipped() {
        // getMapList() itself drops non-map entries before loadRewardPool ever
        // sees them - only the map entry survives
        List<RewardEntry> pool = loadRewardPool(
            "rewards:\n  pool:\n    - just a string\n    - weight: 4\n      display: 'Real entry'\n"
                + "      commands: ['say hi']\n");

        assertEquals(1, pool.size());
        assertEquals("Real entry", pool.get(0).display());
    }

    @Test
    void anEmptyPoolParsesToAnEmptyList() {
        assertTrue(loadRewardPool("rewards:\n  pool: []\n").isEmpty());
        assertTrue(loadRewardPool("other: value\n").isEmpty());
    }

    @Test
    void weightIsClampedSoTheCumulativeTotalCannotOverflow() {
        List<RewardEntry> pool = loadRewardPool(
            "rewards:\n  pool:\n    - weight: 999999999\n      display: 'Huge'\n      commands: ['say hi']\n");

        assertEquals(1, pool.size());
        assertEquals(1_000_000, pool.get(0).weight());
    }

    // ====================================
    // bar.milestones
    // ====================================

    @Test
    void unsortedAndDuplicatedMilestonesAreSortedAndDeduped() {
        assertEquals(List.of(10, 20, 30), loadMilestones(50, List.of(30, 10, 20, 10, 30)));
    }

    @Test
    void valuesAboveBarMaxAreDropped() {
        assertEquals(List.of(10, 20), loadMilestones(20, List.of(10, 20, 30, 40)));
    }

    @Test
    void missingMilestonesFallsBackToTenAndTwenty() {
        assertEquals(List.of(10, 20), loadMilestones(50, List.of()));
    }

    @Test
    void barMaxFifteenClipsTheDefaultToTenOnly() {
        assertEquals(List.of(10), loadMilestones(15, List.of()));
    }

    // A bar too small for either hardcoded default still gets one reward, at
    // bar.max itself
    @Test
    void aBarSmallerThanBothDefaultsFallsBackToBarMaxItself() {
        assertEquals(List.of(5), loadMilestones(5, List.of()));
    }

    @Test
    void nullAndZeroAndNegativeMilestoneValuesAreDropped() {
        List<Integer> raw = new java.util.ArrayList<>();
        raw.add(null);
        raw.add(0);
        raw.add(-5);
        raw.add(10);

        assertEquals(List.of(10), loadMilestones(50, raw));
    }
}
