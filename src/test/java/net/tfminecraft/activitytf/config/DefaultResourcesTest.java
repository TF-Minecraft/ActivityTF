package net.tfminecraft.activitytf.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import net.tfminecraft.activitytf.utils.Utils;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultResourcesTest {

    private static YamlConfiguration load(String resource) {
        File file = new File("src/main/resources/" + resource);
        assertTrue(file.exists(), resource + " should exist");
        return YamlConfiguration.loadConfiguration(file);
    }

    @Test
    void everyGuiMessageIsNonEmpty() {
        YamlConfiguration messages = load("messages.yml");
        ConfigurationSection gui = messages.getConfigurationSection("gui");

        assertTrue(gui != null && !gui.getKeys(false).isEmpty());
        for (String key : gui.getKeys(false)) {
            String value = gui.getString(key);
            assertFalse(value == null || value.isBlank(), "gui." + key + " should not be blank");
        }
    }

    @Test
    void guiMessagesKeepTheirPlaceholders() {
        YamlConfiguration messages = load("messages.yml");

        assertTrue(messages.getString("gui.bar-name").contains("%points%"));
        assertTrue(messages.getString("gui.bar-name").contains("%max%"));
        assertTrue(messages.getString("gui.reward-click").contains("%count%"));
        assertTrue(messages.getString("gui.activity-lore-progress").contains("%bar%"));
        assertTrue(messages.getString("gui.activity-lore-today").contains("%today%"));
        assertTrue(messages.getString("gui.activity-lore-today-capped").contains("%today%"));
        assertTrue(messages.getString("gui.activity-lore-today-capped").contains("%cap%"));
        assertNull(messages.getString("gui.bar-lore-milestones"));
        assertTrue(messages.getString("gui.bar-lore-next").contains("%points%"));
    }

    private static final Pattern HEX_MARKER = Pattern.compile("#[0-9a-fA-F]{6}");

    private static void assertEveryHexMarkerIsValid(String value, String where) {
        int index = 0;
        while ((index = value.indexOf('#', index)) != -1) {
            Matcher m = HEX_MARKER.matcher(value);
            assertTrue(m.find(index) && m.start() == index,
                    where + ": '#' at index " + index + " is not followed by exactly 6 hex digits: " + value);
            index++;
        }
    }

    @Test
    void everyHexMarkerInGuiAndConfigIsSixDigits() {
        YamlConfiguration messages = load("messages.yml");
        ConfigurationSection gui = messages.getConfigurationSection("gui");
        assertTrue(gui != null && !gui.getKeys(false).isEmpty());
        for (String key : gui.getKeys(false)) {
            String value = gui.getString(key);
            if (value != null) {
                assertEveryHexMarkerIsValid(value, "gui." + key);
            }
        }

        YamlConfiguration config = load("config.yml");
        ConfigurationSection activities = config.getConfigurationSection("activities");
        assertTrue(activities != null && !activities.getKeys(false).isEmpty());
        for (String key : activities.getKeys(false)) {
            String display = activities.getString(key + ".display");
            if (display != null) {
                assertEveryHexMarkerIsValid(display, "activities." + key + ".display");
            }
        }

        List<Map<?, ?>> pool = config.getMapList(ActivityConfiguration.REWARDS_POOL_PATH);
        assertFalse(pool.isEmpty(), "rewards.pool should ship at least one entry");
        for (int i = 0; i < pool.size(); i++) {
            Object display = pool.get(i).get("display");
            assertFalse(display == null || String.valueOf(display).isBlank(),
                "rewards.pool[" + i + "].display should not be blank");
            assertEveryHexMarkerIsValid(String.valueOf(display), "rewards.pool[" + i + "].display");
        }
    }

    @Test
    void everyActivityDisplayIsNonEmpty() {
        YamlConfiguration config = load("config.yml");
        ConfigurationSection activities = config.getConfigurationSection("activities");

        assertTrue(activities != null && !activities.getKeys(false).isEmpty());
        for (String key : activities.getKeys(false)) {
            String display = activities.getString(key + ".display");
            assertFalse(display == null || display.isBlank(), "activities." + key + ".display should not be blank");
        }
    }

    @Test
    void everyActivityMaterialResolves() {
        YamlConfiguration config = load("config.yml");
        ConfigurationSection activities = config.getConfigurationSection("activities");

        assertTrue(activities != null && !activities.getKeys(false).isEmpty());
        for (String key : activities.getKeys(false)) {
            String material = activities.getString(key + ".material");
            assertFalse(material == null || material.isBlank(),
                    "activities." + key + ".material should not be blank");
            assertTrue(net.tfminecraft.activitytf.utils.ItemPath.material(material) != null
                            || net.tfminecraft.activitytf.utils.ItemPath.pluginPath(material) != null,
                    "activities." + key + ".material is neither a Material nor an item path: " + material);
        }
    }

    @Test
    void theShippedMilestonesParseAsNumbers() {
        YamlConfiguration config = load("config.yml");

        assertEquals(List.of(10, 20), config.getIntegerList("bar.milestones"));
    }

    @Test
    void everyRewardPoolEntryIsUsable() {
        YamlConfiguration config = load("config.yml");
        List<Map<?, ?>> pool = config.getMapList(ActivityConfiguration.REWARDS_POOL_PATH);

        assertFalse(pool.isEmpty());
        for (int i = 0; i < pool.size(); i++) {
            Map<?, ?> entry = pool.get(i);
            String where = "rewards.pool[" + i + "]";

            Object weight = entry.get("weight");
            assertTrue(weight instanceof Number number && number.intValue() > 0,
                    where + ".weight should be above 0");

            Object display = entry.get("display");
            assertFalse(display == null || String.valueOf(display).isBlank(),
                    where + ".display should not be blank");

            assertTrue(entry.get("items") instanceof List<?> items && !items.isEmpty(),
                    where + ".items should list at least one item");

            List<?> items = (List<?>) entry.get("items");
            for (int j = 0; j < items.size(); j++) {
                String at = where + ".items[" + j + "]";
                assertTrue(items.get(j) instanceof Map<?, ?>, at + " should be an item block");
                Map<?, ?> item = (Map<?, ?>) items.get(j);

                Object path = item.get("item");
                assertFalse(path == null || String.valueOf(path).isBlank(), at + ".item should not be blank");
                assertTrue(net.tfminecraft.activitytf.utils.ItemPath.material(String.valueOf(path)) != null,
                        at + ".item is not a Material: " + path);

                Object amount = item.get("amount");
                assertTrue(amount instanceof Integer count && count >= 1 && count <= 64,
                        at + ".amount should be a whole number in 1-64, was: " + amount);
            }
        }
    }

    @Test
    void shippedConfigDefaultsTheRewardMultiplierToOne() {
        YamlConfiguration config = load("config.yml");

        assertTrue(config.contains(ActivityConfiguration.REWARDS_MULTIPLIER_PATH),
                "config.yml should set " + ActivityConfiguration.REWARDS_MULTIPLIER_PATH);
        assertEquals(1, config.getInt(ActivityConfiguration.REWARDS_MULTIPLIER_PATH));
    }

    @Test
    void everyGuiMessageKeyActivityGuiReadsExistsInMessagesYml() throws java.io.IOException {
        File source = new File("src/main/java/net/tfminecraft/activitytf/gui/ActivityGui.java");
        assertTrue(source.exists(), "ActivityGui.java should exist");
        String content = java.nio.file.Files.readString(source.toPath());

        Pattern lookup = Pattern.compile("messages\\.get\\(\"(gui\\.[a-zA-Z0-9-]+)\"");
        Matcher matcher = lookup.matcher(content);
        List<String> keys = new java.util.ArrayList<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        assertFalse(keys.isEmpty(), "expected to find gui.* message lookups in ActivityGui.java");

        YamlConfiguration messages = load("messages.yml");
        for (String key : keys) {
            String value = messages.getString(key);
            assertFalse(value == null || value.isBlank(), key + " is read by ActivityGui but missing/blank in messages.yml");
        }
    }

    @Test
    void shippedConfigHasEnoughActivitiesForADailyDraw() {
        YamlConfiguration config = load("config.yml");
        ConfigurationSection activities = config.getConfigurationSection("activities");

        assertTrue(activities != null
                && activities.getKeys(false).size() >= net.tfminecraft.activitytf.models.PlayerData.TASKS_PER_DAY,
                "config.yml should ship at least PlayerData.TASKS_PER_DAY activities");
    }

    @Test
    void shippedConfigSetsTheBarAndDailyMaxDefaults() {
        YamlConfiguration config = load("config.yml");

        assertEquals(10, config.getInt("bar.daily-max"));
        assertEquals(50, config.getInt("bar.max"));
    }

    @Test
    void shippedMessagesHasTheDailyBarName() {
        YamlConfiguration messages = load("messages.yml");

        String value = messages.getString("gui.daily-bar-name");
        assertFalse(value == null || value.isBlank(), "gui.daily-bar-name should not be blank");
    }

    @Test
    void shippedConfigDefaultsRerollsPerDayToOne() {
        YamlConfiguration config = load("config.yml");

        assertEquals(1, config.getInt(ActivityConfiguration.REROLLS_PER_DAY_PATH));
    }

    @Test
    void shippedConfigDefaultsRerollMaxPointsToOne() {
        YamlConfiguration config = load("config.yml");

        assertEquals(1, config.getInt(ActivityConfiguration.REROLL_MAX_POINTS_PATH));
    }

    @Test
    void shippedMessagesHasEveryRerollKeyNonBlank() {
        YamlConfiguration messages = load("messages.yml");

        for (String key : List.of("reroll-done", "reroll-none-left", "reroll-disabled", "reroll-failed",
                "reroll-locked", "reroll-too-late")) {
            String value = messages.getString(key);
            assertFalse(value == null || value.isBlank(), key + " should not be blank");
        }
        for (String key : List.of("gui.reroll-name", "gui.reroll-lore-left",
                "gui.reroll-lore-locked", "gui.reroll-lore-disabled",
                "gui.reroll-lore-too-late")) {
            String value = messages.getString(key);
            assertFalse(value == null || value.isBlank(), key + " should not be blank");
        }
    }

    @Test
    void theRerollLoreLeftKeepsItsPlaceholders() {
        YamlConfiguration messages = load("messages.yml");

        String value = messages.getString("gui.reroll-lore-left");
        assertTrue(value.contains("%left%"));
        assertTrue(value.contains("%max%"));
    }

    @Test
    void theShippedConfigDescribesVoteAndCookDish() {
        YamlConfiguration config = load("config.yml");
        String key = "." + ActivityConfiguration.DESCRIPTION_KEY;

        String cookDish = config.getString("activities.cook_dish" + key);
        assertFalse(cookDish == null || cookDish.isBlank(), "activities.cook_dish" + key + " should not be blank");

        List<String> vote = config.getStringList("activities.vote" + key);
        assertFalse(vote.isEmpty(), "activities.vote" + key + " should ship at least one line");
        for (String line : vote) {
            assertFalse(line == null || line.isBlank(), "activities.vote" + key + " has a blank line");
        }
    }

    @Test
    void everyShippedDescriptionIsNonBlankAndValidHex() {
        YamlConfiguration config = load("config.yml");
        ConfigurationSection activities = config.getConfigurationSection("activities");
        assertTrue(activities != null && !activities.getKeys(false).isEmpty());

        int described = 0;
        for (String id : activities.getKeys(false)) {
            String path = id + "." + ActivityConfiguration.DESCRIPTION_KEY;
            Object raw = activities.get(path);
            if (raw == null) {
                continue;
            }
            described++;
            List<?> lines = raw instanceof List<?> list ? list : List.of(raw);
            assertFalse(lines.isEmpty(), "activities." + path + " should not be empty");
            for (Object line : lines) {
                String value = String.valueOf(line);
                assertFalse(line == null || value.isBlank(), "activities." + path + " has a blank line");
                assertEveryHexMarkerIsValid(value, "activities." + path);
            }
        }
        assertTrue(described >= 1, "config.yml should ship a description example, found " + described);
    }

    @Test
    void colorizeStripsHexAndLegacyCodes() {
        String result = Utils.colorize("#e6ca40&lX");

        assertFalse(result.contains("#e6ca40"), "hex marker should be translated away: " + result);
        assertFalse(result.contains("&l"), "legacy code should be translated away: " + result);
    }
}
