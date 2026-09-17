package tfmc.justin.activity.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import sun.reflect.ReflectionFactory;

import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// The 'station:' activity key, loaded by the private loadActivities() /
// loadStation() the same way 'craft:' and 'profession:' are, and read back
// through the public stationActivity(stationId, recipeId).
//
// load() itself needs a live Bukkit server (reloadConfig(), Messages,
// Bukkit.getPluginManager()), which is why every other test in this class
// drives the private parsing methods directly instead. loadStation() is not
// static like registerCraft()/professionActivity() though - it logs through
// plugin.getLogger(), so a JavaPlugin is still needed. JavaPlugin's own
// constructor throws unless its classloader is a live PluginClassLoader, so
// one is built the same way DishCookedListenerTest builds its event: bypass
// the constructor via ReflectionFactory, then hand it a real
// java.util.logging.Logger so the warnings do not NPE.
//
// YamlConfiguration.loadConfiguration(Reader) is pure SnakeYAML and needs no
// server either - see DefaultResourcesTest.
// ====================================
class ActivityConfigurationStationTest {

    // JavaPlugin itself is abstract, and ReflectionFactory's bypass allocator
    // still rejects an abstract class outright (InstantiationError) even
    // without running a constructor - so a trivial concrete subclass, itself
    // never constructed normally either, is what actually gets allocated.
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
            loggerField.set(plugin, Logger.getLogger("ActivityConfigurationStationTest"));

            return plugin;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static ConfigurationSection section(String yaml, String key) {
        YamlConfiguration full = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        return full.getConfigurationSection(key);
    }

