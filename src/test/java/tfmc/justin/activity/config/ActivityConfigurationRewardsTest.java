package tfmc.justin.activity.config;

import org.bukkit.configuration.ConfigurationSection;
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
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            + "        - item: 'nx.custom.block'\n"
            + "        - item: 'm.material'\n"
            + "        - amount: 2\n"
            + "        - 'DIAMOND'\n"
            + "        - item: 'm.material.steel'\n");

        assertEquals(1, pool.size());
        assertEquals(List.of(new RewardEntry.Item("m.material.steel", 1)), pool.get(0).items());

        // Each warning must name the element it came from, or an admin cannot
        // find the line to fix
        assertTrue(loggedContains("Unknown material 'NOT_A_MATERIAL' at rewards.pool[0].items[0]"));
        assertTrue(loggedContains("Unsupported item path 'nx.custom.block' at rewards.pool[0].items[1]"));
        assertTrue(loggedContains("Malformed item path 'm.material' at rewards.pool[0].items[2]"));
        assertTrue(loggedContains("rewards.pool[0].items[3] has no 'item:' path"));
        assertTrue(loggedContains("rewards.pool[0].items[4] is not an 'item:'/'amount:' block"));
    }

    // ====================================
    // ia.<namespace:id> is a reward item like any other: kept at load in the
    // form the admin wrote, normalized only when the payout asks ItemsAdder.
    // Nothing here can tell a live ItemsAdder id from a deleted one, and
    // ItemsAdder may not even be enabled while this runs - so both separators
    // survive the parser.
    // ====================================
    @Test
    void anItemsAdderPathIsKeptAsARewardItem() {
        List<RewardEntry> pool = loadRewardPool("rewards:\n  pool:\n    - weight: 1\n      items:\n"
            + "        - item: 'ia.tfmc:saucepan'\n"
            + "        - item: 'ia.tfmc.saucepan'\n"
            + "          amount: 2\n");

        assertEquals(List.of(
            new RewardEntry.Item("ia.tfmc:saucepan", 1),
            new RewardEntry.Item("ia.tfmc.saucepan", 2)), pool.get(0).items());
        assertFalse(loggedContains("Unsupported item path"));
        assertFalse(loggedContains("Malformed item path"));
    }

    // A path with no id behind it can never resolve, so it is dropped at load
    // rather than kept to hand over nothing at payout
    @Test
    void aMalformedItemsAdderPathIsDroppedAndNamed() {
        List<RewardEntry> pool = loadRewardPool("rewards:\n  pool:\n    - weight: 1\n      items:\n"
            + "        - item: 'ia.saucepan'\n"
            + "        - item: 'DIAMOND'\n");

        assertEquals(List.of(new RewardEntry.Item("DIAMOND", 1)), pool.get(0).items());
        assertTrue(loggedContains("Malformed item path 'ia.saucepan' at rewards.pool[0].items[0]"));
        assertTrue(loggedContains("expected ia.<namespace:id>"));
    }

    // ====================================
    // The one line the whole file gets when it asks for ItemsAdder items and
    // ItemsAdder is not there. load() itself needs a live server, so the rule
    // behind it is pinned directly.
    // ====================================
    @Test
    void theItemsAdderWarningIsOnlyGivenWhenItIsBothAskedForAndMissing() {
        assertNull(ActivityConfiguration.itemsAdderWarning(false, false));
        assertNull(ActivityConfiguration.itemsAdderWarning(false, true));
        assertNull(ActivityConfiguration.itemsAdderWarning(true, true));

        String warning = ActivityConfiguration.itemsAdderWarning(true, false);
        assertNotNull(warning);
        assertTrue(warning.contains("ItemsAdder is not enabled"), warning);
        // an admin must be told the payout consequence, not just the fact
        assertTrue(warning.contains("leaves its milestone unclaimed"), warning);
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

    // Fed a whole config rather than a value, so the key path itself is under
    // test: the shipped multiplier is 1 and so is the fallback, which means a
    // typo in the path is invisible everywhere else.
    private int rewardMultiplier(String yamlContent) {
        try {
            ActivityConfiguration config = new ActivityConfiguration(stubPlugin());
            Method method = ActivityConfiguration.class.getDeclaredMethod("rewardMultiplier",
                ConfigurationSection.class);
            method.setAccessible(true);
            return (int) method.invoke(config, yaml(yamlContent));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private int rewardMultiplier(int raw) {
        return rewardMultiplier("rewards:\n  multiplier: " + raw + "\n");
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

    // The wiring, not the clamp: read out of a whole config at the path the
    // shipped file writes, so a typo in either would be caught here
    @Test
    void theMultiplierIsReadFromItsConfiguredPath() {
        assertEquals(3, rewardMultiplier("rewards:\n  multiplier: 3\n  pool: []\n"));
        assertEquals("rewards.multiplier", ActivityConfiguration.REWARDS_MULTIPLIER_PATH);
    }

    @Test
    void anAbsentMultiplierReadsAsOneWithoutAWarning() {
        assertEquals(1, rewardMultiplier("rewards:\n  pool: []\n"));
        assertFalse(loggedContains("rewards.multiplier"));
    }

    // getInt would have truncated this to 2 in silence, paying double what was
    // written rather than what a broken key is meant to pay
    @Test
    void aFractionalMultiplierFallsBackToOneWithAWarning() {
        assertEquals(1, rewardMultiplier("rewards:\n  multiplier: 2.9\n"));
        assertTrue(loggedContains("rewards.multiplier '2.9' is not a whole number - using 1."));
    }

    // getInt reported this as "rewards.multiplier 0 is outside 1-64", naming a
    // value the admin never wrote
    @Test
    void aNonNumericMultiplierFallsBackToOneAndIsNamedAsWritten() {
        assertEquals(1, rewardMultiplier("rewards:\n  multiplier: 'three'\n"));
        assertTrue(loggedContains("rewards.multiplier is not a number ('three') - using 1."));
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

    // ====================================
    // rewards.drops
    // ====================================

    private static ActivityConfiguration withMilestones(List<Integer> milestones) {
        try {
            ActivityConfiguration config = new ActivityConfiguration(stubPlugin());
            Field field = ActivityConfiguration.class.getDeclaredField("milestones");
            field.setAccessible(true);
            field.set(config, milestones);
            return config;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<Integer, RewardEntry> loadDrops(List<Integer> milestones, String yamlContent) {
        return loadDrops(withMilestones(milestones), yamlContent);
    }

    private static Map<Integer, RewardEntry> loadDrops(ActivityConfiguration config, String yamlContent) {
        try {
            Method method = ActivityConfiguration.class.getDeclaredMethod("loadMilestoneDrops",
                ConfigurationSection.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Integer, RewardEntry> result = (Map<Integer, RewardEntry>) method.invoke(config, yaml(yamlContent));
            return result;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean flag(ActivityConfiguration config, String name) {
        try {
            Field field = ActivityConfiguration.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(config);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // Keyed by the milestone's points, not by N: drop_2 of [10, 20, 30] is 20.
    // The display is the bare name; claim() prefixes the paid amount.
    @Test
    void aFixedDropLoadsAgainstItsMilestoneWithPathAmountAndName() {
        Map<Integer, RewardEntry> drops = loadDrops(List.of(10, 20, 30),
            "rewards:\n  drops:\n    drop_1: pool\n    drop_2: m.material.steel 3\n    drop_3: DIAMOND\n");

        assertEquals(Map.of(20, new RewardEntry(1, "Steel", List.of(),
                List.of(new RewardEntry.Item("m.material.steel", 3))),
            30, new RewardEntry(1, "Diamond", List.of(),
                List.of(new RewardEntry.Item("DIAMOND", 1)))), drops);
        assertTrue(logged.isEmpty());
    }

    @Test
    void aMissingDropsSectionMeansEveryMilestoneDrawsFromThePool() {
        assertTrue(loadDrops(List.of(10, 20), "rewards:\n  multiplier: 1\n").isEmpty());
        assertTrue(logged.isEmpty());
    }

    @Test
    void poolIsCaseInsensitive() {
        assertTrue(loadDrops(List.of(10, 20), "rewards:\n  drops:\n    drop_1: POOL\n    drop_2: Pool\n")
            .isEmpty());
        assertTrue(logged.isEmpty());
    }

    @Test
    void aKeyThatIsNotDropNIsIgnoredWithAWarning() {
        Map<Integer, RewardEntry> drops = loadDrops(List.of(10, 20),
            "rewards:\n  drops:\n    drop_0: DIAMOND\n    reward_1: DIAMOND\n    drop_x: DIAMOND\n");

        assertTrue(drops.isEmpty());
        assertTrue(loggedContains("rewards.drops.drop_0 is not a drop_<N> key"));
        assertTrue(loggedContains("rewards.drops.reward_1 is not a drop_<N> key"));
        assertTrue(loggedContains("rewards.drops.drop_x is not a drop_<N> key"));
    }

    @Test
    void aDropPastTheLastMilestoneIsIgnoredWithAWarning() {
        Map<Integer, RewardEntry> drops = loadDrops(List.of(10, 20),
            "rewards:\n  drops:\n    drop_3: DIAMOND\n    drop_99999999999: DIAMOND\n");

        assertTrue(drops.isEmpty());
        assertTrue(loggedContains("rewards.drops.drop_3 is past the last of the 2 bar.milestones"));
        assertTrue(loggedContains("rewards.drops.drop_99999999999 is past the last"));
    }

    @Test
    void aMalformedAmountFallsBackToThePoolWithAWarning() {
        Map<Integer, RewardEntry> drops = loadDrops(List.of(10, 20, 30, 40, 50),
            "rewards:\n  drops:\n    drop_1: DIAMOND 0\n    drop_2: DIAMOND 65\n    drop_3: DIAMOND three\n"
                + "    drop_4: DIAMOND 2.5\n    drop_5: DIAMOND 2 3\n");

        assertTrue(drops.isEmpty());
        assertTrue(loggedContains("rewards.drops.drop_1 'DIAMOND 0' is not"));
        assertTrue(loggedContains("rewards.drops.drop_2 'DIAMOND 65' is not"));
        assertTrue(loggedContains("rewards.drops.drop_3 'DIAMOND three' is not"));
        assertTrue(loggedContains("rewards.drops.drop_4 'DIAMOND 2.5' is not"));
        assertTrue(loggedContains("milestone 50 draws from the pool"));
    }

    // Unknown to Bukkit is a typo and falls back, and the log says so; a well
    // formed m. or ia. path cannot be checked without its plugin, so it is
    // kept for payout to decide
    @Test
    void anUnknownMaterialFallsBackButPluginPathsAreKeptUnchecked() {
        Map<Integer, RewardEntry> drops = loadDrops(List.of(10, 20, 30),
            "rewards:\n  drops:\n    drop_1: NOT_A_MATERIAL\n    drop_2: ia.tfmc:ruby_gem 2\n"
                + "    drop_3: m.material.nothing_here\n");

        assertEquals(List.of(20, 30), drops.keySet().stream().sorted().toList());
        assertEquals("Ruby Gem", drops.get(20).display());
        assertTrue(loggedContains("Unknown material 'NOT_A_MATERIAL' at rewards.drops.drop_1"));
        assertTrue(loggedContains("rewards.drops.drop_1 has no usable item path - milestone 10 draws from the pool."));
    }

    // A drops-only m. or ia. path must count as "asked for", or load() says
    // nothing when TLibs or ItemsAdder is missing
    @Test
    void aDropsOnlyPluginPathTriggersTheMissingPluginWarnings() {
        ActivityConfiguration config = withMilestones(List.of(10, 20));
        loadDrops(config, "rewards:\n  drops:\n    drop_1: m.material.steel\n    drop_2: ia.tfmc:ruby_gem\n");

        assertTrue(flag(config, "pluginPathConfigured"));
        assertTrue(flag(config, "itemsAdderPathConfigured"));
        assertNotNull(ActivityConfiguration.itemPathWarning(flag(config, "pluginPathConfigured"), false, "TLibs"));
        assertNotNull(ActivityConfiguration.itemsAdderWarning(flag(config, "itemsAdderPathConfigured"), false));
    }

    @Test
    void theItemPathWarningIsOnlyGivenWhenItIsBothAskedForAndMissing() {
        assertNull(ActivityConfiguration.itemPathWarning(false, false, "TLibs"));
        assertNull(ActivityConfiguration.itemPathWarning(true, true, ""));
        assertTrue(ActivityConfiguration.itemPathWarning(true, false, "TLibs, MMOItems")
            .contains("but TLibs, MMOItems are not enabled"));
    }

    // ====================================
    // The load-time "nothing can ever be claimed" warning
    // ====================================

    private static final RewardEntry FIXED = new RewardEntry(1, "Diamond", List.of(),
        List.of(new RewardEntry.Item("DIAMOND", 1)));

    @Test
    void anEmptyPoolWithEveryMilestoneFixedIsNoProblem() {
        assertFalse(ActivityConfiguration.poolNeededButEmpty(List.of(), Map.of(10, FIXED, 20, FIXED),
            List.of(10, 20)));
    }

    @Test
    void anEmptyPoolWithOnePoolMilestoneIsWarnedAbout() {
        assertTrue(ActivityConfiguration.poolNeededButEmpty(List.of(), Map.of(10, FIXED), List.of(10, 20)));
    }

    @Test
    void anEmptyPoolWithNoDropsIsWarnedAboutAsBefore() {
        assertTrue(ActivityConfiguration.poolNeededButEmpty(List.of(), Map.of(), List.of(10, 20)));
        assertFalse(ActivityConfiguration.poolNeededButEmpty(List.of(FIXED), Map.of(), List.of(10, 20)));
    }
}

