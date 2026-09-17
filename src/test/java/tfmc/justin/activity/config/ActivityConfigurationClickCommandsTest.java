package tfmc.justin.activity.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.ActivityDef;

import java.io.File;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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

    private JavaPlugin stubPlugin() {
        return TestPlugins.capturing(logged);
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

    // The one numeric parser all three click-command knobs go through
    private int clamped(String yaml, String path, int fallback, int min, int max) {
        ActivityConfiguration config = new ActivityConfiguration(stubPlugin());
        try {
            Method method = ActivityConfiguration.class.getDeclaredMethod("clamped",
                ConfigurationSection.class, String.class, int.class, int.class, int.class);
            method.setAccessible(true);
            return (int) method.invoke(config, YamlConfiguration.loadConfiguration(new StringReader(yaml)),
                path, fallback, min, max);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private int cooldown(String yaml) {
        return clamped(yaml, ActivityConfiguration.CLICK_COMMAND_COOLDOWN_PATH,
            ActivityConfiguration.CLICK_COMMAND_COOLDOWN_DEFAULT, 50, 60_000);
    }

    private int perSecond(String yaml) {
        return clamped(yaml, ActivityConfiguration.CLICK_COMMANDS_PER_SECOND_PATH,
            ActivityConfiguration.CLICK_COMMANDS_PER_SECOND_DEFAULT, 1, 200);
    }

    private int perClick(String yaml) {
        return clamped(yaml, ActivityConfiguration.CLICK_COMMANDS_PER_CLICK_PATH,
            ActivityConfiguration.CLICK_COMMANDS_PER_CLICK_DEFAULT, 1, 50);
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
        assertEquals(20, shipped.getInt(ActivityConfiguration.CLICK_COMMANDS_PER_SECOND_PATH));
        assertEquals(5, shipped.getInt(ActivityConfiguration.CLICK_COMMANDS_PER_CLICK_PATH));
    }

    // ====================================
    // The server-wide ceiling and the per-click cap, which the per-player
    // cooldown cannot give: they are parsed by the same method, so what is
    // pinned here is that each one is read from its own path with its own
    // default and its own bounds.
    // ====================================
    @Test
    void anAbsentGlobalLimitIsTwentyASecond() {
        assertEquals(20, perSecond("bar:\n  max: 50\n"));
    }

    @Test
    void aGlobalLimitInsideTheBoundsIsKept() {
        assertEquals(60, perSecond(ActivityConfiguration.CLICK_COMMANDS_PER_SECOND_PATH + ": 60\n"));
    }

    // No "off" value here either: 0 a second would stop the feature dead
    // rather than bound it, which is what removing 'click-commands' is for
    @Test
    void aGlobalLimitOutsideTheBoundsIsClamped() {
        assertEquals(1, perSecond(ActivityConfiguration.CLICK_COMMANDS_PER_SECOND_PATH + ": 0\n"));
        assertEquals(200, perSecond(ActivityConfiguration.CLICK_COMMANDS_PER_SECOND_PATH + ": 5000\n"));
        assertTrue(warned("is outside 1-200"), logged.toString());
    }

    @Test
    void aNonNumericGlobalLimitWarnsAndFallsBackToTwenty() {
        assertEquals(20, perSecond(ActivityConfiguration.CLICK_COMMANDS_PER_SECOND_PATH + ": 'lots'\n"));
        assertTrue(warned("is not a number"), logged.toString());
    }

    @Test
    void anAbsentPerClickCapIsFive() {
        assertEquals(5, perClick("bar:\n  max: 50\n"));
    }

    @Test
    void aPerClickCapOutsideTheBoundsIsClamped() {
        assertEquals(1, perClick(ActivityConfiguration.CLICK_COMMANDS_PER_CLICK_PATH + ": 0\n"));
        assertEquals(50, perClick(ActivityConfiguration.CLICK_COMMANDS_PER_CLICK_PATH + ": 99\n"));
        assertTrue(warned("is outside 1-50"), logged.toString());
    }
}
