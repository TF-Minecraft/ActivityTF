package tfmc.justin.activity.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import sun.reflect.ReflectionFactory;
import tfmc.justin.activity.models.ActivityDef;

import java.io.File;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// The 'click-commands:' activity key and the 'click-command-cooldown-millis'
// rate limit that guards it. load() needs a live Bukkit server, so the two
// parsing methods are driven directly through reflection - the same trick, and
// the same hand-built JavaPlugin, as ActivityConfigurationGuaranteedTest. The
// plugin's logger is captured so the warnings can be asserted on.
// ====================================
class ActivityConfigurationClickCommandsTest {

    private final List<String> logged = new ArrayList<>();

    private static final class TestPlugin extends JavaPlugin {
    }

    private JavaPlugin stubPlugin() {
        try {
            ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
            Constructor<Object> objectCtor = Object.class.getDeclaredConstructor();
            Constructor<?> bypass = rf.newConstructorForSerialization(TestPlugin.class, objectCtor);
            JavaPlugin plugin = (JavaPlugin) bypass.newInstance();

            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
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
            });

            Field loggerField = JavaPlugin.class.getDeclaredField("logger");
            loggerField.setAccessible(true);
            loggerField.set(plugin, logger);

            return plugin;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private ActivityConfiguration configFor(String activitiesYaml) {
        ActivityConfiguration config = new ActivityConfiguration(stubPlugin());
        YamlConfiguration full =
            YamlConfiguration.loadConfiguration(new StringReader("activities:\n" + activitiesYaml));
        try {
            Method method =
                ActivityConfiguration.class.getDeclaredMethod("loadActivities", ConfigurationSection.class);
            method.setAccessible(true);
            method.invoke(config, full.getConfigurationSection("activities"));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return config;
    }

    private static ActivityDef only(ActivityConfiguration config) {
        return config.activities().iterator().next();
    }

    private boolean warned(String fragment) {
        return logged.stream().anyMatch(line -> line.contains(fragment));
    }

    @Test
    void anActivityCarriesItsClickCommandsInConfigOrder() {
        ActivityConfiguration config = configFor("  vote:\n    points: 1\n    click-commands:\n"
            + "      - \"sudo %player% votelist\"\n      - \"say %uuid%\"\n");

        assertEquals(List.of("sudo %player% votelist", "say %uuid%"), only(config).clickCommands());
    }

    @Test
    void anActivityWithoutTheKeyCarriesAnEmptyList() {
        ActivityConfiguration config = configFor("  vote:\n    points: 1\n");

        assertEquals(List.of(), only(config).clickCommands());
    }

    // A single 'click-commands: sudo %player% votelist' is the natural typo,
    // and it must not be swallowed the way a malformed list once was here
    @Test
    void aNonListValueWarnsAndYieldsAnEmptyList() {
        ActivityConfiguration config = configFor("  vote:\n    points: 1\n"
            + "    click-commands: \"sudo %player% votelist\"\n");

        assertEquals(List.of(), only(config).clickCommands());
        assertTrue(warned("activities.vote.click-commands is not a list"), logged.toString());
    }

    @Test
    void blankEntriesAreDropped() {
        ActivityConfiguration config = configFor("  vote:\n    points: 1\n    click-commands:\n"
            + "      - \"\"\n      - \"say hi\"\n");

        assertEquals(List.of("say hi"), only(config).clickCommands());
    }

    private int cooldown(String yaml) {
        ActivityConfiguration config = new ActivityConfiguration(stubPlugin());
        try {
            Method method = ActivityConfiguration.class.getDeclaredMethod("clickCommandCooldown",
                ConfigurationSection.class);
            method.setAccessible(true);
            return (int) method.invoke(config, YamlConfiguration.loadConfiguration(new StringReader(yaml)));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void anAbsentCooldownIsOneSecond() {
        assertEquals(1000, cooldown("bar:\n  max: 50\n"));
    }

    @Test
    void aCooldownInsideTheBoundsIsKept() {
        assertEquals(2500, cooldown(ActivityConfiguration.CLICK_COMMAND_COOLDOWN_PATH + ": 2500\n"));
    }

    // There is no "off" value: 0 would let a held mouse button dispatch
    // console commands at click rate
    @Test
    void aCooldownBelowTheFloorIsClampedUp() {
        assertEquals(50, cooldown(ActivityConfiguration.CLICK_COMMAND_COOLDOWN_PATH + ": 0\n"));
        assertEquals(50, cooldown(ActivityConfiguration.CLICK_COMMAND_COOLDOWN_PATH + ": -5000\n"));
        assertTrue(warned("is outside 50-60000"), logged.toString());
    }

    @Test
    void aCooldownAboveTheCeilingIsClampedDown() {
        assertEquals(60_000, cooldown(ActivityConfiguration.CLICK_COMMAND_COOLDOWN_PATH + ": 900000\n"));
    }

    @Test
    void aNonNumericCooldownWarnsAndFallsBackToOneSecond() {
        assertEquals(1000, cooldown(ActivityConfiguration.CLICK_COMMAND_COOLDOWN_PATH + ": 'soon'\n"));
        assertTrue(warned("is not a number"), logged.toString());
    }

    // The shipped file, against the exact paths the parser reads - a typo in
    // either would otherwise go unnoticed, since the shipped cooldown is also
    // the fallback
    @Test
    void theShippedConfigWiresVotingUpAndSetsTheCooldown() {
        YamlConfiguration shipped =
            YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));

        assertEquals(List.of("sudo %player% votelist"),
            shipped.getStringList("activities.vote." + ActivityConfiguration.CLICK_COMMANDS_KEY));
        assertEquals(1000, shipped.getInt(ActivityConfiguration.CLICK_COMMAND_COOLDOWN_PATH));
    }
}
