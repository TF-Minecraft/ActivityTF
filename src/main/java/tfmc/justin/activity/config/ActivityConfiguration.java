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

    // Whether an ia.<namespace:id> path can be resolved right now. Deliberately
    // its own flag rather than another entry in the TLibs/MMOItems/MythicLib
    // list above: those three are what an m. path is built from, ItemsAdder has
    // nothing to do with them, and folding it in would stop every m. path from
    // resolving on a server that simply does not run ItemsAdder.
    private volatile boolean itemsAdderUsable;

    // Set while parsing when any well-formed ia.<namespace:id> value is seen,
    // on an icon or on a reward item
    private boolean itemsAdderPathConfigured;

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

    // What every 'items:' amount is multiplied by at payout. Read fresh on
    // every handover rather than folded into the pool at load, so a reload
    // changes what the next claim pays.
    private volatile int rewardMultiplier = 1;

    // rewards.drops: the fixed reward of a milestone, keyed by its point
    // total. A milestone with no entry here draws from the pool.
    private volatile Map<Integer, RewardEntry> milestoneDrops = Map.of();

    // daily-reward.groups: group name -> the item it pays, in config order
    private volatile Map<String, RewardEntry> dailyRewards = Map.of();

    private String guiTitle;

    private volatile int barMax;
    private volatile int dailyMax;
    // Ascending, deduped, every value inside 1..barMax. Never empty.
    private volatile List<Integer> milestones = List.of();
    private volatile int barLength;

    // 0 disables the reroll button for everyone
    private volatile int rerollsPerDay;
    // Highest dailyPoints a player may still reroll at. 0 means only before
    // anything has been earned today
    private volatile int rerollMaxPoints;

    private String goalCompleteSound;
    private String barCompleteSound;

    private int saveIntervalMinutes;

    // ====================================
    // Shortest gap between two runs of an activity's 'click-commands' for one
    // player. The GUI is the only path where a click dispatches a console
    // command, so this is what keeps a held mouse button or a macro from
    // dispatching at click rate.
    // volatile for the same reason as the fields above it: /activity reload
    // rewrites it on the main thread.
    // ====================================
    private volatile int clickCommandCooldownMillis = CLICK_COMMAND_COOLDOWN_DEFAULT;

    // ====================================
    // The two limits the per-player cooldown cannot give: how many click
    // dispatches the whole server may do in a second (the cooldown is per
    // player, so a hundred players holding a mouse button is a hundred
    // console dispatches a second on the main thread), and how many commands
    // one click may dispatch (the cooldown counts a click, not a command, so
    // a 20-entry 'click-commands' list multiplies the per-click cost).
    // volatile for the same reason as the field above.
    // ====================================
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

        // Reset here rather than in loadActivities: the one warning about m.
        // paths is logged after the section is read
        pluginPathConfigured = false;
        itemsAdderPathConfigured = false;
        loadActivities(config.getConfigurationSection("activities"));

        rewardPool = loadRewardPool(config);
        rewardMultiplier = rewardMultiplier(config);

        barMax = Math.max(1, config.getInt("bar.max", 50));
        dailyMax = Math.max(1, config.getInt("bar.daily-max", 10));
        // After barMax: every milestone is validated against it
        milestones = loadMilestones(config.getIntegerList("bar.milestones"));
        // After milestones: drop_N names the Nth of them. Before the path
        // warnings below: a drop's m. or ia. path counts towards them.
        milestoneDrops = loadMilestoneDrops(config);
        if (poolNeededButEmpty(rewardPool, milestoneDrops, milestones)) {
            plugin.getLogger().warning("rewards.pool is empty or every entry in it was dropped - a milestone"
                + " can be reached but nothing can ever be claimed.");
        }
        // Before the path warnings below, for the same reason as the drops
        dailyRewards = loadDailyRewards(config);

        // ====================================
        // One line for the whole file, and only when config.yml actually asks
        // for an m. path: the icons and the craft keys all fail for the same
        // reason, and repeating it per entry buried the rest of the startup log.
        // ====================================
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
    // The two reroll knobs, parsed apart from load() so a headless test can
    // feed them a value and catch a typo in the path: the shipped defaults
    // are identical to the getInt fallbacks, so nothing else here would.
    // The paths are constants for the same reason - DefaultResourcesTest
    // asserts the shipped file against these exact strings.
    // ====================================
    static final String REROLLS_PER_DAY_PATH = "reroll.per-day";
    static final String REROLL_MAX_POINTS_PATH = "reroll.max-points";

    // The reward keys, constants for the same reason: the shipped multiplier
    // is 1, which is also the fallback, so a typo in either path would leave
    // every claim paying 1x with nothing to show for it.
    static final String REWARDS_MULTIPLIER_PATH = "rewards.multiplier";
    static final String REWARDS_POOL_PATH = "rewards.pool";
    static final String REWARDS_DROPS_PATH = "rewards.drops";
    static final String DAILY_REWARD_GROUPS_PATH = "daily-reward.groups";

    // The per-activity key and the rate limit that guards it. A path constant
    // for the same reason as the ones above: DefaultResourcesTest asserts the
    // shipped file against this exact string.
    static final String CLICK_COMMANDS_KEY = "click-commands";

    // The optional per-activity blurb, a constant for the same reason:
    // DefaultResourcesTest asserts the shipped file against this exact string.
    static final String DESCRIPTION_KEY = "description";
    static final String CLICK_COMMAND_COOLDOWN_PATH = "click-command-cooldown-millis";
    static final String CLICK_COMMANDS_PER_SECOND_PATH = "click-commands-per-second";
    static final String CLICK_COMMANDS_PER_CLICK_PATH = "click-commands-per-click";

    // ====================================
    // The defaults, named once each: they are both the field initialiser and
    // what an absent or unparseable value falls back to, and writing the
    // number in three places is how "absent" and "unreadable" end up meaning
    // two different things.
    // ====================================
    static final int CLICK_COMMAND_COOLDOWN_DEFAULT = 1000;
    static final int CLICK_COMMANDS_PER_SECOND_DEFAULT = 20;
    static final int CLICK_COMMANDS_PER_CLICK_DEFAULT = 5;

    // ====================================
    // The three click-command knobs, parsed the same way. None of them has an
    // "off" value the reroll knobs have: 0 would let a held mouse button
    // dispatch console commands at click rate, so each has a floor above
    // zero, and a ceiling past which the value reads as a typo rather than a
    // setting. A non-number is named and the default used, the way
    // rewardMultiplier() names one. Takes the section, not the value, so a
    // headless test can catch a typo in the path.
    // ====================================
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

    // 0 turns rerolling off entirely, so like playtime.afk-minutes this clamp
    // has no lower bound of 1
    static int parseRerollsPerDay(ConfigurationSection config) {
        return Math.max(0, config.getInt(REROLLS_PER_DAY_PATH, 1));
    }

    // 0 is a meaningful setting here too - it allows a reroll only while
    // nothing has been earned today - so a negative clamps down to it
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
                // Still PAPER behind a path: what the GUI shows if TLibs is
                // gone or the path stops resolving. iconPath() has already
                // reported a path that could not be used, so material() -
                // which would call it an unknown material - is skipped.
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

        // ====================================
        // Deliberately not supported, and said so in its own words rather than
        // through the generic line below.
        //
        // Every other key resolves a path into an item; a craft key has to do
        // the opposite - take the crafted ItemStack and find the path it was
        // built from - which for ItemsAdder means a CustomStack.byItemStack()
        // reflection call on every craft the Material map did not answer. That
        // is a second reverse-matching mechanism, on the hot path of a craft
        // event, for something nobody has asked for: the request was for icons
        // and reward items. Say plainly that it is the craft key specifically,
        // so an admin does not read it as "ia. paths do not work at all".
        // ====================================
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

    // ====================================
    // Optional per-activity console commands, run when the player clicks an
    // already-revealed task. A single 'click-commands: sudo %player% votelist'
    // is the natural typo and would otherwise be dropped without a word - the
    // same bug loadRewardItems refuses to have.
    // ====================================
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

    // ====================================
    // Optional per-activity blurb, shown at the top of the task's lore once
    // the task is revealed. A two-line description wants a list and a one-line
    // one wants a plain string, so both are accepted; anything else (a number,
    // a nested section) is named and ignored rather than silently dropped.
    // ====================================
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

    // Every blank or missing entry dropped, so neither a stray '- ""' nor a
    // null from YAML reaches the GUI or the dispatcher
    private static List<String> nonBlank(List<?> list) {
        List<String> kept = new ArrayList<>();
        for (Object value : list) {
            if (value != null && !String.valueOf(value).isBlank()) {
                kept.add(String.valueOf(value));
            }
        }
        return List.copyOf(kept);
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
        for (Map<?, ?> entry : config.getMapList(REWARDS_POOL_PATH)) {
            String where = REWARDS_POOL_PATH + "[" + index++ + "]";

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

    // ====================================
    // The items one pool entry hands over. A path that cannot possibly work -
    // an unknown material, another plugin's path syntax, a malformed
    // m.<type>.<id> - is dropped here with a warning rather than kept to fail
    // at payout, because a broken path at payout costs the player a click and
    // a 'reward-failed'. A well formed m. path is kept even when TLibs is not
    // enabled right now: nothing here can tell a deleted MMOItems id from one
    // that resolves fine once the server has all three plugins, so that one is
    // left to fail loudly at payout instead.
    // ====================================
    private List<RewardEntry.Item> loadRewardItems(Object raw, String where) {
        List<RewardEntry.Item> items = new ArrayList<>();
        if (raw == null) {
            return items;
        }
        // A single 'items: DIAMOND' or 'items: {item: DIAMOND}' is the natural
        // typo, and silently paying nothing for it is exactly what every other
        // malformed key here refuses to do
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

    // The forms ItemPath accepts, warned about the way material() and
    // iconPath() warn - except that a reward item has no safe default, so a
    // value that is none of them is dropped instead of falling back.
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
            // Counts as "the admin asked for ItemsAdder items", so load() warns
            // once for the file when ItemsAdder is not enabled. Kept as written:
            // resolveRewardItem normalizes it when the payout happens, and a
            // warning that quotes config.yml back is worth more than one that
            // quotes something the admin never typed.
            itemsAdderPathConfigured = true;
            return true;
        }
        if (ItemPath.isPluginPath(value)) {
            if (ItemPath.pluginPath(value) == null) {
                plugin.getLogger().warning("Malformed item path '" + Utils.safeForLog(value) + "' at " + at
                    + " - expected m.<type>.<id> - ignored.");
                return false;
            }
            // Counts as "the admin asked for item paths", so load() warns once
            // for the file when TLibs/MMOItems/MythicLib are not all enabled
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

    // 64 is a vanilla stack and the most one 'items:' line may hand over;
    // anything else (absent, negative, text) falls back to 1 the way every
    // other numeric key here clamps rather than skips.
    private int rewardAmount(Object raw, String at) {
        if (raw == null) {
            return 1;
        }
        if (!(raw instanceof Number number)) {
            plugin.getLogger().warning(at + " has a non-numeric amount '" + Utils.safeForLog(String.valueOf(raw))
                + "' - using 1.");
            return 1;
        }
        // Tested as a long before narrowing: intValue() on 4294967298 is 2,
        // which would pass the range test having asked for something else
        // entirely. A fractional amount is a different mistake and falls back
        // rather than silently rounding.
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

    // ====================================
    // rewards.multiplier: what every 'items:' amount is multiplied by at
    // payout. 1-64 - 0 or negative would mean "hand nothing over", which is
    // never what an admin meant (the way to pay nothing is to drop the entry),
    // and 64 x a 64 amount is already 64 full stacks off one 'items:' line.
    // Console 'give' commands are opaque strings and are never multiplied.
    //
    // Read raw rather than through getInt, and refused the same way an
    // 'amount:' is: getInt turns 2.9 into 2 in silence and reports a
    // non-numeric value as "0 is outside 1-64", naming a number the admin
    // never wrote. Takes the section, not the value, so a headless test can
    // catch a typo in the path - the shipped value is 1, which is also the
    // fallback, so nothing else would.
    // ====================================
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
        // Long before narrowing, and a fraction refused rather than rounded,
        // exactly as rewardAmount does it
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

    // ====================================
    // rewards.drops: 'drop_N: pool' or 'drop_N: <item path> [amount]' for the
    // Nth milestone. Anything that does not parse is named and left to the
    // pool, so a typo never pays less than the config did before drops
    // existed. The path is checked the way a pool item's is - a well formed
    // m. or ia. path is kept even when its plugin is not up yet and is left
    // to resolve, or fail and stay claimable, at payout.
    // ====================================
    private Map<Integer, RewardEntry> loadMilestoneDrops(ConfigurationSection config) {
        ConfigurationSection section = config.getConfigurationSection(REWARDS_DROPS_PATH);
        if (section == null) {
            if (config.contains(REWARDS_DROPS_PATH)) {
                plugin.getLogger().warning(REWARDS_DROPS_PATH + " is not a section of drop_<N> keys - ignored,"
                    + " every milestone draws from the pool.");
            }
            return Map.of();
        }
        Map<Integer, RewardEntry> drops = new HashMap<>();
        for (String key : section.getKeys(false)) {
            String at = REWARDS_DROPS_PATH + "." + key;
            String number = key.startsWith("drop_") ? key.substring(5) : "";
            if (!number.matches("[1-9][0-9]*")) {
                plugin.getLogger().warning(at + " is not a drop_<N> key (N = 1, 2, ...) - ignored.");
                continue;
            }
            // Length first, so a number too long for an int is out of range
            // rather than a NumberFormatException
            if (number.length() > 9 || Integer.parseInt(number) > milestones.size()) {
                plugin.getLogger().warning(at + " is past the last of the " + milestones.size()
                    + " bar.milestones - ignored.");
                continue;
            }
            int milestone = milestones.get(Integer.parseInt(number) - 1);

            String value = String.valueOf(section.get(key)).strip();
            if (value.equalsIgnoreCase("pool")) {
                continue;
            }
            RewardEntry drop = fixedItem(at, value, "milestone " + milestone + " draws from the pool");
            if (drop != null) {
                drops.put(milestone, drop);
            }
        }
        return Map.copyOf(drops);
    }

    // ====================================
    // One "<item path> [amount]" value as a one-item entry, or null when it
    // does not parse - named in the log along with 'otherwise', what happens
    // instead. Shared by rewards.drops and daily-reward.groups.
    // ====================================
    private RewardEntry fixedItem(String at, String value, String otherwise) {
        String[] parts = value.split("\\s+");
        // 0 marks anything that is not one or two words with a 1-64 second one
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
        // display is the bare item name: the payout prefixes the amount it
        // actually hands over, multiplier included
        return new RewardEntry(1, itemName(parts[0]), List.of(), List.of(new RewardEntry.Item(parts[0], amount)));
    }

    // ====================================
    // daily-reward.groups: '<group>: <item path> [amount]', in config order -
    // the first group a player is in wins, so the highest rank goes first. A
    // value that does not parse is named and that group gets nothing.
    // ====================================
    private Map<String, RewardEntry> loadDailyRewards(ConfigurationSection config) {
        ConfigurationSection section = config.getConfigurationSection(DAILY_REWARD_GROUPS_PATH);
        if (section == null) {
            if (config.contains(DAILY_REWARD_GROUPS_PATH)) {
                plugin.getLogger().warning(DAILY_REWARD_GROUPS_PATH + " is not a section of <group>: <item>"
                    + " lines - ignored, no daily reward is paid.");
            }
            return Map.of();
        }
        Map<String, RewardEntry> groups = new LinkedHashMap<>();
        for (String group : section.getKeys(false)) {
            RewardEntry reward = fixedItem(DAILY_REWARD_GROUPS_PATH + "." + Utils.safeForLog(group),
                String.valueOf(section.get(group)).strip(),
                "group " + Utils.safeForLog(group) + " gets no daily reward");
            if (reward != null) {
                groups.put(group, reward);
            }
        }
        return Collections.unmodifiableMap(groups);
    }

    // The load-time "nothing can ever be claimed" check: only true when some
    // milestone actually draws from the empty pool
    static boolean poolNeededButEmpty(List<RewardEntry> pool, Map<Integer, RewardEntry> drops,
                                      List<Integer> milestones) {
        return pool.isEmpty() && ActivityManager.needsPool(milestones, drops);
    }

    // The chat name of a fixed drop, from its path alone - resolving it here
    // would need TLibs/ItemsAdder up at load. The last segment, words
    // capitalised: m.material.steel is "Steel", ia.tfmc:ruby_gem "Ruby Gem".
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
    // The m.<type>.<id> or ia.<namespace:id> path an icon should be built
    // from, or null when the value is a plain material - which material() then
    // reports on as before. An ia. path comes back normalized to its colon
    // form, so the two ways of writing it are one path from here on.
    // ====================================
    private String iconPath(String name, String path) {
        if (ItemPath.isItemsAdderPath(name)) {
            String id = ItemPath.itemsAdderId(name);
            if (id == null) {
                plugin.getLogger().warning("Malformed item path '" + Utils.safeForLog(name) + "' at " + path
                    + " - expected ia.<namespace:id> - using PAPER.");
                return null;
            }
            // Well formed, so it counts even when ItemsAdder cannot resolve it -
            // load() says that once for the whole file. Kept as a path rather
            // than gated here the way the m. branch is, because the GUI is what
            // asks ItemsAdder and it re-checks itemsAdderUsable() at build time.
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
        // Well formed, so it counts as "the admin asked for item paths" even
        // when nothing can resolve it - loadActivities says that once, for the
        // whole file, instead of once per icon.
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

    public int rewardMultiplier() {
        return rewardMultiplier;
    }

    // Keyed by milestone point total; a milestone missing here draws from the pool
    public Map<Integer, RewardEntry> milestoneDrops() {
        return milestoneDrops;
    }

    // Group name -> daily reward, in config order: the first match wins
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

    public List<Integer> milestones() {
        return milestones;
    }

    public int barLength() {
        return barLength;
    }

    // How many times a day a player may throw today's draw away. 0 means the
    // reroll button refuses everyone.
    public int rerollsPerDay() {
        return rerollsPerDay;
    }

    // The most points a player may already have banked today and still be
    // allowed to reroll. 0 means the reroll is only offered before anything
    // has been earned.
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

    // Shortest gap between two 'click-commands' runs for one player
    public int clickCommandCooldownMillis() {
        return clickCommandCooldownMillis;
    }

    // Most click dispatches the whole server may do in a second
    public int clickCommandsPerSecond() {
        return clickCommandsPerSecond;
    }

    // Most commands one click may dispatch
    public int clickCommandsPerClick() {
        return clickCommandsPerClick;
    }

    // Whether an m.<type>.<id> path can actually be resolved right now -
    // TLibs, MMOItems and MythicLib all enabled. The GUI asks this instead
    // of re-checking isPluginEnabled("TLibs") on its own, so both places
    // agree on what "usable" means.
    public boolean itemPathsUsable() {
        return itemPathsUsable;
    }

    // Whether an ia.<namespace:id> path can actually be resolved right now.
    // Asked separately from itemPathsUsable() by the GUI and by the reward
    // payout, so ItemsAdder being absent costs ia. paths only.
    public boolean itemsAdderUsable() {
        return itemsAdderUsable;
    }

    // The same for m. paths and the TLibs/MMOItems/MythicLib trio
    static String itemPathWarning(boolean configured, boolean usable, String missing) {
        if (!configured || usable) {
            return null;
        }
        return "config.yml uses m.<type>.<id> item paths but " + missing
            + (missing.contains(",") ? " are" : " is") + " not enabled - those icons"
            + " fall back to PAPER, those crafts are never credited, and any reward item on such a path"
            + " hands over nothing and leaves its milestone unclaimed.";
    }

    // The one line the whole file gets when it asks for ia. paths and
    // ItemsAdder is not enabled, or null when there is nothing to say. Split
    // out so the rule can be pinned without a running server behind load().
    static String itemsAdderWarning(boolean configured, boolean usable) {
        if (!configured || usable) {
            return null;
        }
        return "config.yml uses ia.<namespace:id> item paths but ItemsAdder is not enabled -"
            + " those icons fall back to PAPER, and any reward item on such a path hands over"
            + " nothing and leaves its milestone unclaimed.";
    }
}
