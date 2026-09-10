package tfmc.justin.activity.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import tfmc.justin.activity.models.ActivityDef;

import tfmc.justin.activity.utils.Utils;
import tfmc.justin.activity.utils.Weeks;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

// ====================================
// Typed view over config.yml. Everything is read once on load so the hot
// paths (every recorded action, every GUI open) never touch YAML.
// ====================================
public class ActivityConfiguration {

    private final JavaPlugin plugin;
    private final Messages messages;

    // ====================================
    // Insertion-ordered: the GUI lays activities out in config order.
    // Volatile and replaced wholesale on reload rather than cleared and
    // refilled, because PlaceholderAPI reads it from other threads and must
    // never see a half-rebuilt map.
    // ====================================
    private volatile Map<String, ActivityDef> activities = new LinkedHashMap<>();

    // ====================================
    // Crafted material -> the activity id its 'craft:' key belongs to. Built
    // once here so CraftListener answers an event with one map lookup instead
    // of walking every activity. Replaced wholesale on reload, same as above.
    // ====================================
    private volatile Map<Material, String> craftActivities = new HashMap<>();

    // ====================================
    // volatile: read from PlaceholderAPI's own threads after /activity reload
    // rebuilds them on the main thread, so readers need a visibility guarantee.
    // ====================================
    private volatile DayOfWeek resetDay;
    private volatile int resetHour;

    private List<String> rewardCommands;
    private List<String> rewardDisplay;

    private String guiTitle;
    private Material rewardMaterial;

    private volatile int barMax;
    private volatile int rewardEvery;
    private volatile int barLength;
    private volatile String barFilledChar;
    private volatile String barEmptyChar;
    private volatile String barFilledColor;
    private volatile String barEmptyColor;

    private String goalCompleteSound;
    private String barCompleteSound;

    private int saveIntervalMinutes;

    private volatile int afkMinutes;

    public ActivityConfiguration(JavaPlugin plugin) {
        this.plugin = plugin;
        this.messages = new Messages(plugin);
    }

    public void load() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        messages.reload();

        resetDay = parseDay(config.getString("reset.day", "MONDAY"));
        resetHour = Math.max(0, Math.min(23, config.getInt("reset.hour", 0)));

        loadActivities(config.getConfigurationSection("activities"));

        rewardCommands = config.getStringList("rewards.commands");
        if (rewardCommands.isEmpty()) {
            plugin.getLogger().warning("rewards.commands is empty - a full bar can be reached but nothing"
                + " can ever be claimed.");
        }
        rewardDisplay = config.getStringList("rewards.display");

        guiTitle = config.getString("gui.title", "&8Weekly Activity");
        rewardMaterial = material(config.getString("gui.reward-material", "CHEST"), "gui.reward-material");

        barMax = Math.max(1, config.getInt("bar.max", 20));
        rewardEvery = Math.max(1, Math.min(barMax, config.getInt("bar.reward-every", 10)));
        barLength = barLength(config.getInt("bar.length", 20));
        barFilledChar = config.getString("bar.filled-char", "░");
        barEmptyChar = config.getString("bar.empty-char", "░");
        barFilledColor = config.getString("bar.filled-color", "&a");
        barEmptyColor = config.getString("bar.empty-color", "&7");

        goalCompleteSound = soundKey(config.getString("sounds.goal-complete", ""));
        barCompleteSound = soundKey(config.getString("sounds.bar-complete", ""));

        saveIntervalMinutes = Math.max(1, config.getInt("save-interval-minutes", 5));

        // 0 disables the idle check, so unlike the other clamps this one has
        // no lower bound of 1
        afkMinutes = Math.max(0, config.getInt("playtime.afk-minutes", 5));

        // The timer credits the hardcoded id 'playtime', so renaming that
        // activity kills the feature without touching this section
        if (config.contains("playtime.afk-minutes") && !activities.containsKey("playtime")) {
            plugin.getLogger().warning("playtime.afk-minutes is set but there is no 'playtime' activity"
                + " - no minute will ever be credited.");
        }

