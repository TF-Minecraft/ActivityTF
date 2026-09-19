package tfmc.justin.activity.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.utils.Utils;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// The shipped default messages.yml/config.yml now mix inline #rrggbb hex
// with legacy &-codes (see the diff that introduced fillEmptySlots and the
// hex accents). YamlConfiguration.loadConfiguration works headless (pure
// SnakeYAML under the hood, no live server needed) - these tests just load
// the real resources exactly as the plugin ships them and check the hex
// strings survived as values, not as swallowed YAML comments, and that every
// placeholder callers substitute into is still present.
// ====================================
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

    // A material that does not resolve leaves the activity with no GUI icon,
    // and YAML alone will not catch a typo in one
    @Test
    void everyActivityMaterialResolves() {
        YamlConfiguration config = load("config.yml");
        ConfigurationSection activities = config.getConfigurationSection("activities");

        assertTrue(activities != null && !activities.getKeys(false).isEmpty());
        for (String key : activities.getKeys(false)) {
            String material = activities.getString(key + ".material");
            assertFalse(material == null || material.isBlank(),
                    "activities." + key + ".material should not be blank");
            assertTrue(tfmc.justin.activity.utils.ItemPath.material(material) != null
                            || tfmc.justin.activity.utils.ItemPath.pluginPath(material) != null,
                    "activities." + key + ".material is neither a Material nor an item path: " + material);
        }
    }

    // The shipped milestones must parse as a list of ints: written inline with
    // a trailing comment, a typo here reads as an empty list and silently
    // falls back to the hardcoded defaults.
    @Test
    void theShippedMilestonesParseAsNumbers() {
        YamlConfiguration config = load("config.yml");

        assertEquals(List.of(10, 20), config.getIntegerList("bar.milestones"));
    }

    // Every shipped pool entry must be drawable: a weight above 0, something
    // to say in chat and at least one item to hand over.
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

            // The shipped pool pays in items rather than commands on purpose:
            // a console 'give' that fails is invisible here, so the player is
            // marked paid having received nothing
            assertTrue(entry.get("items") instanceof List<?> items && !items.isEmpty(),
                    where + ".items should list at least one item");

            // The payload itself, not just its shape: the four paths and their
            // amounts are the whole shipped economy, and both are checkable
            // headless (ItemPath.material is a name lookup). A 'DIAMONDS' typo
            // would otherwise ship green and pay nothing.
            List<?> items = (List<?>) entry.get("items");
            for (int j = 0; j < items.size(); j++) {
                String at = where + ".items[" + j + "]";
                assertTrue(items.get(j) instanceof Map<?, ?>, at + " should be an item block");
                Map<?, ?> item = (Map<?, ?>) items.get(j);

                Object path = item.get("item");
                assertFalse(path == null || String.valueOf(path).isBlank(), at + ".item should not be blank");
                // Every shipped entry pays a bare Material on purpose: an
                // m.<type>.<id> would make the default config depend on
                // MMOItems being installed
                assertTrue(tfmc.justin.activity.utils.ItemPath.material(String.valueOf(path)) != null,
                        at + ".item is not a Material: " + path);

                Object amount = item.get("amount");
                assertTrue(amount instanceof Integer count && count >= 1 && count <= 64,
                        at + ".amount should be a whole number in 1-64, was: " + amount);
            }
        }
    }

    // The shipped multiplier: the number of pool spins, and the amount
    // multiplier for fixed drops and the daily reward. 1 is also the fallback,
    // so this reads the key through the constant load() reads it through - a
    // typo in either end fails here rather than reading as 1 in silence.
    @Test
    void shippedConfigDefaultsTheRewardMultiplierToOne() {
        YamlConfiguration config = load("config.yml");

        assertTrue(config.contains(ActivityConfiguration.REWARDS_MULTIPLIER_PATH),
                "config.yml should set " + ActivityConfiguration.REWARDS_MULTIPLIER_PATH);
        assertEquals(1, config.getInt(ActivityConfiguration.REWARDS_MULTIPLIER_PATH));
    }

    // Every "gui.<key>" literal ActivityGui.java passes to Messages.get(...)
    // must resolve against the shipped messages.yml - read straight from the
    // source file rather than hand-copied, so a new lookup added there without
    // a matching messages.yml entry fails this test instead of NPEing at
    // runtime in front of a player.
    @Test
    void everyGuiMessageKeyActivityGuiReadsExistsInMessagesYml() throws java.io.IOException {
        File source = new File("src/main/java/tfmc/justin/activity/gui/ActivityGui.java");
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

    // The daily draw picks from every loaded activity, so the shipped file
    // has to offer at least the seven a player is handed each day
    @Test
    void shippedConfigHasEnoughActivitiesForADailyDraw() {
        YamlConfiguration config = load("config.yml");
        ConfigurationSection activities = config.getConfigurationSection("activities");

        assertTrue(activities != null
                && activities.getKeys(false).size() >= tfmc.justin.activity.models.PlayerData.TASKS_PER_DAY,
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

    // The default budget the reroll button ships with: 1 a day, not off
    @Test
    void shippedConfigDefaultsRerollsPerDayToOne() {
        YamlConfiguration config = load("config.yml");

        assertEquals(1, config.getInt(ActivityConfiguration.REROLLS_PER_DAY_PATH));
    }

    // The default point gate: rerollable at 0 or 1 points earned today
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

    // The lore that shows the remaining budget needs both placeholders to be
    // worth anything - a fixed string would lie once the budget changed
    @Test
    void theRerollLoreLeftKeepsItsPlaceholders() {
        YamlConfiguration messages = load("messages.yml");

        String value = messages.getString("gui.reroll-lore-left");
        assertTrue(value.contains("%left%"));
        assertTrue(value.contains("%max%"));
    }

    // The shipped descriptions, read through the exact key the parser reads -
    // a typo in either end would leave the examples invisible in silence. Both
    // accepted shapes ship: cook_dish is a plain string, vote is a list.
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

    // Every description that ships, whatever shape it is written in, has to be
    // non-blank and use valid hex markers - the same bar display is held to
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
