package tfmc.justin.activity.config;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import tfmc.justin.activity.hooks.TLibsItems;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.GroupDef;

import tfmc.justin.activity.utils.ItemPath;
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
    // Insertion-ordered for the same reason: the GUI gives each group a row,
    // top to bottom in config order. Loaded before the activities, which are
    // validated against it.
    // ====================================
    private volatile Map<String, GroupDef> groups = new LinkedHashMap<>();

    // ====================================
    // Crafted material -> the activity id its 'craft:' key belongs to. Built
    // once here so CraftListener answers an event with one map lookup instead
    // of walking every activity. Replaced wholesale on reload, same as above.
    // ====================================
    private volatile Map<Material, String> craftActivities = new HashMap<>();

    // ====================================
    // The same thing for the 'craft:' keys written as a TLibs m.<type>.<id>
    // path: those cannot be keyed by Material, so they are walked in config
    // order and the first path the crafted item matches wins. Only reached
    // when the Material lookup above missed, so a server with no such
    // activity pays nothing for them.
    // ====================================
    private volatile List<Map.Entry<String, String>> craftPaths = List.of();

    // Read once on load rather than per craft event; TLibs is a softdepend,
    // so it is already enabled or already absent by the time we load.
    //
    // TLibs, further gated on MMOItems and MythicLib being enabled too: an
    // m.<type>.<id> path is resolved by TLibs but built from those two, so
    // TLibs alone is not enough to trust a path against - it would just
    // report every one of them as broken on first use instead of never
    // trying. Read by both the craft/icon paths above and the GUI, so both
    // ask the same question the same way.
    private volatile boolean itemPathsUsable;

    // Which of TLibs/MMOItems/MythicLib are not enabled, ready to be named in
    // the one warning that is worth logging - and only if config.yml actually
    // asks for an m. path. A server that uses none must stay silent.
    private String missingItemPathPlugins = "";

    // Set while parsing when any well-formed m.<type>.<id> value is seen, on
    // an icon or on a craft
    private boolean pluginPathConfigured;

    // ====================================
    // MMOCore profession id (normalized, see normalizeProfessionId) ->
    // activity id. Built once on load so the experience-gain listener, which
    // fires on every ore broken and every crop harvested, is a single map
    // lookup instead of a scan.
    // Replaced wholesale alongside 'activities' for the same reason.
    // ====================================
    private volatile Map<String, String> professionActivities = Map.of();

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

        List<String> missing = new ArrayList<>();
        for (String name : List.of("TLibs", "MMOItems", "MythicLib")) {
            if (!Bukkit.getPluginManager().isPluginEnabled(name)) {
                missing.add(name);
            }
        }
        itemPathsUsable = missing.isEmpty();
        missingItemPathPlugins = String.join(", ", missing);

        resetDay = parseDay(config.getString("reset.day", "MONDAY"));
        resetHour = Math.max(0, Math.min(23, config.getInt("reset.hour", 0)));

        // Reset here rather than in loadActivities: both sections can carry an
        // m. path and the one warning about them is logged after both are read
        pluginPathConfigured = false;
        loadGroups(config.getConfigurationSection("groups"));
        loadActivities(config.getConfigurationSection("activities"));

        // ====================================
        // One line for the whole file, and only when config.yml actually asks
        // for an m. path: the icons and the craft keys all fail for the same
        // reason, and repeating it per entry buried the rest of the startup log.
        // ====================================
        if ((pluginPathConfigured || !craftPaths.isEmpty()) && !itemPathsUsable) {
            plugin.getLogger().warning("config.yml uses m.<type>.<id> item paths but " + missingItemPathPlugins
                + (missingItemPathPlugins.contains(",") ? " are" : " is") + " not enabled - those icons"
                + " fall back to PAPER and those crafts are never credited.");
        }

        rewardCommands = config.getStringList("rewards.commands");
        if (rewardCommands.isEmpty()) {
            plugin.getLogger().warning("rewards.commands is empty - a full bar can be reached but nothing"
                + " can ever be claimed.");
        }
        rewardDisplay = config.getStringList("rewards.display");

        guiTitle = config.getString("gui.title", "&8Weekly Activity");
        rewardMaterial = bareMaterial(config.getString("gui.reward-material", "CHEST"), "gui.reward-material");

        barMax = Math.max(1, config.getInt("bar.max", 20));
        rewardEvery = Math.max(1, Math.min(barMax, config.getInt("bar.reward-every", 10)));
        barLength = barLength(config.getInt("bar.length", 40));

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

    // ====================================
    // The GUI's group tiles. A group carries nothing but a tile item, so
    // there is nothing here that can be wrong beyond an icon - only the count
    // is checked, since the grid has room for GroupDef.MAX_GROUPS of them.
    // Ids are lower-cased and trimmed here, and the same is done to an
    // activity's 'group' before it is looked up below, so 'Server' and
    // 'server' are the same group rather than a silent mismatch.
    // ====================================
    private void loadGroups(ConfigurationSection section) {
        Map<String, GroupDef> loaded = new LinkedHashMap<>();
        if (section == null) {
            plugin.getLogger().warning("config.yml has no 'groups' section - every activity belongs to an"
                + " unknown group and the GUI will be empty.");
            groups = loaded;
            return;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                plugin.getLogger().warning("groups." + id + " is not a section - ignoring it.");
                continue;
            }

            String key = id.trim().toLowerCase(Locale.ROOT);
            if (loaded.containsKey(key)) {
                plugin.getLogger().warning("groups." + id + " collides with an earlier group '" + key
                    + "' after lower-casing - ignoring it.");
                continue;
            }
            String iconValue = entry.getString("material", "PAPER");
            String path = "groups." + id + ".material";
            loaded.put(key, new GroupDef(
                key,
                entry.getString("display", id),
                ItemPath.isPluginPath(iconValue) ? Material.PAPER : material(iconValue, path),
                iconPath(iconValue, path)
            ));
        }

        if (loaded.size() > GroupDef.MAX_GROUPS) {
            plugin.getLogger().warning("config.yml defines " + loaded.size() + " groups but the GUI has room for "
                + GroupDef.MAX_GROUPS + " - the rest are not shown.");
        }

        groups = loaded;
    }

    private void loadActivities(ConfigurationSection section) {
        if (section == null) {
            plugin.getLogger().warning("config.yml has no 'activities' section - the bar can never fill.");
            activities = new LinkedHashMap<>();
            craftActivities = new HashMap<>();
            craftPaths = List.of();
            professionActivities = Map.of();
            return;
        }

        Map<String, ActivityDef> loaded = new LinkedHashMap<>();
        Map<Material, String> crafts = new HashMap<>();
        List<Map.Entry<String, String>> paths = new ArrayList<>();
        Map<String, String> professions = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                plugin.getLogger().warning("Activity '" + id + "' is not a configuration section, so it has no"
                    + " 'group' - the GUI has nowhere to draw it, so it is dropped entirely and nothing will ever"
                    + " be credited to it.");
                continue;
            }

            // ====================================
            // The GUI lays activities out group by group, so an activity with
            // no group, or one naming a group that does not exist, has nowhere
            // to be drawn. Checked first so it is reported even when the
            // activity also fails a later check, rather than being dropped
            // silently once the first 'continue' below fires.
            // ====================================
            String group = entry.getString("group");
            if (group == null || group.isBlank()) {
                plugin.getLogger().warning("Activity '" + id + "' has no 'group' - the GUI has nowhere to draw"
                    + " it, so it is dropped entirely and nothing will ever be credited to it.");
                continue;
            }
            String groupKey = group.trim().toLowerCase(Locale.ROOT);
            if (!groups.containsKey(groupKey)) {
                plugin.getLogger().warning("Activity '" + id + "' is in group '" + Utils.safeForLog(group)
                    + "', which is not defined under 'groups' - the GUI has nowhere to draw it, so it is"
                    + " dropped entirely and nothing will ever be credited to it.");
                continue;
            }

            int points = wholeNumber(entry, id, "points", 0);
            if (points <= 0) {
                plugin.getLogger().warning("Activity '" + id + "' is worth " + points
                    + " points - skipping it, since meeting its goal could never move the bar.");
                continue;
            }

            int dailyCap = Math.max(0, wholeNumber(entry, id, "daily-cap", 0));

            String iconValue = entry.getString("material", "PAPER");
            String iconPath = iconPath(iconValue, "activities." + id + ".material");

            loaded.put(id, new ActivityDef(
                id,
                entry.getString("display", id),
                // Still PAPER behind a path: what the GUI shows if TLibs is
                // gone or the path stops resolving. iconPath() has already
                // reported a path that could not be used, so material() -
                // which would call it an unknown material - is skipped.
                ItemPath.isPluginPath(iconValue)
                    ? Material.PAPER
                    : material(iconValue, "activities." + id + ".material"),
                iconPath,
                Math.max(1, wholeNumber(entry, id, "every", 1)),
                points,
                dailyCap,
                groupKey
            ));

            loadCraft(crafts, paths, entry.getString("craft"), id);

            String profession = entry.getString("profession");
            if (profession != null && !profession.isBlank()) {
                String key = normalizeProfessionId(profession);
                String previous = professions.put(key, id);
                if (previous != null) {
                    plugin.getLogger().warning("Activities '" + previous + "' and '" + id
                        + "' both track profession '" + key + "' - only '" + id + "' will be fed.");
                }
                // ====================================
                // Profession XP is game-influenced and can be hundreds per
                // event, unlike the one-per-action activities, so an
                // uncapped profession activity is an unbounded reward source.
                // ====================================
                if (dailyCap <= 0) {
                    plugin.getLogger().warning("Activity '" + id + "' tracks profession '" + key
                        + "' with no daily-cap - profession XP is unbounded, so this activity can"
                        + " fill the bar on its own.");
                }
            }
        }

        // ====================================
        // A group's page has one grid, so anything past GroupDef.MAX_ACTIVITIES
        // in it is dropped from the GUI. A startup-time mistake, said once at
        // startup - not once per /activity.
        // ====================================
        for (String groupId : groups.keySet()) {
            long size = loaded.values().stream().filter(def -> groupId.equals(def.group())).count();
            if (size > GroupDef.MAX_ACTIVITIES) {
                plugin.getLogger().warning("Group '" + groupId + "' has " + size + " activities but its GUI page"
                    + " has room for " + GroupDef.MAX_ACTIVITIES + " - the rest are not shown.");
            }
        }

        // One assignment publishes the whole set
        activities = loaded;
        craftActivities = crafts;
        craftPaths = List.copyOf(paths);
        professionActivities = professions;
    }

    // ====================================
    // Optional. A name that is not a material is the admin's typo, not a
    // reason to lose the rest of config.yml - say which key is wrong and
    // leave the activity in place, just with nothing feeding it.
    // ====================================
    private void loadCraft(Map<Material, String> crafts, List<Map.Entry<String, String>> paths,
                           String name, String id) {
        String problem = registerCraft(crafts, paths, name, id, ActivityConfiguration::craftableItem);
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
    static String registerCraft(Map<Material, String> crafts, List<Map.Entry<String, String>> paths,
                                String name, String id, Predicate<Material> craftable) {
        if (name == null || name.isBlank()) {
            return null;
        }

        // The name and the section key it sits under are both admin-supplied
        // and go straight into a log line
        String safe = Utils.safeForLog(name);
        String safeId = Utils.safeForLog(id);

        // An m.<type>.<id> item cannot be keyed by Material - it is matched
        // by asking TLibs, so it is kept as the path it was written as. Same
        // first-one-wins rule as the materials below.
        if (ItemPath.isPluginPath(name)) {
            String path = ItemPath.pluginPath(name);
            if (path == null) {
                return "Malformed item path '" + safe + "' at activities." + safeId
                    + ".craft - expected m.<type>.<id> - nothing will ever feed that activity.";
            }
            for (Map.Entry<String, String> entry : paths) {
                if (entry.getKey().equalsIgnoreCase(path)) {
                    String safeOwner = Utils.safeForLog(entry.getValue());
                    return "activities." + safeId + ".craft is " + safe
                        + ", which activity '" + safeOwner + "' already tracks - only '" + safeOwner
                        + "' will be credited.";
                }
            }
            paths.add(Map.entry(path, id));
            return null;
        }

        if (ItemPath.isUnsupportedPath(name)) {
            return "Unsupported item path '" + safe + "' at activities." + safeId
                + ".craft - only bare Material names, v.<material> and m.<type>.<id> are supported"
                + " - nothing will ever feed that activity.";
        }

        Material crafted = ItemPath.material(name);
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

    // ====================================
    // The m.<type>.<id> path an icon should be built from, or null when the
    // value is a plain material - which material() then reports on as before.
    // ====================================
    private String iconPath(String name, String path) {
        if (!ItemPath.isPluginPath(name)) {
            return null;
        }
        String resolved = ItemPath.pluginPath(name);
        if (resolved == null) {
            plugin.getLogger().warning("Malformed item path '" + Utils.safeForLog(name) + "' at " + path
                + " - expected m.<type>.<id> - using PAPER.");
            return null;
        }
        // Well formed, so it counts as "the admin asked for item paths" even
        // when nothing can resolve it - loadActivities says that once, for the
        // whole file, instead of once per icon.
        pluginPathConfigured = true;
        return itemPathsUsable ? resolved : null;
    }

    // ====================================
    // gui.reward-material is the one item-valued key that is not an item path:
    // it is the chest in the GUI, not an activity's icon or tracked craft, so
    // it reads bare Material names only, exactly as before.
    // ====================================
    private Material bareMaterial(String name, String path) {
        Material material = name == null ? null : Material.matchMaterial(name);
        if (material == null) {
            plugin.getLogger().warning("Unknown material '" + name + "' at " + path + " - using PAPER.");
            return Material.PAPER;
        }
        return material;
    }

    private Material material(String name, String path) {
        if (ItemPath.isUnsupportedPath(name)) {
            plugin.getLogger().warning("Unsupported item path '" + Utils.safeForLog(name) + "' at " + path
                + " - only bare Material names, v.<material> and m.<type>.<id> are supported - using PAPER.");
            return Material.PAPER;
        }
        Material material = ItemPath.material(name);
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

    // Insertion-ordered, one GUI row each
    public Map<String, GroupDef> groups() {
        return Collections.unmodifiableMap(groups);
    }

    public ActivityDef activity(String id) {
        return activities.get(id);
    }

    // ====================================
    // The activity fed by crafting this item, or null if none is. The
    // Material map answers first and answers almost every craft; the item
    // paths are only walked when it missed and there are any.
    //
    // ponytail: an MMOItems result whose base Material is also claimed by a
    // vanilla 'craft:' is credited to the vanilla activity, because the map
    // answers first. Upgrade path if that combination is ever configured:
    // check the paths before the map when both claim that Material.
    // ====================================
    public String craftActivity(ItemStack crafted) {
        String id = craftActivities.get(crafted.getType());
        if (id != null || craftPaths.isEmpty() || !itemPathsUsable) {
            return id;
        }
        return TLibsItems.match(crafted, craftPaths);
    }

    // The activity fed by an MMOCore profession, or null if none tracks it
    public String professionActivity(String professionId) {
        return professionActivity(professionActivities, professionId);
    }

    static String professionActivity(Map<String, String> professions, String professionId) {
        if (professionId == null || professionId.isBlank()) {
            return null;
        }
        return professions.get(normalizeProfessionId(professionId));
    }

    // ====================================
    // MMOCore's Profession constructor stores its id as
    // lowercase-with-underscores-and-spaces-turned-into-dashes, so a file
    // named mining_expert.yml has the id 'mining-expert'. config.yml tells
    // admins to use the file name, so both the map keys and the lookups run
    // through here - anything else silently never matches.
    // ====================================
    static String normalizeProfessionId(String professionId) {
        return professionId.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
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

    public String goalCompleteSound() {
        return goalCompleteSound;
    }

    public String barCompleteSound() {
        return barCompleteSound;
    }

    public int saveIntervalMinutes() {
        return saveIntervalMinutes;
    }

    // Whether an m.<type>.<id> path can actually be resolved right now -
    // TLibs, MMOItems and MythicLib all enabled. The GUI asks this instead
    // of re-checking isPluginEnabled("TLibs") on its own, so both places
    // agree on what "usable" means.
    public boolean itemPathsUsable() {
        return itemPathsUsable;
    }
}