        warnIfBarUnreachable();
    }

    private void loadActivities(ConfigurationSection section) {
        if (section == null) {
            plugin.getLogger().warning("config.yml has no 'activities' section - the bar can never fill.");
            activities = new LinkedHashMap<>();
            craftActivities = new HashMap<>();
            return;
        }

        Map<String, ActivityDef> loaded = new LinkedHashMap<>();
        Map<Material, String> crafts = new HashMap<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }

            int points = wholeNumber(entry, id, "points", 0);
            if (points <= 0) {
                plugin.getLogger().warning("Activity '" + id + "' is worth " + points
                    + " points - skipping it, since meeting its goal could never move the bar.");
                continue;
            }

            loaded.put(id, new ActivityDef(
                id,
                entry.getString("display", id),
                material(entry.getString("material", "PAPER"), "activities." + id + ".material"),
                Math.max(1, wholeNumber(entry, id, "every", 1)),
                points,
                Math.max(0, wholeNumber(entry, id, "daily-cap", 0))
            ));

            loadCraft(crafts, entry.getString("craft"), id);
        }

        // One assignment publishes the whole set
        activities = loaded;
        craftActivities = crafts;
    }

    // ====================================
    // Optional. A name that is not a material is the admin's typo, not a
    // reason to lose the rest of config.yml - say which key is wrong and
    // leave the activity in place, just with nothing feeding it.
    // ====================================
    private void loadCraft(Map<Material, String> crafts, String name, String id) {
        String problem = registerCraft(crafts, name, id, ActivityConfiguration::craftableItem);
        if (problem != null) {
            plugin.getLogger().warning(problem);
        }
    }

    // ====================================
    // The whole craft: decision, pure so it can be tested: registers the name
    // under this activity and returns the warning to log, or null when there
    // is nothing to say.
    //
    // Not routed through material(): that one falls back to PAPER, which here
    // would silently start tracking paper crafts instead of saying nothing
    // feeds the activity. Only a real item can come out of a crafting grid,
    // so a block-only or legacy name is rejected the same way a typo is - and
    // so is AIR, which passes isItem() and is what the special recipes
    // (firework rockets, banner copies, map extending) report as their result,
    // so accepting it would match all of them at once.
    //
    // Two activities claiming one material would make the second unreachable,
    // so the first one wins and the clash is named.
    //
    // craftable is handed in because Material#isAir and #isItem both go
    // through the item registry, which only exists on a running server.
    // ====================================
    static String registerCraft(Map<Material, String> crafts, String name, String id,
                                Predicate<Material> craftable) {
        if (name == null || name.isBlank()) {
            return null;
        }

        // The name and the section key it sits under are both admin-supplied
        // and go straight into a log line
        String safe = Utils.safeForLog(name);
        String safeId = Utils.safeForLog(id);

        Material crafted = Material.matchMaterial(name);
        if (crafted == null) {
            return "Unknown material '" + safe + "' at activities." + safeId
                + ".craft - nothing will ever feed that activity.";
        }
        if (!craftable.test(crafted)) {
            return "'" + safe + "' at activities." + safeId
                + ".craft is not an item and cannot be crafted - nothing will ever feed"
                + " that activity.";
        }

        String existing = crafts.putIfAbsent(crafted, id);
        if (existing == null) {
            return null;
        }
        String safeExisting = Utils.safeForLog(existing);
        return "activities." + safeId + ".craft is " + crafted
            + ", which activity '" + safeExisting + "' already tracks - only '" + safeExisting
            + "' will be credited.";
    }

    private static boolean craftableItem(Material material) {
        return !material.isAir() && material.isItem();
    }

    // ====================================
    // A quoted or misspelled number reads as 0 through getInt, which would
    // silently make an activity worthless or its goal met on the first action.
    // Name the activity and the key rather than guessing quietly.
    // ====================================
    private int wholeNumber(ConfigurationSection entry, String id, String key, int fallback) {
        if (entry.contains(key) && !entry.isInt(key)) {
            plugin.getLogger().warning("activities." + id + "." + key + " is not a whole number ('"
                + entry.get(key) + "') - using " + fallback + ".");
            return fallback;
        }
        return entry.getInt(key, fallback);
    }

    // A bar of 0 glyphs is invisible and one of 5000 does not fit in a lore
    // line, so the value is pinned to something that can actually be rendered
    private int barLength(int length) {
        if (length < 1 || length > 100) {
            int clamped = Math.max(1, Math.min(100, length));
            plugin.getLogger().warning("bar.length " + length + " is outside 1-100 - using " + clamped + ".");
            return clamped;
        }
        return length;
    }

    // ====================================
    // Seven days of every capped activity is the ceiling - if that is under
    // the first milestone nothing can ever be claimed. An uncapped activity
    // has no ceiling, so the check is skipped.
    // ====================================
    private void warnIfBarUnreachable() {
        long weekly = 0;
        for (ActivityDef def : activities.values()) {
            if (def.dailyCap() == 0) {
                return;
            }
            weekly += def.dailyCap();
        }
        weekly *= 7;

        if (weekly < rewardEvery) {
            plugin.getLogger().warning("All activities together are capped at " + weekly
                + " points a week - nobody can reach the first reward at " + rewardEvery + ".");
        }
    }

    private Material material(String name, String path) {
        Material material = name == null ? null : Material.matchMaterial(name);
        if (material == null) {
            plugin.getLogger().warning("Unknown material '" + name + "' at " + path + " - using PAPER.");
            return Material.PAPER;
        }
        return material;
    }

    private DayOfWeek parseDay(String name) {
        try {
            return DayOfWeek.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown reset.day '" + name + "' - using MONDAY.");
            return DayOfWeek.MONDAY;
        }
    }

    // ====================================
    // Sounds are played by key rather than by the Sound enum: the enum's
    // constants move between Minecraft versions, the keys do not. Admins can
    // write either ENTITY_EXPERIENCE_ORB_PICKUP or entity.experience.orb.pickup.
    // ====================================
    private String soundKey(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.trim().toLowerCase(Locale.ROOT).replace('_', '.');
    }

    public Messages messages() {
        return messages;
    }

    public List<ActivityDef> activities() {
        return new ArrayList<>(activities.values());
    }

    public ActivityDef activity(String id) {
        return activities.get(id);
    }

    // The activity fed by crafting this material, or null if none is
    public String craftActivity(Material crafted) {
        return craftActivities.get(crafted);
    }

    // How long a player must have been idle before a minute stops counting.
    // 0 means the idle check is off and every online minute counts.
    public int afkMinutes() {
        return afkMinutes;
    }

    // ====================================
    // Both keys off one clock reading. Asking for them separately can straddle
    // a midnight tick and produce a day key from the new day with a week key
    // from the old one, which reads as a rollover that never happened.
    // ====================================
    public Keys currentKeys() {
        LocalDateTime now = LocalDateTime.now();
        return new Keys(Weeks.weekKey(now, resetDay, resetHour), Weeks.dayKey(now.toLocalDate()));
    }

    public record Keys(String week, String day) {
    }

    public DayOfWeek resetDay() {
        return resetDay;
    }

    public int resetHour() {
        return resetHour;
    }

    public List<String> rewardCommands() {
        return Collections.unmodifiableList(rewardCommands);
    }

    public List<String> rewardDisplay() {
        return Collections.unmodifiableList(rewardDisplay);
    }

    public String guiTitle() {
        return guiTitle;
    }

    public Material rewardMaterial() {
        return rewardMaterial;
    }

    public int barMax() {
        return barMax;
    }

    public int rewardEvery() {
        return rewardEvery;
    }

    public int barLength() {
        return barLength;
    }

    public String barFilledChar() {
        return barFilledChar;
    }

    public String barEmptyChar() {
        return barEmptyChar;
    }

    public String barFilledColor() {
        return barFilledColor;
    }

    public String barEmptyColor() {
        return barEmptyColor;
    }

    public String goalCompleteSound() {
        return goalCompleteSound;
    }

    public String barCompleteSound() {
        return barCompleteSound;
    }

    public int saveIntervalMinutes() {
        return saveIntervalMinutes;
    }
}
