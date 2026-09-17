package tfmc.justin.activity.config;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import tfmc.justin.activity.hooks.TLibsItems;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.models.RewardEntry;

import tfmc.justin.activity.utils.ItemPath;
import tfmc.justin.activity.utils.Utils;
import tfmc.justin.activity.utils.Weeks;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Predicate;

// ====================================
// Typed view over config.yml. Everything is read once on load so the hot
// paths (every recorded action, every GUI open) never touch YAML.
// ====================================
public class ActivityConfiguration {

    private final JavaPlugin plugin;
    private final Messages messages;

    // ====================================
    // Insertion-ordered, the order config.yml lists them in.
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
    // The ids marked 'daily-guaranteed: true', in config order - the ones the
    // daily draw always hands out. Immutable and replaced wholesale on reload
    // for the same reason as the maps around it.
    // ====================================
    private volatile List<String> guaranteedActivities = List.of();

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
    // MMOItems crafting stations. Key is either '<station>' (any recipe at
    // that station) or '<station>/<recipe>', both normalized the same way as
    // the 'station:' values they came from, so MmoItemsStationListener only
    // has to hand over the two ids it got from the event.
    // ====================================
    private volatile Map<String, String> stationActivities = Map.of();

    // ====================================
    // volatile: read from PlaceholderAPI's own threads after /activity reload
    // rebuilds them on the main thread, so readers need a visibility guarantee.
    // ====================================
    private volatile DayOfWeek resetDay;
    private volatile int resetHour;

    // Replaced wholesale on reload and read on the main thread only, but kept
    // immutable so a reward command that reloads mid-claim cannot change the
    // list the claim is paying out of
    private volatile List<RewardEntry> rewardPool = List.of();

    private String guiTitle;

    private volatile int barMax;
    private volatile int dailyMax;
    // Ascending, deduped, every value inside 1..barMax. Never empty.
    private volatile List<Integer> milestones = List.of();
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

        // Reset here rather than in loadActivities: the one warning about m.
        // paths is logged after the section is read
        pluginPathConfigured = false;
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

        rewardPool = loadRewardPool(config);

        guiTitle = config.getString("gui.title", "&8Weekly Activity");

        barMax = Math.max(1, config.getInt("bar.max", 50));
        dailyMax = Math.max(1, config.getInt("bar.daily-max", 10));
        // After barMax: every milestone is validated against it
        milestones = loadMilestones(config.getIntegerList("bar.milestones"));
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

    private void loadActivities(ConfigurationSection section) {
        if (section == null) {
            plugin.getLogger().warning("config.yml has no 'activities' section - the bar can never fill.");
            activities = new LinkedHashMap<>();
            craftActivities = new HashMap<>();
            craftPaths = List.of();
            professionActivities = Map.of();
            stationActivities = Map.of();
            guaranteedActivities = List.of();
            return;
        }

        Map<String, ActivityDef> loaded = new LinkedHashMap<>();
        Map<Material, String> crafts = new HashMap<>();
        List<Map.Entry<String, String>> paths = new ArrayList<>();
        Map<String, String> professions = new LinkedHashMap<>();
        Map<String, String> stations = new LinkedHashMap<>();
        List<String> guaranteed = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                plugin.getLogger().warning("Activity '" + id + "' is not a configuration section - it is"
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
                dailyCap
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

            loadStation(stations, entry, id);

            if (flag(entry, id, "daily-guaranteed", false)) {
                guaranteed.add(id);
            }
        }

        // ====================================
        // More guaranteed activities than there are task slots: the draw can
        // only hold TASKS_PER_DAY of them, so every other activity in the file
        // becomes undrawable. Worth one line at load - it is almost certainly
        // not what the admin meant.
        // ====================================
        if (guaranteed.size() > PlayerData.TASKS_PER_DAY) {
            plugin.getLogger().warning(guaranteed.size() + " activities are marked daily-guaranteed but only "
                + PlayerData.TASKS_PER_DAY + " tasks are handed out a day - every draw is "
                + PlayerData.TASKS_PER_DAY + " of them picked at random and no other activity can"
                + " ever be drawn.");
        }

        // One assignment publishes the whole set
        activities = loaded;
        craftActivities = crafts;
        craftPaths = List.copyOf(paths);
        professionActivities = professions;
        stationActivities = stations;
        guaranteedActivities = List.copyOf(guaranteed);
    }

