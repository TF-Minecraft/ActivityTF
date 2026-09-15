package tfmc.justin.activity.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.GroupDef;
import tfmc.justin.activity.utils.Utils;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(messages.getString("gui.reward-lore-claimed").contains("%claimed%"));
        assertTrue(messages.getString("gui.reward-lore-claimed").contains("%total%"));
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

        List<String> rewardDisplay = config.getStringList("rewards.display");
        for (int i = 0; i < rewardDisplay.size(); i++) {
            assertEveryHexMarkerIsValid(rewardDisplay.get(i), "rewards.display[" + i + "]");
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

    // Mirrors the load-time validation in ActivityConfiguration: every
    // activity's group must resolve to a real 'groups' key (case-insensitive),
    // and both sides must fit the GUI window ActivityGui draws into.
    @Test
    void everyActivityGroupResolvesAndFitsTheGui() {
        YamlConfiguration config = load("config.yml");
        ConfigurationSection groupsSection = config.getConfigurationSection("groups");
        ConfigurationSection activities = config.getConfigurationSection("activities");

        assertTrue(groupsSection != null && !groupsSection.getKeys(false).isEmpty());
        assertTrue(activities != null && !activities.getKeys(false).isEmpty());

        assertTrue(groupsSection.getKeys(false).size() <= GroupDef.MAX_GROUPS,
                "groups has more than GroupDef.MAX_GROUPS (" + GroupDef.MAX_GROUPS + ") entries");

        Map<String, String> groupKeys = new HashMap<>();
        for (String key : groupsSection.getKeys(false)) {
            groupKeys.put(key.toLowerCase(Locale.ROOT), key);
        }

        Map<String, Integer> perGroupCount = new HashMap<>();
        for (String key : activities.getKeys(false)) {
            String group = activities.getString(key + ".group");
            assertFalse(group == null || group.isBlank(), "activities." + key + ".group should not be blank");
            String groupKey = group.trim().toLowerCase(Locale.ROOT);
            assertTrue(groupKeys.containsKey(groupKey),
                    "activities." + key + ".group '" + group + "' is not a key of 'groups'");
            perGroupCount.merge(groupKey, 1, Integer::sum);
        }

        for (Map.Entry<String, Integer> entry : perGroupCount.entrySet()) {
            assertTrue(entry.getValue() <= GroupDef.MAX_ACTIVITIES,
                    "group '" + entry.getKey() + "' has " + entry.getValue()
                            + " activities, more than GroupDef.MAX_ACTIVITIES (" + GroupDef.MAX_ACTIVITIES + ")");
        }
    }

    @Test
    void everyGroupMaterialResolves() {
        YamlConfiguration config = load("config.yml");
        ConfigurationSection groupsSection = config.getConfigurationSection("groups");

        assertTrue(groupsSection != null && !groupsSection.getKeys(false).isEmpty());
        for (String key : groupsSection.getKeys(false)) {
            String material = groupsSection.getString(key + ".material");
            assertFalse(material == null || material.isBlank(),
                    "groups." + key + ".material should not be blank");
            assertTrue(tfmc.justin.activity.utils.ItemPath.material(material) != null
                            || tfmc.justin.activity.utils.ItemPath.pluginPath(material) != null,
                    "groups." + key + ".material is neither a Material nor an item path: " + material);
        }
    }

    @Test
    void everyRewardDisplayLineIsNonEmpty() {
        YamlConfiguration config = load("config.yml");
        var display = config.getStringList("rewards.display");

        assertFalse(display.isEmpty());
        for (String line : display) {
            assertFalse(line == null || line.isBlank(), "rewards.display entries should not be blank");
        }
    }

    @Test
    void colorizeStripsHexAndLegacyCodes() {
        String result = Utils.colorize("#e6ca40&lX");

        assertFalse(result.contains("#e6ca40"), "hex marker should be translated away: " + result);
        assertFalse(result.contains("&l"), "legacy code should be translated away: " + result);
    }
}
