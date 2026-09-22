package net.tfminecraft.activitytf.config;

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

class Tier1HooksTest {

    private static final List<String> NEW_ACTIVITY_IDS = List.of(
            "vehicle_build", "ic_chat", "furniture_place",
            "battle_joined", "advcraft_item", "market_sale",
            "casino_win", "cook_dish", "animal_universal_feed"
    );

    private static final List<String> STATION_ACTIVITY_IDS = List.of(
            "ingot_flint", "ingot_coal",
            "tool_iron_pickaxe", "tool_iron_axe",
            "block_andesite", "block_clay",
            "forester_arrow", "forester_string",
            "alchemy_minor_health", "alchemy_powder",
            "instrument_iron_lute", "instrument_steel_lute",
            "research_scribe_paper", "research_bronze",
            "medicine_herb_mixture", "medicine_splint",
            "engineer_bullet_box", "engineer_fuel",
            "fishing_rod", "fishing_iron_hook",
            "magic_basic_handle", "magic_iron_core",
            "animal_whistle"
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

        for (String id : Stream.concat(NEW_ACTIVITY_IDS.stream(), STATION_ACTIVITY_IDS.stream()).toList()) {
            ConfigurationSection section = activities.getConfigurationSection(id);
            assertTrue(section != null, "activities." + id + " should exist");

            assertTrue(section.getInt("every", -1) >= 1, "activities." + id + ".every should be >= 1");
            assertTrue(section.getInt("points", -1) >= 1, "activities." + id + ".points should be >= 1");
            assertTrue(section.getInt("daily-cap", -1) >= 0, "activities." + id + ".daily-cap should be >= 0");

            String display = section.getString("display");
            assertFalse(display == null || display.isBlank(), "activities." + id + ".display should not be blank");

        }
    }

    @Test
    void everyStationActivityDeclaresAUniqueStationRecipe() {
        YamlConfiguration config = loadConfig();
        ConfigurationSection activities = config.getConfigurationSection("activities");
        assertTrue(activities != null, "activities section should exist");

        Set<String> claimed = new HashSet<>();
        for (String id : STATION_ACTIVITY_IDS) {
            String station = activities.getString(id + ".station");
            assertFalse(station == null || station.isBlank(), "activities." + id + ".station should not be blank");
            assertTrue(station.matches("[a-z0-9-]+/[a-z0-9-]+"),
                    "activities." + id + ".station should be '<station>/<recipe>', was: " + station);
            assertTrue(claimed.add(station),
                    "two station activities claim '" + station + "' - only the last one would be fed");
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
                "SimpleFactions", "AdvancedCrafting", "MMOCore",
                "MarketBlock", "Archaeo", "Games", "Cooking", "MMOItems"
        );
        for (String plugin1 : required) {
            assertTrue(softdepend.contains(plugin1), "softdepend should contain " + plugin1 + ", was: " + softdepend);
        }
    }

    private static final Pattern RECORD_ACTION_LITERAL =
            Pattern.compile("recordAction\\([^,]+,\\s*\"([^\"]+)\"");

    @Test
    void everyListenerRecordedActivityIdExistsInConfig() throws IOException {
        Path listenersDir = Path.of("src/main/java/net/tfminecraft/activitytf/listeners");
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
    void everyChecksumJarHasMatchingDownloadAndViceVersa() throws IOException {
        Path sumsFile = Path.of(".github/dependencies.sha256");
        assertTrue(Files.exists(sumsFile), ".github/dependencies.sha256 should exist");
        Path workflowFile = Path.of(".github/scripts/prepare-release.sh");
        assertTrue(Files.exists(workflowFile), "prepare-release.sh should exist");

        Set<String> jarsInSums = new HashSet<>();
        Pattern sumsLine = Pattern.compile("^[0-9a-f]{64}  libs/(\\S+\\.jar)$");
        for (String line : Files.readAllLines(sumsFile)) {
            if (line.isBlank()) continue;
            Matcher m = sumsLine.matcher(line.trim());
            assertTrue(m.find(), "unexpected SHA256SUMS line format: " + line);
            jarsInSums.add(m.group(1));
        }
        assertFalse(jarsInSums.isEmpty(), "SHA256SUMS should list at least one jar");

        Set<String> jarsInWorkflow = new HashSet<>();
        Pattern patternLine = Pattern.compile("> \"libs/([^\"]+\\.jar)\"");
        String workflowSource = Files.readString(workflowFile);
        Matcher m = patternLine.matcher(workflowSource);
        while (m.find()) {
            jarsInWorkflow.add(m.group(1));
        }
        assertFalse(jarsInWorkflow.isEmpty(), "prepare-release.sh should download at least one jar");

        assertEquals(jarsInSums, jarsInWorkflow,
                "Checksum entries and downloaded jars should match exactly");
    }

    @Test
    void everyInstalledLocalJarIsChecksumPinned() throws IOException {
        Path pomFile = Path.of("pom.xml");
        assertTrue(Files.exists(pomFile), "pom.xml should exist");
        Path sumsFile = Path.of(".github/dependencies.sha256");

        Set<String> jarsInSums = new HashSet<>();
        Pattern sumsLine = Pattern.compile("^[0-9a-f]{64}  libs/(\\S+\\.jar)$");
        for (String line : Files.readAllLines(sumsFile)) {
            if (line.isBlank()) continue;
            Matcher m = sumsLine.matcher(line.trim());
            assertTrue(m.find(), "unexpected SHA256SUMS line format: " + line);
            jarsInSums.add(m.group(1));
        }

        String pom = Files.readString(pomFile);
        assertFalse(pom.contains("<systemPath>"), "Local APIs must use provided Maven dependencies");
        String installer = Files.readString(Path.of(".github/scripts/install-local-dependencies.sh"));
        assertTrue(installer.contains("sha256sum --check .github/dependencies.sha256"));
        Set<String> installed = new HashSet<>();
        Matcher files = Pattern.compile("-Dfile=\"libs/([^\"]+\\.jar)\"").matcher(installer);
        while (files.find()) installed.add(files.group(1));
        assertEquals(jarsInSums, installed, "Every installed JAR must have a pinned checksum");
        for (String line : Files.readAllLines(sumsFile)) {
            if (line.isBlank()) continue;
            String suffix = "-tfmc-" + line.substring(0, 12);
            assertTrue(installer.contains(suffix), "Installer must use hash-qualified coordinates");
            assertTrue(pom.contains(suffix + "</version>"), "POM must use the same pinned coordinates");
        }
    }
}
