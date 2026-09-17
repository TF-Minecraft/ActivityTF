package tfmc.justin.activity.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.ActivityDef;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// The optional 'description:' activity key, which takes either a single string
// or a list of lines. load() needs a live Bukkit server, so loadActivities is
// driven directly through reflection - the same trick, and the same hand-built
// JavaPlugin, as ActivityConfigurationClickCommandsTest. The plugin's logger is
// captured so the warning can be asserted on.
// ====================================
class ActivityConfigurationDescriptionTest {

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
    void aStringDescriptionLoadsAsASingleLine() {
        ActivityConfiguration config = configFor("  cook_dish:\n    points: 1\n"
            + "    description: \"Finish 5 dishes at a cooking station\"\n");

        assertEquals(List.of("Finish 5 dishes at a cooking station"), only(config).description());
    }

    @Test
    void aListDescriptionLoadsInConfigOrder() {
        ActivityConfiguration config = configFor("  vote:\n    points: 1\n    description:\n"
            + "      - \"Vote for the server on any listed site\"\n      - \"&7Links: &f/vote\"\n");

        assertEquals(List.of("Vote for the server on any listed site", "&7Links: &f/vote"),
            only(config).description());
    }

    @Test
    void anActivityWithoutTheKeyCarriesAnEmptyDescription() {
        ActivityConfiguration config = configFor("  vote:\n    points: 1\n");

        assertEquals(List.of(), only(config).description());
    }

    // Neither a string nor a list: named rather than silently dropped, the
    // same bug the click-commands and reward-item parsers refuse to have
    @Test
    void aValueThatIsNeitherStringNorListWarnsAndYieldsEmpty() {
        ActivityConfiguration config = configFor("  vote:\n    points: 1\n    description:\n"
            + "      line: \"nested\"\n");

        assertEquals(List.of(), only(config).description());
        assertTrue(warned("activities.vote.description is neither a string nor a list"), logged.toString());
    }

    @Test
    void blankDescriptionEntriesAreDropped() {
        ActivityConfiguration config = configFor("  vote:\n    points: 1\n    description:\n"
            + "      - \"\"\n      - \"  \"\n      - \"kept\"\n");

        assertEquals(List.of("kept"), only(config).description());
    }

    @Test
    void aBlankStringDescriptionIsDropped() {
        ActivityConfiguration config = configFor("  vote:\n    points: 1\n    description: \"   \"\n");

        assertEquals(List.of(), only(config).description());
    }
}