    // ====================================
    // Optional 'station: <station>' or 'station: <station>/<recipe>'. An
    // activity already fed by 'craft:' or 'profession:' would be fed by two
    // unrelated sources at once, so the extra 'station:' is reported and
    // dropped and the first-declared feed is what stays.
    // ====================================
    private void loadStation(Map<String, String> stations, ConfigurationSection entry, String id) {
        String station = entry.getString("station");
        if (station == null) {
            return;
        }
        if (station.isBlank()) {
            plugin.getLogger().warning("Activity '" + id + "' has a malformed 'station': "
                + Utils.safeForLog(station) + " - expected <station> or <station>/<recipe> - nothing will"
                + " ever feed that activity.");
            return;
        }

        String other = null;
        for (String key : List.of("craft", "profession")) {
            String value = entry.getString(key);
            if (value != null && !value.isBlank()) {
                other = key;
                break;
            }
        }
        if (other != null) {
            plugin.getLogger().warning("Activity '" + id + "' has both '" + other + "' and 'station' - an"
                + " activity can only be fed by one source, so 'station' is ignored and '" + other
                + "' is kept.");
            return;
        }

        String stationKey = stationKey(station);
        if (stationKey.isEmpty() || stationKey.startsWith("/") || stationKey.endsWith("/")) {
            plugin.getLogger().warning("Activity '" + id + "' has a malformed 'station': "
                + Utils.safeForLog(station) + " - expected <station> or <station>/<recipe> - nothing will"
                + " ever feed that activity.");
            return;
        }

        String previous = stations.put(stationKey, id);
        if (previous != null) {
            plugin.getLogger().warning("Activities '" + previous + "' and '" + id
                + "' both track station '" + stationKey + "' - only '" + id + "' will be fed.");
        }
    }

    // Station and recipe ids are matched case-insensitively and trimmed, on
    // both sides, so 'Ingot-Station / Flint' and 'ingot-station/flint' are
    // the same key. More than one slash is rejected as malformed.
    private static String stationKey(String station) {
        int slash = station.indexOf('/');
        if (slash < 0) {
            return normalizeStationPart(station);
        }
        // Reject values with more than one slash
        if (station.indexOf('/', slash + 1) >= 0) {
            return "";
        }
        return normalizeStationPart(station.substring(0, slash))
            + "/" + normalizeStationPart(station.substring(slash + 1));
    }

