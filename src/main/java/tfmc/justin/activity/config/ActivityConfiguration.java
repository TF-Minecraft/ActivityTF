package tfmc.justin.activity.config;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import tfmc.justin.activity.hooks.TLibsItems;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.models.RewardEntry;

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
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Predicate;

public class ActivityConfiguration {

    private final JavaPlugin plugin;
    private final Messages messages;

    private volatile Map<String, ActivityDef> activities = new LinkedHashMap<>();

    private volatile Map<Material, String> craftActivities = new HashMap<>();

    private volatile List<String> guaranteedActivities = List.of();

    private volatile List<Map.Entry<String, String>> craftPaths = List.of();

    private volatile boolean itemPathsUsable;

    private String missingItemPathPlugins = "";

    private boolean pluginPathConfigured;

    private volatile boolean itemsAdderUsable;

    private boolean itemsAdderPathConfigured;

    private volatile Map<String, String> professionActivities = Map.of();

    private volatile Map<String, String> stationActivities = Map.of();

    private volatile DayOfWeek resetDay;
    private volatile int resetHour;

    private volatile Map<String, List<RewardEntry>> rewardPools = Map.of();

    private volatile int rewardMultiplier = 1;

    private volatile Map<Integer, RewardEntry> milestoneDrops = Map.of();

    private volatile Map<String, RewardEntry> dailyRewards = Map.of();

    public static final String DEFAULT_POOL = "pool";
    public static final RewardEntry DAILY_POOL = new RewardEntry(0, DEFAULT_POOL, List.of(), List.of());

    public static boolean isPoolName(String value) {
        String name = poolName(value);
        return name.equals(DEFAULT_POOL) || name.startsWith(DEFAULT_POOL + "_");
    }

    public static String poolName(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    public static RewardEntry poolRef(String name) {
        String pool = poolName(name);
        return pool.equals(DEFAULT_POOL) ? DAILY_POOL : new RewardEntry(0, pool, List.of(), List.of());
    }

    public static String referencedPool(RewardEntry entry) {
        return entry.weight() == 0 && entry.commands().isEmpty() && entry.items().isEmpty()
            ? entry.display()
            : null;
    }

    private String guiTitle;

    private volatile int barMax;
    private volatile int dailyMax;
    private volatile int voteShare;
    private volatile List<Integer> milestones = List.of();
    private volatile int barLength;

    private volatile int rerollsPerDay;
    private volatile int rerollMaxPoints;

    private String goalCompleteSound;
    private String barCompleteSound;

    private int saveIntervalMinutes;

    private volatile int clickCommandCooldownMillis = CLICK_COMMAND_COOLDOWN_DEFAULT;

    private volatile int clickCommandsPerSecond = CLICK_COMMANDS_PER_SECOND_DEFAULT;

    private volatile int clickCommandsPerClick = CLICK_COMMANDS_PER_CLICK_DEFAULT;

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
        itemsAdderUsable = Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");

        resetDay = parseDay(config.getString("reset.day", "MONDAY"));
        resetHour = Math.max(0, Math.min(23, config.getInt("reset.hour", 0)));

        pluginPathConfigured = false;
        itemsAdderPathConfigured = false;
        loadActivities(config.getConfigurationSection("activities"));

        rewardPools = loadRewardPools(config);
        rewardMultiplier = rewardMultiplier(config);

        barMax = Math.max(1, config.getInt("bar.max", 50));
        dailyMax = Math.max(1, config.getInt("bar.daily-max", 10));
        voteShare = parseVoteShare(config);
        warnIfVoteShareMisfits();
        milestones = loadMilestones(config.getIntegerList("bar.milestones"));
        milestoneDrops = loadMilestoneDrops(config);
        if (poolNeededButEmpty(rewardPool(), milestoneDrops, milestones)) {
            plugin.getLogger().warning("rewards.pool is empty or every entry in it was dropped - a milestone"
                + " can be reached but nothing can ever be claimed.");
        }
        dailyRewards = loadDailyRewards(config);

        String itemPathProblem = itemPathWarning(pluginPathConfigured || !craftPaths.isEmpty(),
            itemPathsUsable, missingItemPathPlugins);
        if (itemPathProblem != null) {
            plugin.getLogger().warning(itemPathProblem);
        }

        String itemsAdderProblem = itemsAdderWarning(itemsAdderPathConfigured, itemsAdderUsable);
        if (itemsAdderProblem != null) {
            plugin.getLogger().warning(itemsAdderProblem);
        }

        guiTitle = config.getString("gui.title", "&8Weekly Activity");

        barLength = barLength(config.getInt("bar.length", 40));

        rerollsPerDay = parseRerollsPerDay(config);
        rerollMaxPoints = parseRerollMaxPoints(config);

        goalCompleteSound = soundKey(config.getString("sounds.goal-complete", ""));
        barCompleteSound = soundKey(config.getString("sounds.bar-complete", ""));

        saveIntervalMinutes = Math.max(1, config.getInt("save-interval-minutes", 5));

        clickCommandCooldownMillis = clamped(config, CLICK_COMMAND_COOLDOWN_PATH,
            CLICK_COMMAND_COOLDOWN_DEFAULT, 50, 60_000);
        clickCommandsPerSecond = clamped(config, CLICK_COMMANDS_PER_SECOND_PATH,
            CLICK_COMMANDS_PER_SECOND_DEFAULT, 1, 200);
        clickCommandsPerClick = clamped(config, CLICK_COMMANDS_PER_CLICK_PATH,
            CLICK_COMMANDS_PER_CLICK_DEFAULT, 1, 50);

        afkMinutes = Math.max(0, config.getInt("playtime.afk-minutes", 5));

        if (config.contains("playtime.afk-minutes") && !activities.containsKey("playtime")) {
            plugin.getLogger().warning("playtime.afk-minutes is set but there is no 'playtime' activity"
                + " - no minute will ever be credited.");
        }

        warnIfBarUnreachable();
    }

