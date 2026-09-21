package tfmc.justin.activity.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.PlayerData;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityConfigurationGuaranteedTest {

    private final List<String> logged = new ArrayList<>();

    private JavaPlugin stubPlugin() {
        return TestPlugins.capturing(logged);
    }

    private ActivityConfiguration configFor(String activitiesYaml) {
        ActivityConfiguration config = new ActivityConfiguration(stubPlugin());
        YamlConfiguration full =
            YamlConfiguration.loadConfiguration(new StringReader("activities:\n" + activitiesYaml));
        ConfigurationSection section = full.getConfigurationSection("activities");
        try {
            Method method = ActivityConfiguration.class.getDeclaredMethod("loadActivities", ConfigurationSection.class);
            method.setAccessible(true);
            method.invoke(config, section);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return config;
    }

    private static String entry(String id, String guaranteed) {
        return "  " + id + ":\n    points: 1\n"
            + (guaranteed == null ? "" : "    daily-guaranteed: " + guaranteed + "\n");
    }

    @Test
    void trueMarksTheActivityGuaranteed() {
        ActivityConfiguration config = configFor(entry("vote", "true"));

        assertEquals(List.of("vote"), config.guaranteed());
    }

    @Test
    void falseAndAbsentBothMeanNotGuaranteed() {
        ActivityConfiguration config = configFor(entry("vote", "false") + entry("geiger", null));

        assertEquals(List.of(), config.guaranteed());
    }

    @Test
    void guaranteedIdsKeepConfigOrder() {
        ActivityConfiguration config =
            configFor(entry("vote", "true") + entry("geiger", null) + entry("battle", "true"));

        assertEquals(List.of("vote", "battle"), config.guaranteed());
    }

    @Test
    void aGarbageValueIsReportedAndFallsBackToFalse() {
        ActivityConfiguration config = configFor(entry("vote", "\"yes please\""));

        assertEquals(List.of(), config.guaranteed());
        assertTrue(logged.stream().anyMatch(line -> line.contains("activities.vote.daily-guaranteed")
            && line.contains("not true or false")), logged.toString());
    }

    @Test
    void anActivityDroppedForZeroPointsIsNotGuaranteed() {
        ActivityConfiguration config = configFor("  vote:\n    points: 0\n    daily-guaranteed: true\n");

        assertEquals(List.of(), config.guaranteed());
    }

    @Test
    void anEmptyActivitiesSectionHasNoGuaranteedIds() {
        ActivityConfiguration config = configFor(entry("vote", "true"));
        assertEquals(List.of("vote"), config.guaranteed());

        try {
            Method method = ActivityConfiguration.class.getDeclaredMethod("loadActivities", ConfigurationSection.class);
            method.setAccessible(true);
            method.invoke(config, (ConfigurationSection) null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }

        assertEquals(List.of(), config.guaranteed());
    }

    @Test
    void noWarningWhenTheGuaranteedIdsFitTheDay() {
        StringBuilder yaml = new StringBuilder();
        for (int i = 0; i < PlayerData.TASKS_PER_DAY; i++) {
            yaml.append(entry("a" + i, "true"));
        }
        ActivityConfiguration config = configFor(yaml.toString());

        assertEquals(PlayerData.TASKS_PER_DAY, config.guaranteed().size());
        assertFalse(logged.stream().anyMatch(line -> line.contains("daily-guaranteed but only")),
            logged.toString());
    }

    @Test
    void moreGuaranteedThanTaskSlotsIsReported() {
        StringBuilder yaml = new StringBuilder();
        for (int i = 0; i < PlayerData.TASKS_PER_DAY + 2; i++) {
            yaml.append(entry("a" + i, "true"));
        }
        ActivityConfiguration config = configFor(yaml.toString());

        assertEquals(PlayerData.TASKS_PER_DAY + 2, config.guaranteed().size());
        assertTrue(logged.stream().anyMatch(line -> line.contains("daily-guaranteed but only")),
            logged.toString());
    }
}
