package net.tfminecraft.activitytf.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import net.tfminecraft.activitytf.models.PlayerData;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityConfigurationRequiresTest {

    private final List<String> logged = new ArrayList<>();

    private ActivityConfiguration configFor(String activitiesYaml) {
        ActivityConfiguration config = new ActivityConfiguration(TestPlugins.capturing(logged));
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

    @Test
    void requiresTakesOnePermissionOrAList() {
        ActivityConfiguration config = configFor("""
              one:
                points: 1
                requires: " professions.physician "
              many:
                points: 1
                requires: [professions.fisher, "", " professions.breeder_1 "]
              blank:
                points: 1
                requires: "  "
              none:
                points: 1
            """);

        assertEquals(List.of("professions.physician"), config.activity("one").requires());
        assertEquals(List.of("professions.fisher", "professions.breeder_1"), config.activity("many").requires());
        assertEquals(List.of(), config.activity("blank").requires());
        assertEquals(List.of(), config.activity("none").requires());
        assertTrue(logged.isEmpty(), String.valueOf(logged));
    }

    @Test
    void aRequiresThatIsNotTextIsReportedAndLetsAnyoneDoTheTask() {
        ActivityConfiguration config = configFor("""
              odd:
                points: 1
                requires:
                  nested: true
            """);

        assertEquals(List.of(), config.activity("odd").requires());
        assertEquals(1, logged.stream().filter(line -> line.contains("activities.odd.requires")).count());
    }

    @Test
    void onlyVotingMustBeRevealedUnlessTheConfigSaysOtherwise() {
        ActivityConfiguration config = configFor("""
              vote:
                points: 1
              cook:
                points: 1
              secret:
                points: 1
                requires-reveal: true
              open_vote:
                points: 1
            """);

        assertTrue(config.activity("vote").requiresReveal());
        assertFalse(config.activity("cook").requiresReveal());
        assertTrue(config.activity("secret").requiresReveal());
        assertFalse(configFor("  vote:\n    points: 1\n    requires-reveal: false\n").activity("vote").requiresReveal());
    }

    @Test
    void professionSlotsAreClampedToADay() {
        YamlConfiguration yaml = new YamlConfiguration();
        assertEquals(0, ActivityConfiguration.parseProfessionSlots(yaml));
        yaml.set("draw.profession-slots", 3);
        assertEquals(3, ActivityConfiguration.parseProfessionSlots(yaml));
        yaml.set("draw.profession-slots", -2);
        assertEquals(0, ActivityConfiguration.parseProfessionSlots(yaml));
        yaml.set("draw.profession-slots", 99);
        assertEquals(PlayerData.TASKS_PER_DAY, ActivityConfiguration.parseProfessionSlots(yaml));
    }
}