    private static String normalizeStationPart(String part) {
        return part.trim().toLowerCase(Locale.ROOT);
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

    // ====================================
    // Optional boolean activity key. A value that is not a boolean is the
    // admin's typo - it is named and the fallback is used, the same way
    // wholeNumber() handles a non-number.
    // ====================================
    private boolean flag(ConfigurationSection entry, String id, String key, boolean fallback) {
        if (entry.contains(key) && !entry.isBoolean(key)) {
            plugin.getLogger().warning("activities." + id + "." + key + " is not true or false ('"
                + entry.get(key) + "') - using " + fallback + ".");
            return fallback;
        }
        return entry.getBoolean(key, fallback);
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
    // The reward pool. Every entry is validated on its own: a bad weight or an
    // entry with nothing to run is dropped with a warning rather than taking
    // the rest of the pool down with it. Weights are clamped so a pool of
    // absurd numbers cannot overflow the cumulative total the draw walks.
    // ====================================
    private List<RewardEntry> loadRewardPool(FileConfiguration config) {
        List<RewardEntry> pool = new ArrayList<>();
        int index = 0;
        for (Map<?, ?> entry : config.getMapList("rewards.pool")) {
            String where = "rewards.pool[" + index++ + "]";

            int weight = entry.get("weight") instanceof Number number ? number.intValue() : 1;
            if (weight <= 0) {
                plugin.getLogger().warning(where + " has weight " + weight + " - a weight must be above 0,"
                    + " so this reward is skipped and can never be drawn.");
                continue;
            }

            List<String> commands = new ArrayList<>();
            if (entry.get("commands") instanceof List<?> raw) {
                for (Object command : raw) {
                    if (command != null) {
                        commands.add(String.valueOf(command));
                    }
                }
            }
            if (commands.isEmpty()) {
                plugin.getLogger().warning(where + " has no commands - skipped, since drawing it would pay"
                    + " the player nothing.");
                continue;
            }

            Object display = entry.get("display");
            pool.add(new RewardEntry(Math.min(weight, 1_000_000),
                display == null ? "" : String.valueOf(display), List.copyOf(commands)));
        }

        if (pool.isEmpty()) {
            plugin.getLogger().warning("rewards.pool is empty or every entry in it was dropped - a milestone"
                + " can be reached but nothing can ever be claimed.");
        }
        return List.copyOf(pool);
    }

    // ====================================
    // The point totals a reward can be claimed at. Sorted and deduped, since
    // claim() walks them in order and pays each one once; anything outside the
    // bar is dropped, because a milestone past bar.max can never be reached
    // and one at 0 would be claimable before anything was done.
    // ====================================
    private List<Integer> loadMilestones(List<Integer> raw) {
        TreeSet<Integer> milestones = new TreeSet<>();
        for (Integer milestone : raw) {
            if (milestone == null || milestone < 1 || milestone > barMax) {
                plugin.getLogger().warning("bar.milestones value " + milestone + " is outside 1-" + barMax
                    + " - ignored.");
                continue;
            }
            milestones.add(milestone);
        }

        if (milestones.isEmpty()) {
            plugin.getLogger().warning("bar.milestones is missing or has no usable value - falling back to"
                + " 10 and 20, clipped to bar.max (" + barMax + ").");
            for (int fallback : new int[] {10, 20}) {
                if (fallback <= barMax) {
                    milestones.add(fallback);
                }
            }
            // A bar too small for either default still needs one reward on it
            if (milestones.isEmpty()) {
                milestones.add(barMax);
            }
        }
        return List.copyOf(milestones);
    }

    // ====================================
    // A player can only earn from the TASKS_PER_DAY activities drawn for them,
    // so the daily ceiling is the worst draw they can get: the lowest
    // TASKS_PER_DAY daily-caps, itself capped by bar.daily-max. Seven days of
    // that is the weekly ceiling - if it is under the first milestone, an
    // unlucky week can never be claimed on.
    //
    // Too few capped activities to fill a draw means an uncapped one is always
    // in it, and then only bar.daily-max bounds the day. Fewer loaded
    // activities than TASKS_PER_DAY still bounds, since the draw is then all
    // of them.
    // ====================================
    private void warnIfBarUnreachable() {
        List<Integer> caps = new ArrayList<>();
        for (ActivityDef def : activities.values()) {
            if (def.dailyCap() > 0) {
                caps.add(def.dailyCap());
            }
        }

        String warning = unreachableWarning(caps, activities.size(), dailyMax, milestones.get(0));
        if (warning != null) {
            plugin.getLogger().warning(warning);
        }
    }

    // ====================================
    // The warning text, or null when the first reward is reachable. Pure so
    // both the arithmetic and what it says about it can be tested.
    //
    // The bound is named off what the caps alone would allow, so a sum that
    // lands exactly on bar.daily-max is not reported as bound by daily-max
    // alone - both numbers have to change to lift it. The task count is the
    // draw's real size, which is every loaded activity when fewer than
    // TASKS_PER_DAY are loaded.
    // ====================================
    static String unreachableWarning(List<Integer> dailyCaps, int activityCount, int dailyMax,
                                     int firstMilestone) {
        long capCeiling = dailyCeiling(dailyCaps, activityCount, Integer.MAX_VALUE);
        long dailyCeiling = Math.min(capCeiling, dailyMax);
        long weekly = dailyCeiling * 7;
        if (weekly >= firstMilestone) {
            return null;
        }

        String boundBy = capCeiling < dailyMax ? "per-activity daily-caps"
            : capCeiling > dailyMax ? "bar.daily-max"
            : "per-activity daily-caps and bar.daily-max";

        return "A day's " + Math.min(activityCount, PlayerData.TASKS_PER_DAY) + " drawn tasks are capped at "
            + dailyCeiling + " points, so " + weekly + " a week (bound by " + boundBy + ") - a player"
            + " cannot count on reaching the first reward at " + firstMilestone + ".";
    }

    // ====================================
    // The arithmetic above, pure so it can be tested: the daily-caps of the
    // capped activities (in any order), how many activities are loaded in
    // total, and bar.daily-max. Package-private for the test.
    // ====================================
    static long dailyCeiling(List<Integer> dailyCaps, int activityCount, int dailyMax) {
        List<Integer> caps = new ArrayList<>(dailyCaps);
        caps.sort(null);

        // Too few capped activities to fill a draw on their own: every draw
        // holds at least one uncapped activity, so only bar.daily-max bounds it
        if (caps.size() < PlayerData.TASKS_PER_DAY && caps.size() != activityCount) {
            return dailyMax;
        }

        long sum = 0;
        for (int i = 0; i < Math.min(PlayerData.TASKS_PER_DAY, caps.size()); i++) {
            sum += caps.get(i);
        }
        return Math.min(sum, dailyMax);
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

    public ActivityDef activity(String id) {
        return activities.get(id);
    }

    // The ids marked 'daily-guaranteed', in config order. Every one of them
    // is in every player's draw for the day.
    public List<String> guaranteed() {
        return guaranteedActivities;
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

    // ====================================
    // The activity fed by crafting <recipeId> at MMOItems station
    // <stationId>, if any. A '<station>/<recipe>' activity wins over a
    // whole-station one, so a craft only ever feeds a single activity.
    // ====================================
    public Optional<String> stationActivity(String stationId, String recipeId) {
        if (stationId == null || stationId.isBlank()) {
            return Optional.empty();
        }
        String station = normalizeStationPart(stationId);
        if (recipeId != null && !recipeId.isBlank()) {
            String specific = stationActivities.get(station + "/" + normalizeStationPart(recipeId));
            if (specific != null) {
                return Optional.of(specific);
            }
        }
        return Optional.ofNullable(stationActivities.get(station));
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

    public List<RewardEntry> rewardPool() {
        return rewardPool;
    }

    public String guiTitle() {
        return guiTitle;
    }

    public int barMax() {
        return barMax;
    }

    public int dailyMax() {
        return dailyMax;
    }

    public List<Integer> milestones() {
        return milestones;
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