    private static void loadActivities(ActivityConfiguration config, ConfigurationSection section) {
        try {
            Method method = ActivityConfiguration.class.getDeclaredMethod("loadActivities", ConfigurationSection.class);
            method.setAccessible(true);
            method.invoke(config, section);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // Builds a config from an 'activities:' YAML block. Every entry needs
    // 'points' to survive loadActivities()'s earlier checks and actually
    // reach loadStation().
    private static ActivityConfiguration configFor(String activitiesYaml) {
        ActivityConfiguration config = new ActivityConfiguration(stubPlugin());
        loadActivities(config, section("activities:\n" + activitiesYaml, "activities"));
        return config;
    }

    private static String entry(String id, String station) {
        return "  " + id + ":\n    points: 1\n    station: \"" + station + "\"\n";
    }

    @Test
    void wholeStationMatchesAnyRecipe() {
        ActivityConfiguration config = configFor(entry("smelt", "forge"));

        assertEquals(Optional.of("smelt"), config.stationActivity("forge", "anything"));
        assertEquals(Optional.of("smelt"), config.stationActivity("forge", null));
    }

    @Test
    void specificRecipeOnlyMatchesThatRecipe() {
        ActivityConfiguration config = configFor(entry("flint", "forge/flint"));

        assertEquals(Optional.of("flint"), config.stationActivity("forge", "flint"));
        assertEquals(Optional.empty(), config.stationActivity("forge", "coal"));
    }

    @Test
    void specificRecipeBeatsWholeStation() {
        ActivityConfiguration config = configFor(
            entry("smelt_anything", "forge") + entry("smelt_flint", "forge/flint"));

        assertEquals(Optional.of("smelt_flint"), config.stationActivity("forge", "flint"));
        assertEquals(Optional.of("smelt_anything"), config.stationActivity("forge", "coal"));
    }

    @Test
    void lookupIsCaseAndWhitespaceInsensitive() {
        ActivityConfiguration config = configFor(entry("flint", "Forge-Station/Flint"));

        assertEquals(Optional.of("flint"), config.stationActivity(" forge-station ", " FLINT "));
        assertEquals(Optional.of("flint"), config.stationActivity("FORGE-STATION", "flint"));
    }

    @Test
    void unknownStationIsEmpty() {
        ActivityConfiguration config = configFor(entry("smelt", "forge"));

        assertEquals(Optional.empty(), config.stationActivity("anvil", "anything"));
        assertEquals(Optional.empty(), config.stationActivity(null, "anything"));
        assertEquals(Optional.empty(), config.stationActivity("", "anything"));
    }

    // Documented behaviour: two activities claiming one station key, last one
    // loaded wins - same rule as a duplicate profession
    @Test
    void duplicateStationClaimKeepsTheLastActivity() {
        ActivityConfiguration config = configFor(
            entry("first", "forge") + entry("second", "forge"));

        assertEquals(Optional.of("second"), config.stationActivity("forge", "anything"));
    }

    // station: alongside craft: (or profession:) is a conflict: only the
    // earlier-declared feed survives, and station is ignored entirely.
    // craft: uses an m.<type>.<id> item path rather than a bare Material name
    // so registerCraft never has to ask a live server's item registry whether
    // the material is craftable - see registerCraft's own ItemPath branch.
    @Test
    void stationTogetherWithCraftIsIgnored() {
        ActivityConfiguration config = configFor(
            "  smelt:\n    points: 1\n    craft: \"m.material.steel\"\n    station: \"forge\"\n");

        assertEquals(Optional.empty(), config.stationActivity("forge", "anything"));
    }

    @Test
    void stationTogetherWithProfessionIsIgnored() {
        ActivityConfiguration config = configFor(
            "  mine:\n    points: 1\n    profession: miner\n    station: \"forge\"\n");

        assertEquals(Optional.empty(), config.stationActivity("forge", "anything"));
    }

    // Malformed values (leading slash, trailing slash, blank) must not throw
    // and must register nothing - so no station/recipe pair can ever match
    // the activity they were meant for
    @Test
    void malformedValuesRegisterNothingAndDoNotThrow() {
        ActivityConfiguration config = assertDoesNotThrowConfig(
            entry("a", "/flint") + entry("b", "ingot-station/") + entry("d", ""));

        assertEquals(Optional.empty(), config.stationActivity("", "flint"));
        assertEquals(Optional.empty(), config.stationActivity("flint", ""));
        assertEquals(Optional.empty(), config.stationActivity("ingot-station", "anything"));
        assertEquals(Optional.empty(), config.stationActivity("ingot-station", ""));
    }

    // Multiple slashes in a station: value are rejected as malformed and
    // register nothing - so no split can ever match the activity they were
    // meant for.
    @Test
    void multipleSlashesRegisterNothingAndDoNotThrow() {
        ActivityConfiguration config = assertDoesNotThrowConfig(
            entry("multi", "a/b/c") + entry("double", "ingot-station//flint"));

        assertEquals(Optional.empty(), config.stationActivity("a", "b/c"));
        assertEquals(Optional.empty(), config.stationActivity("a/b", "c"));
        assertEquals(Optional.empty(), config.stationActivity("ingot-station", "flint"));
        assertEquals(Optional.empty(), config.stationActivity("ingot-station", ""));
    }

    private static ActivityConfiguration assertDoesNotThrowConfig(String activitiesYaml) {
        return org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> configFor(activitiesYaml));
    }

    // A reload must not leave a station claimed by a config that no longer
    // asks for it
    @Test
    void reloadReplacesTheStationMapEntirely() {
        ActivityConfiguration config = configFor(entry("smelt", "forge"));
        assertTrue(config.stationActivity("forge", "anything").isPresent());

        loadActivities(config, section("activities:\n" + entry("other", "anvil"), "activities"));

        assertEquals(Optional.empty(), config.stationActivity("forge", "anything"));
        assertEquals(Optional.of("other"), config.stationActivity("anvil", "anything"));
    }

    @Test
    void emptyActivitiesSectionMeansNoStationsAtAll() {
        ActivityConfiguration config = new ActivityConfiguration(stubPlugin());
        loadActivities(config, null);

        assertFalse(config.stationActivity("forge", "anything").isPresent());
    }
}
