package tfmc.justin.activity.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// Six new tier-1 hooks (VFBuilders, RPCharacters, InteractibleFurniture,
// MMOCore skills, SimpleFactions, AdvancedCrafting) each map a foreign event
// to manager.recordAction(uuid, "<id>", amount). The listener classes
// themselves need a live server / foreign event instances and are not
// unit-tested here. These tests check the parts that are pure data and can
// be loaded headlessly: the config.yml entries the listeners rely on, the
// plugin.yml softdepend declarations, and that every literal activity id a
// listener passes to recordAction actually exists in config.yml (a typo
// there would silently drop the points at runtime).
// ====================================
class Tier1HooksTest {

    private static final List<String> NEW_ACTIVITY_IDS = List.of(
            "vehicle_build", "ic_chat", "furniture_place", "skill_cast",
            "battle_joined", "advcraft_item"
    );

    private static YamlConfiguration loadConfig() {
        File file = new File("src/main/resources/config.yml");
        assertTrue(file.exists(), "config.yml should exist");
        return YamlConfiguration.loadConfiguration(file);
    }

    @Test
    void everyNewActivityHasSaneNumericBounds() {
        YamlConfiguration config = loadConfig();
        ConfigurationSection activities = config.getConfigurationSection("activities");
        assertTrue(activities != null, "activities section should exist");

        for (String id : NEW_ACTIVITY_IDS) {
            ConfigurationSection section = activities.getConfigurationSection(id);
            assertTrue(section != null, "activities." + id + " should exist");

            assertTrue(section.getInt("every", -1) >= 1, "activities." + id + ".every should be >= 1");
            assertTrue(section.getInt("points", -1) >= 1, "activities." + id + ".points should be >= 1");
            assertTrue(section.getInt("daily-cap", -1) >= 0, "activities." + id + ".daily-cap should be >= 0");

            String display = section.getString("display");
            assertFalse(display == null || display.isBlank(), "activities." + id + ".display should not be blank");

            String material = section.getString("material");
            assertFalse(material == null || material.isBlank(), "activities." + id + ".material should not be blank");
            assertTrue(org.bukkit.Material.matchMaterial(material) != null,
                    "activities." + id + ".material is not a Material: " + material);
        }
    }

    @Test
    void pluginYmlSoftdependsOnAllTier1Plugins() {
        File file = new File("src/main/resources/plugin.yml");
        assertTrue(file.exists(), "plugin.yml should exist");
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(file);

        List<String> softdepend = plugin.getStringList("softdepend");
        List<String> required = List.of(
                "VFBuilders", "RPCharacters", "InteractibleFurniture",
                "SimpleFactions", "AdvancedCrafting", "MMOCore"
        );
        for (String plugin1 : required) {
            assertTrue(softdepend.contains(plugin1), "softdepend should contain " + plugin1 + ", was: " + softdepend);
        }
    }

    private static final Pattern RECORD_ACTION_LITERAL =
            Pattern.compile("recordAction\\([^,]+,\\s*\"([^\"]+)\"");

    @Test
    void everyListenerRecordedActivityIdExistsInConfig() throws IOException {
        Path listenersDir = Path.of("src/main/java/tfmc/justin/activity/listeners");
        assertTrue(Files.isDirectory(listenersDir), "listeners directory should exist: " + listenersDir);

        Set<String> recordedIds = new HashSet<>();
        try (Stream<Path> files = Files.list(listenersDir)) {
            for (Path javaFile : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(javaFile);
                Matcher m = RECORD_ACTION_LITERAL.matcher(source);
                while (m.find()) {
                    recordedIds.add(m.group(1));
                }
            }
        }

        // Sanity: the six new ids should actually have been found via the
        // literal-string scan, otherwise this test would trivially pass.
        assertTrue(recordedIds.containsAll(NEW_ACTIVITY_IDS),
                "expected to find all new ids recorded by listeners, found: " + recordedIds);

        YamlConfiguration config = loadConfig();
        ConfigurationSection activities = config.getConfigurationSection("activities");
        assertTrue(activities != null, "activities section should exist");

        for (String id : recordedIds) {
            assertTrue(activities.isConfigurationSection(id),
                    "listener records activity id '" + id + "' which is missing from config.yml activities:");
        }
    }

    @Test
    void everySha256sumsJarHasMatchingWorkflowPatternAndViceVersa() throws IOException {
        Path sumsFile = Path.of("libs/SHA256SUMS");
        assertTrue(Files.exists(sumsFile), "libs/SHA256SUMS should exist");
        Path workflowFile = Path.of(".github/workflows/build.yml");
        assertTrue(Files.exists(workflowFile), "build.yml should exist");

        Set<String> jarsInSums = new HashSet<>();
        Pattern sumsLine = Pattern.compile("\\*(?:libs/)?(\\S+\\.jar)\\s*$");
        for (String line : Files.readAllLines(sumsFile)) {
            if (line.isBlank()) continue;
            Matcher m = sumsLine.matcher(line.trim());
            assertTrue(m.find(), "unexpected SHA256SUMS line format: " + line);
            jarsInSums.add(m.group(1));
        }
        assertFalse(jarsInSums.isEmpty(), "SHA256SUMS should list at least one jar");

        Set<String> jarsInWorkflow = new HashSet<>();
        Pattern patternLine = Pattern.compile("--pattern\\s+'([^']+\\.jar)'");
        String workflowSource = Files.readString(workflowFile);
        Matcher m = patternLine.matcher(workflowSource);
        while (m.find()) {
            jarsInWorkflow.add(m.group(1));
        }
        assertFalse(jarsInWorkflow.isEmpty(), "build.yml should download at least one jar pattern");

        assertEquals(jarsInSums, jarsInWorkflow,
                "SHA256SUMS jars and build.yml --pattern jars should match exactly");
    }

    @Test
    void everyPomSystemPathJarIsListedInSha256sums() throws IOException {
        Path pomFile = Path.of("pom.xml");
        assertTrue(Files.exists(pomFile), "pom.xml should exist");
        Path sumsFile = Path.of("libs/SHA256SUMS");

        Set<String> jarsInSums = new HashSet<>();
        Pattern sumsLine = Pattern.compile("\\*(?:libs/)?(\\S+\\.jar)\\s*$");
        for (String line : Files.readAllLines(sumsFile)) {
            if (line.isBlank()) continue;
            Matcher m = sumsLine.matcher(line.trim());
            assertTrue(m.find(), "unexpected SHA256SUMS line format: " + line);
            jarsInSums.add(m.group(1));
        }

        Pattern systemPath = Pattern.compile("<systemPath>[^<]*?libs/([^<]+\\.jar)</systemPath>");
        Matcher m = systemPath.matcher(Files.readString(pomFile));
        Set<String> jarsInPom = new HashSet<>();
        while (m.find()) {
            jarsInPom.add(m.group(1));
        }
        assertFalse(jarsInPom.isEmpty(), "pom.xml should have at least one systemPath jar");

        for (String jar : jarsInPom) {
            assertTrue(jarsInSums.contains(jar), "pom.xml systemPath jar '" + jar + "' missing from libs/SHA256SUMS");
        }
    }
}