    static final String REROLLS_PER_DAY_PATH = "reroll.per-day";
    static final String REROLL_MAX_POINTS_PATH = "reroll.max-points";

    public static final String REWARDS_PATH = "rewards";
    static final String REWARDS_MULTIPLIER_PATH = "rewards.multiplier";
    static final String REWARDS_POOL_PATH = REWARDS_PATH + "." + DEFAULT_POOL;
    static final String REWARDS_DROPS_PATH = "rewards.drops";
    static final String DAILY_REWARD_GROUPS_PATH = "daily-reward.groups";

    static final String CLICK_COMMANDS_KEY = "click-commands";

    static final String DESCRIPTION_KEY = "description";
    static final String CLICK_COMMAND_COOLDOWN_PATH = "click-command-cooldown-millis";
    static final String CLICK_COMMANDS_PER_SECOND_PATH = "click-commands-per-second";
    static final String CLICK_COMMANDS_PER_CLICK_PATH = "click-commands-per-click";

    static final int CLICK_COMMAND_COOLDOWN_DEFAULT = 1000;
    static final int CLICK_COMMANDS_PER_SECOND_DEFAULT = 20;
    static final int CLICK_COMMANDS_PER_CLICK_DEFAULT = 5;

    private int clamped(ConfigurationSection config, String path, int fallback, int min, int max) {
        Object raw = config.get(path);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number number)) {
            plugin.getLogger().warning(path + " is not a number ('"
                + Utils.safeForLog(String.valueOf(raw)) + "') - using " + fallback + ".");
            return fallback;
        }
        long value = number.longValue();
        if (value < min || value > max) {
            long clamp = Math.max(min, Math.min(max, value));
            plugin.getLogger().warning(path + " " + value
                + " is outside " + min + "-" + max + " - using " + clamp + ".");
            return (int) clamp;
        }
        return (int) value;
    }

    static int parseRerollsPerDay(ConfigurationSection config) {
        return Math.max(0, config.getInt(REROLLS_PER_DAY_PATH, 1));
    }

    static int parseRerollMaxPoints(ConfigurationSection config) {
        return Math.max(0, config.getInt(REROLL_MAX_POINTS_PATH, 1));
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
                ItemPath.isPluginPath(iconValue) || ItemPath.isItemsAdderPath(iconValue)
                    ? Material.PAPER
                    : material(iconValue, "activities." + id + ".material"),
                iconPath,
                Math.max(1, wholeNumber(entry, id, "every", 1)),
                points,
                dailyCap,
                clickCommands(entry, id),
                description(entry, id)
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

        if (guaranteed.size() > PlayerData.TASKS_PER_DAY) {
            plugin.getLogger().warning(guaranteed.size() + " activities are marked daily-guaranteed but only "
                + PlayerData.TASKS_PER_DAY + " tasks are handed out a day - every draw is "
                + PlayerData.TASKS_PER_DAY + " of them picked at random and no other activity can"
                + " ever be drawn.");
        }

        activities = loaded;
        craftActivities = crafts;
        craftPaths = List.copyOf(paths);
        professionActivities = professions;
        stationActivities = stations;
        guaranteedActivities = List.copyOf(guaranteed);
    }

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

    private static String stationKey(String station) {
        int slash = station.indexOf('/');
        if (slash < 0) {
            return normalizeStationPart(station);
        }
        if (station.indexOf('/', slash + 1) >= 0) {
            return "";
        }
        return normalizeStationPart(station.substring(0, slash))
            + "/" + normalizeStationPart(station.substring(slash + 1));
    }

    private static String normalizeStationPart(String part) {
        return part.trim().toLowerCase(Locale.ROOT);
    }

    private void loadCraft(Map<Material, String> crafts, List<Map.Entry<String, String>> paths,
                           String name, String id) {
        String problem = registerCraft(crafts, paths, name, id, ActivityConfiguration::craftableItem);
        if (problem != null) {
            plugin.getLogger().warning(problem);
        }
    }

    static String registerCraft(Map<Material, String> crafts, List<Map.Entry<String, String>> paths,
                                String name, String id, Predicate<Material> craftable) {
        if (name == null || name.isBlank()) {
            return null;
        }

        String safe = Utils.safeForLog(name);
        String safeId = Utils.safeForLog(id);

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

        if (ItemPath.isItemsAdderPath(name)) {
            return "ItemsAdder item path '" + safe + "' at activities." + safeId
                + ".craft - ia.<namespace:id> is not supported for craft keys (only for 'material:'"
                + " icons and reward items) - nothing will ever feed that activity.";
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

    private int wholeNumber(ConfigurationSection entry, String id, String key, int fallback) {
        if (entry.contains(key) && !entry.isInt(key)) {
            plugin.getLogger().warning("activities." + id + "." + key + " is not a whole number ('"
                + entry.get(key) + "') - using " + fallback + ".");
            return fallback;
        }
        return entry.getInt(key, fallback);
    }

    private boolean flag(ConfigurationSection entry, String id, String key, boolean fallback) {
        if (entry.contains(key) && !entry.isBoolean(key)) {
            plugin.getLogger().warning("activities." + id + "." + key + " is not true or false ('"
                + entry.get(key) + "') - using " + fallback + ".");
            return fallback;
        }
        return entry.getBoolean(key, fallback);
    }

    private List<String> clickCommands(ConfigurationSection entry, String id) {
        Object raw = entry.get(CLICK_COMMANDS_KEY);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            plugin.getLogger().warning("activities." + id + "." + CLICK_COMMANDS_KEY + " is not a list of"
                + " commands ('" + Utils.safeForLog(String.valueOf(raw)) + "') - no command will ever be run"
                + " for this activity.");
            return List.of();
        }
        return nonBlank(list);
    }

    private List<String> description(ConfigurationSection entry, String id) {
        Object raw = entry.get(DESCRIPTION_KEY);
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return nonBlank(list);
        }
        if (raw instanceof String text) {
            return text.isBlank() ? List.of() : List.of(text);
        }
        plugin.getLogger().warning("activities." + id + "." + DESCRIPTION_KEY + " is neither a string nor a"
            + " list of lines ('" + Utils.safeForLog(String.valueOf(raw)) + "') - this activity shows no"
            + " description.");
        return List.of();
    }

    private static List<String> nonBlank(List<?> list) {
        List<String> kept = new ArrayList<>();
        for (Object value : list) {
            if (value != null && !String.valueOf(value).isBlank()) {
                kept.add(String.valueOf(value));
            }
        }
        return List.copyOf(kept);
    }

    private int barLength(int length) {
        if (length < 1 || length > 100) {
            int clamped = Math.max(1, Math.min(100, length));
            plugin.getLogger().warning("bar.length " + length + " is outside 1-100 - using " + clamped + ".");
            return clamped;
        }
        return length;
    }

    private static boolean set(ConfigurationSection config, String path) {
        return config.contains(path, true);
    }

    private static ConfigurationSection section(ConfigurationSection config, String path) {
        return set(config, path) ? config.getConfigurationSection(path) : null;
    }

    private static List<String> keys(ConfigurationSection section) {
        List<String> keys = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            if (set(section, key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private Map<String, List<RewardEntry>> loadRewardPools(ConfigurationSection config) {
        ConfigurationSection rewards = section(config, REWARDS_PATH);
        if (rewards == null) {
            return Map.of();
        }
        Map<String, List<RewardEntry>> pools = new LinkedHashMap<>();
        for (String key : keys(rewards)) {
            if (isPoolName(key)) {
                pools.put(poolName(key), loadRewardPool(config, REWARDS_PATH + "." + key));
            }
        }
        ConfigurationSection named = section(config, "rewards.pools");
        if (named != null) {
            for (String key : keys(named)) {
                if (!isPoolName(key) || poolName(key).equals(DEFAULT_POOL)) {
                    plugin.getLogger().warning("rewards.pools." + Utils.safeForLog(key)
                        + " must use a pool_<name> name - ignored.");
                    continue;
                }
                pools.put(poolName(key), loadRewardPool(config, "rewards.pools." + key));
            }
        }
        return Map.copyOf(pools);
    }

    private List<RewardEntry> loadRewardPool(ConfigurationSection config, String path) {
        List<RewardEntry> pool = new ArrayList<>();
        if (!set(config, path)) {
            return List.of();
        }
        int index = 0;
        for (Map<?, ?> entry : config.getMapList(path)) {
            String where = path + "[" + index++ + "]";

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
            List<RewardEntry.Item> items = loadRewardItems(entry.get("items"), where);

            if (commands.isEmpty() && items.isEmpty()) {
                plugin.getLogger().warning(where + " has no commands and no items - skipped, since drawing it"
                    + " would pay the player nothing.");
                continue;
            }

            Object display = entry.get("display");
            pool.add(new RewardEntry(Math.min(weight, 1_000_000),
                display == null ? "" : String.valueOf(display), List.copyOf(commands), List.copyOf(items)));
        }
        return List.copyOf(pool);
    }

    private List<RewardEntry.Item> loadRewardItems(Object raw, String where) {
        List<RewardEntry.Item> items = new ArrayList<>();
        if (raw == null) {
            return items;
        }
        if (!(raw instanceof List<?> list)) {
            plugin.getLogger().warning(where + ".items is not a list of 'item:'/'amount:' blocks ('"
                + Utils.safeForLog(String.valueOf(raw)) + "') - no item is handed over for this entry.");
            return items;
        }
        int index = 0;
        for (Object element : list) {
            String at = where + ".items[" + index++ + "]";
            if (!(element instanceof Map<?, ?> map)) {
                plugin.getLogger().warning(at + " is not an 'item:'/'amount:' block - ignored.");
                continue;
            }
            Object path = map.get("item");
            if (path == null || String.valueOf(path).isBlank()) {
                plugin.getLogger().warning(at + " has no 'item:' path - ignored.");
                continue;
            }
            String value = String.valueOf(path).strip();
            if (!validItemPath(value, at)) {
                continue;
            }
            items.add(new RewardEntry.Item(value, rewardAmount(map.get("amount"), at)));
        }
        return items;
    }

    private boolean validItemPath(String value, String at) {
        if (ItemPath.isUnsupportedPath(value)) {
            plugin.getLogger().warning("Unsupported item path '" + Utils.safeForLog(value) + "' at " + at
                + " - only bare Material names, v.<material>, m.<type>.<id> and ia.<namespace:id>"
                + " are supported - ignored.");
            return false;
        }
        if (ItemPath.isItemsAdderPath(value)) {
            if (ItemPath.itemsAdderId(value) == null) {
                plugin.getLogger().warning("Malformed item path '" + Utils.safeForLog(value) + "' at " + at
                    + " - expected ia.<namespace:id> - ignored.");
                return false;
            }
            itemsAdderPathConfigured = true;
            return true;
        }
        if (ItemPath.isPluginPath(value)) {
            if (ItemPath.pluginPath(value) == null) {
                plugin.getLogger().warning("Malformed item path '" + Utils.safeForLog(value) + "' at " + at
                    + " - expected m.<type>.<id> - ignored.");
                return false;
            }
            pluginPathConfigured = true;
            return true;
        }
        if (ItemPath.material(value) == null) {
            plugin.getLogger().warning("Unknown material '" + Utils.safeForLog(value) + "' at " + at
                + " - ignored.");
            return false;
        }
        return true;
    }

    private int rewardAmount(Object raw, String at) {
        if (raw == null) {
            return 1;
        }
        if (!(raw instanceof Number number)) {
            plugin.getLogger().warning(at + " has a non-numeric amount '" + Utils.safeForLog(String.valueOf(raw))
                + "' - using 1.");
            return 1;
        }
        long amount = number.longValue();
        if (number.doubleValue() != amount) {
            plugin.getLogger().warning(at + " amount '" + Utils.safeForLog(String.valueOf(raw))
                + "' is not a whole number - using 1.");
            return 1;
        }
        if (amount < 1 || amount > 64) {
            long clamped = Math.max(1, Math.min(64, amount));
            plugin.getLogger().warning(at + " amount " + amount + " is outside 1-64 - using " + clamped + ".");
            return (int) clamped;
        }
        return (int) amount;
    }

    private int rewardMultiplier(ConfigurationSection config) {
        Object raw = config.get(REWARDS_MULTIPLIER_PATH);
        if (raw == null) {
            return 1;
        }
        if (!(raw instanceof Number number)) {
            plugin.getLogger().warning(REWARDS_MULTIPLIER_PATH + " is not a number ('"
                + Utils.safeForLog(String.valueOf(raw)) + "') - using 1.");
            return 1;
        }
        long value = number.longValue();
        if (number.doubleValue() != value) {
            plugin.getLogger().warning(REWARDS_MULTIPLIER_PATH + " '" + Utils.safeForLog(String.valueOf(raw))
                + "' is not a whole number - using 1.");
            return 1;
        }
        if (value < 1 || value > 64) {
            long clamped = Math.max(1, Math.min(64, value));
            plugin.getLogger().warning(REWARDS_MULTIPLIER_PATH + " " + value + " is outside 1-64 - using "
                + clamped + ".");
            return (int) clamped;
        }
        return (int) value;
    }

    private Map<Integer, RewardEntry> loadMilestoneDrops(ConfigurationSection config) {
        ConfigurationSection section = section(config, REWARDS_DROPS_PATH);
        if (section == null) {
            if (set(config, REWARDS_DROPS_PATH)) {
                plugin.getLogger().warning(REWARDS_DROPS_PATH + " is not a section of drop_<N> keys - ignored,"
                    + " every milestone draws from the pool.");
            }
            return Map.of();
        }
        Map<Integer, RewardEntry> drops = new HashMap<>();
        for (String key : keys(section)) {
            String at = REWARDS_DROPS_PATH + "." + key;
            String number = key.startsWith("drop_") ? key.substring(5) : "";
            if (!number.matches("[1-9][0-9]*")) {
                plugin.getLogger().warning(at + " is not a drop_<N> key (N = 1, 2, ...) - ignored.");
                continue;
            }
            if (number.length() > 9 || Integer.parseInt(number) > milestones.size()) {
                plugin.getLogger().warning(at + " is past the last of the " + milestones.size()
                    + " bar.milestones - ignored.");
                continue;
            }
            int milestone = milestones.get(Integer.parseInt(number) - 1);

            Object raw = section.get(key);
            if (raw instanceof String string && isPoolName(string)) {
                if (poolName(string).equals(DEFAULT_POOL)) {
                    continue;
                }
                drops.put(milestone, warnedPoolRef(at, string, "milestone " + milestone
                    + " pays nothing and stays claimable"));
                continue;
            }
            RewardEntry drop = fixedItem(at, raw, "milestone " + milestone + " draws from the pool");
            if (drop != null) {
                drops.put(milestone, drop);
            }
        }
        return Map.copyOf(drops);
    }

    private RewardEntry warnedPoolRef(String at, String value, String otherwise) {
        String pool = poolName(value);
        if (rewardPool(pool).isEmpty()) {
            plugin.getLogger().warning(at + " draws from '" + Utils.safeForLog(pool) + "', which is not a pool"
                + " under rewards.pools or rewards, or has no usable entry - " + otherwise + ".");
        }
        return poolRef(pool);
    }

    private RewardEntry fixedItem(String at, Object raw, String otherwise) {
        if (raw instanceof ConfigurationSection block) {
            return fixedItemBlock(at, block, otherwise);
        }
        return fixedItemString(at, String.valueOf(raw).strip(), otherwise);
    }

    private RewardEntry fixedItemString(String at, String value, String otherwise) {
        String[] parts = value.split("\\s+");
        int amount = parts.length == 1 ? 1
            : parts.length == 2 && parts[1].matches("[0-9]{1,2}") ? Integer.parseInt(parts[1]) : 0;
        if (amount < 1 || amount > 64) {
            plugin.getLogger().warning(at + " '" + Utils.safeForLog(value) + "' is not '<item path>' or"
                + " '<item path> <amount 1-64>' - " + otherwise + ".");
            return null;
        }
        if (!validItemPath(parts[0], at)) {
            plugin.getLogger().warning(at + " has no usable item path - " + otherwise + ".");
            return null;
        }
        return new RewardEntry(1, itemName(parts[0]), List.of(), List.of(new RewardEntry.Item(parts[0], amount)));
    }

    private RewardEntry fixedItemBlock(String at, ConfigurationSection block, String otherwise) {
        String path = block.getString("item");
        if (path == null || path.isBlank()) {
            plugin.getLogger().warning(at + " has no 'item:' path - " + otherwise + ".");
            return null;
        }
        path = path.strip();

        Object amountRaw = block.get("amount");
        String amountText = amountRaw == null ? "" : String.valueOf(amountRaw).strip();
        int amount = amountRaw == null ? 1 : amountText.matches("[0-9]{1,2}") ? Integer.parseInt(amountText) : 0;
        if (amount < 1 || amount > 64) {
            plugin.getLogger().warning(at + ".amount '" + Utils.safeForLog(amountText) + "' is not 1-64 - "
                + otherwise + ".");
            return null;
        }

        if (!validItemPath(path, at)) {
            plugin.getLogger().warning(at + " has no usable item path - " + otherwise + ".");
            return null;
        }

        List<String> unknown = new ArrayList<>();
        for (String key : block.getKeys(false)) {
            if (!key.equals("item") && !key.equals("amount")) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            plugin.getLogger().warning(at + " has unknown key(s) " + Utils.safeForLog(String.join(", ", unknown))
                + " - ignored.");
        }

        return new RewardEntry(1, itemName(path), List.of(), List.of(new RewardEntry.Item(path, amount)));
    }

    private Map<String, RewardEntry> loadDailyRewards(ConfigurationSection config) {
        ConfigurationSection section = section(config, DAILY_REWARD_GROUPS_PATH);
        if (section == null) {
            if (set(config, DAILY_REWARD_GROUPS_PATH)) {
                plugin.getLogger().warning(DAILY_REWARD_GROUPS_PATH + " is not a section of <group>: <item>"
                    + " lines - ignored, no daily reward is paid.");
            }
            return Map.of();
        }
        Map<String, RewardEntry> groups = new LinkedHashMap<>();
        for (String group : keys(section)) {
            Object raw = section.get(group);
            if (raw instanceof String string && isPoolName(string)) {
                groups.put(group, poolName(string).equals(DEFAULT_POOL)
                    ? DAILY_POOL
                    : warnedPoolRef(DAILY_REWARD_GROUPS_PATH + "." + Utils.safeForLog(group), string,
                        "group " + Utils.safeForLog(group) + " gets no daily reward"));
                continue;
            }
            RewardEntry reward = fixedItem(DAILY_REWARD_GROUPS_PATH + "." + Utils.safeForLog(group),
                raw,
                "group " + Utils.safeForLog(group) + " gets no daily reward");
            if (reward != null) {
                groups.put(group, reward);
            }
        }
        if (rewardPool().isEmpty() && groups.containsValue(DAILY_POOL)) {
            plugin.getLogger().warning("rewards.pool is empty or every entry in it was dropped - a "
                + DAILY_REWARD_GROUPS_PATH + " group set to 'pool' can never be paid its daily reward.");
        }
        return Collections.unmodifiableMap(groups);
    }

    static boolean poolNeededButEmpty(List<RewardEntry> pool, Map<Integer, RewardEntry> drops,
                                      List<Integer> milestones) {
        return pool.isEmpty() && ActivityManager.neededPools(milestones, drops).contains(DEFAULT_POOL);
    }

    static String itemName(String path) {
        String last = path.substring(Math.max(path.lastIndexOf('.'), path.lastIndexOf(':')) + 1);
        StringBuilder name = new StringBuilder();
        for (String word : last.toLowerCase(Locale.ROOT).split("_")) {
            if (!word.isEmpty()) {
                name.append(name.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1));
            }
        }
        return name.toString();
    }

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
            if (milestones.isEmpty()) {
                milestones.add(barMax);
            }
        }
        return List.copyOf(milestones);
    }

    private void warnIfVoteShareMisfits() {
        if (voteShare == 0) {
            return;
        }
        ActivityDef vote = activities.get("vote");
        if (vote == null) {
            plugin.getLogger().warning("bar.vote-share is " + voteShare + " but there is no 'vote' activity"
                + " - the share is not applied.");
            return;
        }
        if (!guaranteedActivities.contains("vote")) {
            plugin.getLogger().warning("bar.vote-share is " + voteShare + " but 'vote' is not daily-guaranteed"
                + " - on a day it is not drawn, the reserved share cannot be earned.");
        }
        int reserved = dailyMax - nonVoteDailyMax();
        if (vote.dailyCap() > 0 && vote.capPoints() < reserved) {
            plugin.getLogger().warning("activities.vote.daily-cap " + vote.dailyCap() + " (" + vote.capPoints()
                + " points) is below the "
                + reserved + " points bar.vote-share keeps for voting - the rest of that share is never earned.");
        }
    }

    private void warnIfBarUnreachable() {
        List<Integer> caps = new ArrayList<>();
        for (ActivityDef def : activities.values()) {
            if (def.dailyCap() > 0) {
                caps.add(def.capPoints());
            }
        }

        boolean shared = voteShare > 0 && activities.containsKey("vote");
        String warning = unreachableWarning(caps, activities.size(), shared ? nonVoteDailyMax() : dailyMax,
            milestones.get(0));
        if (warning != null) {
            plugin.getLogger().warning(shared ? warning + " This is without voting: bar.vote-share keeps "
                + (dailyMax - nonVoteDailyMax()) + " of bar.daily-max for it." : warning);
        }
    }

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

    static long dailyCeiling(List<Integer> dailyCaps, int activityCount, int dailyMax) {
        List<Integer> caps = new ArrayList<>(dailyCaps);
        caps.sort(null);

        if (caps.size() < PlayerData.TASKS_PER_DAY && caps.size() != activityCount) {
            return dailyMax;
        }

        long sum = 0;
        for (int i = 0; i < Math.min(PlayerData.TASKS_PER_DAY, caps.size()); i++) {
            sum += caps.get(i);
        }
        return Math.min(sum, dailyMax);
    }

    private String iconPath(String name, String path) {
        if (ItemPath.isItemsAdderPath(name)) {
            String id = ItemPath.itemsAdderId(name);
            if (id == null) {
                plugin.getLogger().warning("Malformed item path '" + Utils.safeForLog(name) + "' at " + path
                    + " - expected ia.<namespace:id> - using PAPER.");
                return null;
            }
            itemsAdderPathConfigured = true;
            return "ia." + id;
        }
        if (!ItemPath.isPluginPath(name)) {
            return null;
        }
        String resolved = ItemPath.pluginPath(name);
        if (resolved == null) {
            plugin.getLogger().warning("Malformed item path '" + Utils.safeForLog(name) + "' at " + path
                + " - expected m.<type>.<id> - using PAPER.");
            return null;
        }
        pluginPathConfigured = true;
        return itemPathsUsable ? resolved : null;
    }

    private Material material(String name, String path) {
        if (ItemPath.isUnsupportedPath(name)) {
            plugin.getLogger().warning("Unsupported item path '" + Utils.safeForLog(name) + "' at " + path
                + " - only bare Material names, v.<material>, m.<type>.<id> and ia.<namespace:id>"
                + " are supported - using PAPER.");
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

    public List<String> guaranteed() {
        return guaranteedActivities;
    }

    public String craftActivity(ItemStack crafted) {
        String id = craftActivities.get(crafted.getType());
        if (id != null || craftPaths.isEmpty() || !itemPathsUsable) {
            return id;
        }
        return TLibsItems.match(crafted, craftPaths);
    }

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

    public String professionActivity(String professionId) {
        return professionActivity(professionActivities, professionId);
    }

    static String professionActivity(Map<String, String> professions, String professionId) {
        if (professionId == null || professionId.isBlank()) {
            return null;
        }
        return professions.get(normalizeProfessionId(professionId));
    }

    static String normalizeProfessionId(String professionId) {
        return professionId.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
    }

    public int afkMinutes() {
        return afkMinutes;
    }

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
        return rewardPool(DEFAULT_POOL);
    }

    public List<RewardEntry> rewardPool(String name) {
        return rewardPools.getOrDefault(poolName(name), List.of());
    }

    public int rewardMultiplier() {
        return rewardMultiplier;
    }

    public Map<Integer, RewardEntry> milestoneDrops() {
        return milestoneDrops;
    }

    public Map<String, RewardEntry> dailyRewards() {
        return dailyRewards;
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

    public int nonVoteDailyMax() {
        return nonVoteDailyMax(dailyMax, voteShare);
    }

    static int nonVoteDailyMax(int dailyMax, int voteShare) {
        return dailyMax * (100 - voteShare) / 100;
    }

    int parseVoteShare(ConfigurationSection config) {
        return clamped(config, "bar.vote-share", 50, 0, 100);
    }

    public List<Integer> milestones() {
        return milestones;
    }

    public int barLength() {
        return barLength;
    }

    public int rerollsPerDay() {
        return rerollsPerDay;
    }

    public int rerollMaxPoints() {
        return rerollMaxPoints;
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

    public int clickCommandCooldownMillis() {
        return clickCommandCooldownMillis;
    }

    public int clickCommandsPerSecond() {
        return clickCommandsPerSecond;
    }

    public int clickCommandsPerClick() {
        return clickCommandsPerClick;
    }

    public boolean itemPathsUsable() {
        return itemPathsUsable;
    }

    public boolean itemsAdderUsable() {
        return itemsAdderUsable;
    }

    static String itemPathWarning(boolean configured, boolean usable, String missing) {
        if (!configured || usable) {
            return null;
        }
        return "config.yml uses m.<type>.<id> item paths but " + missing
            + (missing.contains(",") ? " are" : " is") + " not enabled - those icons"
            + " fall back to PAPER, those crafts are never credited, and any reward item on such a path"
            + " hands over nothing and leaves its milestone unclaimed.";
    }

    static String itemsAdderWarning(boolean configured, boolean usable) {
        if (!configured || usable) {
            return null;
        }
        return "config.yml uses ia.<namespace:id> item paths but ItemsAdder is not enabled -"
            + " those icons fall back to PAPER, and any reward item on such a path hands over"
            + " nothing and leaves its milestone unclaimed.";
    }
}
