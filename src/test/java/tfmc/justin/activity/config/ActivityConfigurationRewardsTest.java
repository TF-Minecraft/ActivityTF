package tfmc.justin.activity.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.reflect.ReflectionFactory;
import tfmc.justin.activity.models.RewardEntry;

import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // What the parser logged while this test ran: the warnings are half of
    // what these keys do, and a warning that names the wrong index is worth no
    // more than none
    private final List<String> logged = new ArrayList<>();

    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void captureLog() {
        Logger logger = Logger.getLogger("ActivityConfigurationRewardsTest");
        logger.setLevel(Level.ALL);
        logger.addHandler(capture);
    }

    @AfterEach
    void releaseLog() {
        Logger.getLogger("ActivityConfigurationRewardsTest").removeHandler(capture);
    }

    private boolean loggedContains(String fragment) {
        return logged.stream().anyMatch(message -> message.contains(fragment));
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
    // rewards.pool[N].items - an entry may hand items over instead of, or as
    // well as, running commands
    // ====================================

    @Test
    void anItemOnlyEntryLoadsWithItsPathAndAmount() {
        List<RewardEntry> pool = loadRewardPool("rewards:\n  pool:\n    - weight: 2\n      display: 'Steel'\n"
            + "      items:\n        - item: 'm.material.steel'\n          amount: 3\n");

        assertEquals(1, pool.size());
        assertEquals(List.of(), pool.get(0).commands());
        assertEquals(List.of(new RewardEntry.Item("m.material.steel", 3)), pool.get(0).items());
    }

    @Test
    void aCommandOnlyEntryStillLoadsWithNoItems() {
        List<RewardEntry> pool = loadRewardPool(
            "rewards:\n  pool:\n    - weight: 1\n      commands: ['give %player% diamond 3']\n");

        assertEquals(1, pool.size());
        assertEquals(List.of("give %player% diamond 3"), pool.get(0).commands());
        assertEquals(List.of(), pool.get(0).items());
    }

    @Test
    void anEntryWithBothCommandsAndItemsKeepsBoth() {
        List<RewardEntry> pool = loadRewardPool("rewards:\n  pool:\n    - weight: 1\n"
            + "      commands: ['say hi']\n      items:\n        - item: 'DIAMOND'\n");

        assertEquals(1, pool.size());
        assertEquals(List.of("say hi"), pool.get(0).commands());
        assertEquals(List.of(new RewardEntry.Item("DIAMOND", 1)), pool.get(0).items());
    }

    @Test
    void anEntryWithNeitherCommandsNorItemsIsSkipped() {
        assertTrue(loadRewardPool("rewards:\n  pool:\n    - weight: 1\n      display: 'Nothing'\n").isEmpty());
    }

    @Test
    void aMissingAmountDefaultsToOneAndTheBoundsAreClamped() {
        List<RewardEntry> pool = loadRewardPool("rewards:\n  pool:\n    - weight: 1\n      items:\n"
            + "        - item: 'v.diamond'\n"
            + "        - item: 'DIAMOND'\n          amount: 999\n"
            + "        - item: 'DIAMOND'\n          amount: -4\n"
            + "        - item: 'DIAMOND'\n          amount: 'lots'\n");

        assertEquals(List.of(
            new RewardEntry.Item("v.diamond", 1),
            new RewardEntry.Item("DIAMOND", 64),
            new RewardEntry.Item("DIAMOND", 1),
            new RewardEntry.Item("DIAMOND", 1)), pool.get(0).items());
    }

    // ====================================
    // An unknown material, another plugin's path syntax and a malformed m.
    // path can never resolve, so they are dropped at load rather than kept to
    // fail at payout. A well formed m. path is kept: nothing here can tell a
    // live MMOItems id from a deleted one, and TLibs may not even be enabled
    // while this runs.
    // ====================================
    @Test
    void unusableItemPathsAreDroppedAndTheRestOfTheEntrySurvives() {
        List<RewardEntry> pool = loadRewardPool("rewards:\n  pool:\n    - weight: 1\n      items:\n"
            + "        - item: 'NOT_A_MATERIAL'\n"
            + "        - item: 'ia.custom.block'\n"
            + "        - item: 'm.material'\n"
            + "        - amount: 2\n"
            + "        - 'DIAMOND'\n"
            + "        - item: 'm.material.steel'\n");

        assertEquals(1, pool.size());
        assertEquals(List.of(new RewardEntry.Item("m.material.steel", 1)), pool.get(0).items());

        // Each warning must name the element it came from, or an admin cannot
        // find the line to fix
        assertTrue(loggedContains("Unknown material 'NOT_A_MATERIAL' at rewards.pool[0].items[0]"));
        assertTrue(loggedContains("Unsupported item path 'ia.custom.block' at rewards.pool[0].items[1]"));
        assertTrue(loggedContains("Malformed item path 'm.material' at rewards.pool[0].items[2]"));
        assertTrue(loggedContains("rewards.pool[0].items[3] has no 'item:' path"));
        assertTrue(loggedContains("rewards.pool[0].items[4] is not an 'item:'/'amount:' block"));
    }

    // ====================================
    // 'items:' written as anything but a list - 'items: DIAMOND', or the
    // single-item map that is the natural typo - used to load clean and pay
    // less than the admin wrote
    // ====================================
    @Test
    void anItemsKeyThatIsNotAListIsNamedRatherThanSwallowed() {
        List<RewardEntry> pool = loadRewardPool("rewards:\n  pool:\n    - weight: 1\n"
            + "      commands: ['say hi']\n      items: 'DIAMOND'\n");

        assertEquals(List.of(), pool.get(0).items());
        assertTrue(loggedContains("rewards.pool[0].items is not a list"));
    }

    @Test
    void anItemsKeyWrittenAsASingleMapIsNamedRatherThanSwallowed() {
        List<RewardEntry> pool = loadRewardPool("rewards:\n  pool:\n    - weight: 1\n"
            + "      commands: ['say hi']\n      items:\n        item: 'DIAMOND'\n");

        assertEquals(List.of(), pool.get(0).items());
        assertTrue(loggedContains("rewards.pool[0].items is not a list"));
    }

    @Test
    void anEntryWithNoItemsKeyAtAllSaysNothing() {
        loadRewardPool("rewards:\n  pool:\n    - weight: 1\n      commands: ['say hi']\n");

        assertFalse(loggedContains("items is not a list"));
    }

    // ====================================
    // An amount is range-tested as a long before it is narrowed: intValue() on
    // 4294967298 is 2, which would pass the test having asked for something
    // else entirely. A fractional amount is a different mistake and falls back
    // rather than rounding in silence.
    // ====================================
    @Test
    void anAmountTooLargeForAnIntIsClampedRatherThanNarrowedIntoRange() {
        List<RewardEntry> pool = loadRewardPool("rewards:\n  pool:\n    - weight: 1\n      items:\n"
            + "        - item: 'DIAMOND'\n          amount: 4294967298\n");

        assertEquals(List.of(new RewardEntry.Item("DIAMOND", 64)), pool.get(0).items());
        assertTrue(loggedContains("amount 4294967298 is outside 1-64 - using 64."));
    }

    @Test
    void aFractionalAmountFallsBackToOneWithAWarning() {
        List<RewardEntry> pool = loadRewardPool("rewards:\n  pool:\n    - weight: 1\n      items:\n"
            + "        - item: 'DIAMOND'\n          amount: 3.9\n");

        assertEquals(List.of(new RewardEntry.Item("DIAMOND", 1)), pool.get(0).items());
        assertTrue(loggedContains("is not a whole number - using 1."));
    }

    @Test
    void anEntryWhoseOnlyItemPathsAreUnusableIsSkipped() {
        assertTrue(loadRewardPool("rewards:\n  pool:\n    - weight: 1\n      items:\n"
            + "        - item: 'NOT_A_MATERIAL'\n").isEmpty());
    }

    // ====================================
    // rewards.multiplier - what every 'items:' amount is multiplied by at
    // payout. Clamped the way every other numeric key here is; 0 or negative
    // reads as 1, never as "hand nothing over".
    // ====================================

    private int rewardMultiplier(int raw) {
        try {
            ActivityConfiguration config = new ActivityConfiguration(stubPlugin());
            Method method = ActivityConfiguration.class.getDeclaredMethod("rewardMultiplier", int.class);
            method.setAccessible(true);
            return (int) method.invoke(config, raw);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void theMultiplierIsKeptInsideItsBounds() {
        assertEquals(1, rewardMultiplier(1));
        assertEquals(3, rewardMultiplier(3));
        assertEquals(64, rewardMultiplier(64));
    }

    @Test
    void aZeroOrNegativeMultiplierReadsAsOne() {
        assertEquals(1, rewardMultiplier(0));
        assertEquals(1, rewardMultiplier(-5));
        assertTrue(loggedContains("rewards.multiplier -5 is outside 1-64 - using 1."));
    }

    @Test
    void aMultiplierAboveTheUpperBoundIsClamped() {
        assertEquals(64, rewardMultiplier(1000));
        assertTrue(loggedContains("rewards.multiplier 1000 is outside 1-64 - using 64."));
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
