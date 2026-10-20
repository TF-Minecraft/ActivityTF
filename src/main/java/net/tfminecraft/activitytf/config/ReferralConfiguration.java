package net.tfminecraft.activitytf.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.activitytf.models.RewardEntry;
import net.tfminecraft.activitytf.utils.ItemPath;
import net.tfminecraft.activitytf.utils.Utils;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** referral.yml: who may link, how playtime is counted, and one milestone table per act. */
public class ReferralConfiguration {

    public static final String FILE = "referral.yml";

    /** The GUI has one row of seven milestone slots. */
    public static final int MAX_MILESTONES = 7;

    /** A milestone reward: a fixed entry, or one draw from {@code pool}. */
    public record Reward(String display, String icon, String pool, RewardEntry fixed) {
    }

    public record Milestone(int hours, Reward newPlayer, Reward inviter, boolean rare) {
        public int minutes() {
            return hours * 60;
        }
    }

    /** {@code rareLimit} below 0 means no limit. */
    public record Act(String id, String display, LocalDateTime starts, int rareLimit, List<Milestone> milestones) {
    }

    private final JavaPlugin plugin;
    private final ActivityConfiguration activity;

    private volatile boolean enabled;
    private volatile LocalDateTime seasonStart;
    private volatile String seasonKey = "";
    private volatile int openDays;
    private volatile int newAccountDays;
    private volatile int maxInvitees;
    private volatile int maxLinksPerDay;
    private volatile boolean ipCheck;
    private volatile int afkMinutes;
    private volatile int moveBlocks;
    private volatile Set<String> ignoredWorlds = Set.of();
    private volatile List<Act> acts = List.of();
    private volatile Map<String, List<RewardEntry>> pools = Map.of();

    public ReferralConfiguration(JavaPlugin plugin, ActivityConfiguration activity) {
        this.plugin = plugin;
        this.activity = activity;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) {
            plugin.saveResource(FILE, false);
        }
        load(YamlConfiguration.loadConfiguration(file));
    }

    void load(ConfigurationSection config) {
        enabled = config.getBoolean("enabled", true);

        // A season.start that does not parse keeps the previous one, so a typo never starts a new season.
        String start = config.getString("season.start", "").strip();
        LocalDateTime parsed = start.isEmpty() ? null : dateTime(start, "season.start");
        if (start.isEmpty() || parsed != null) {
            seasonStart = parsed;
            seasonKey = parsed == null ? "" : parsed.toString();
        }
        openDays = Math.max(0, config.getInt("season.open-days", 14));
        newAccountDays = Math.max(0, config.getInt("link.new-account-days", 7));
        maxInvitees = Math.max(0, config.getInt("link.max-invitees", 10));
        maxLinksPerDay = Math.max(0, config.getInt("link.max-links-per-day", 3));
        ipCheck = config.getBoolean("link.same-ip-check", true);

        afkMinutes = Math.max(1, config.getInt("playtime.afk-minutes", 5));
        moveBlocks = Math.max(1, config.getInt("playtime.move-blocks", 3));
        Set<String> worlds = new HashSet<>();
        for (String world : config.getStringList("playtime.ignored-worlds")) {
            worlds.add(world.strip().toLowerCase(Locale.ROOT));
        }
        ignoredWorlds = Set.copyOf(worlds);

        pools = loadPools(config.getConfigurationSection("pools"));
        acts = loadActs(config.getConfigurationSection("acts"));
        if (enabled && acts.isEmpty()) {
            log().warning(FILE + " has no usable act under 'acts' - nothing can be claimed.");
        }
    }

    private Logger log() {
        return plugin.getLogger();
    }

    private LocalDateTime dateTime(String value, String at) {
        try {
            return value.contains("T") ? LocalDateTime.parse(value) : LocalDate.parse(value).atStartOfDay();
        } catch (DateTimeParseException e) {
            log().warning(at + " '" + Utils.safeForLog(value) + "' is not a date (2026-10-10) or date-time"
                + " (2026-10-10T06:00) - ignored.");
            return null;
        }
    }

    private Map<String, List<RewardEntry>> loadPools(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, List<RewardEntry>> loaded = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if (!ActivityConfiguration.isPoolName(key)) {
                log().warning(FILE + " pools." + Utils.safeForLog(key) + " must use a pool_<name> name - ignored.");
                continue;
            }
            loaded.put(ActivityConfiguration.poolName(key), activity.loadRewardPool(section, key));
        }
        return Map.copyOf(loaded);
    }

    private List<Act> loadActs(ConfigurationSection section) {
        if (section == null) {
            return List.of();
        }
        List<Act> loaded = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection act = section.getConfigurationSection(id);
            String at = "acts." + Utils.safeForLog(id);
            if (act == null) {
                log().warning(FILE + " " + at + " is not a section - ignored.");
                continue;
            }
            LocalDateTime starts = dateTime(act.getString("starts", "").strip(), at + ".starts");
            if (starts == null) {
                log().warning(FILE + " " + at + " has no usable 'starts' date - the act is ignored.");
                continue;
            }
            List<Milestone> milestones = loadMilestones(act, at);
            if (milestones.isEmpty()) {
                log().warning(FILE + " " + at + " has no usable milestone - the act is ignored.");
                continue;
            }
            loaded.add(new Act(id, act.getString("display", id), starts, act.getInt("rare-limit", 2),
                milestones));
        }
        loaded.sort(Comparator.comparing(Act::starts));
        return List.copyOf(loaded);
    }

    private List<Milestone> loadMilestones(ConfigurationSection act, String at) {
        List<Milestone> milestones = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int index = 0;
        for (Map<?, ?> raw : act.getMapList("milestones")) {
            String where = at + ".milestones[" + index++ + "]";
            int hours = raw.get("hours") instanceof Number number ? number.intValue() : 0;
            if (hours < 1 || hours > 10_000) {
                log().warning(FILE + " " + where + " needs 'hours' between 1 and 10000 - ignored.");
                continue;
            }
            if (seen.contains(hours)) {
                log().warning(FILE + " " + where + " repeats " + hours + "h - ignored.");
                continue;
            }
            Reward newPlayer = reward(raw.get("new-player"), where + ".new-player");
            Reward inviter = reward(raw.get("inviter"), where + ".inviter");
            if (newPlayer == null && inviter == null) {
                log().warning(FILE + " " + where + " pays neither player - ignored.");
                continue;
            }
            seen.add(hours);
            milestones.add(new Milestone(hours, newPlayer, inviter, Boolean.TRUE.equals(raw.get("rare"))));
        }
        milestones.sort(Comparator.comparingInt(Milestone::hours));
        if (milestones.size() > MAX_MILESTONES) {
            log().warning(FILE + " " + at + " has " + milestones.size() + " milestones - only the first "
                + MAX_MILESTONES + " fit in the menu, the rest are ignored.");
            return List.copyOf(milestones.subList(0, MAX_MILESTONES));
        }
        return List.copyOf(milestones);
    }

    private Reward reward(Object raw, String at) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String string && ActivityConfiguration.isPoolName(string)) {
            return poolReward(ActivityConfiguration.poolName(string), null, null, at);
        }
        if (!(raw instanceof Map<?, ?> block)) {
            log().warning(FILE + " " + at + " is not a pool name or a reward block - ignored.");
            return null;
        }
        String display = block.get("display") == null ? null : String.valueOf(block.get("display"));
        String icon = block.get("icon") == null ? null : String.valueOf(block.get("icon")).strip();
        if (icon != null && ItemPath.isUnsupportedPath(icon)) {
            log().warning(FILE + " " + at + ".icon '" + Utils.safeForLog(icon) + "' is not an item path - ignored.");
            icon = null;
        }
        if (block.get("pool") instanceof String pool && ActivityConfiguration.isPoolName(pool)) {
            return poolReward(ActivityConfiguration.poolName(pool), display, icon, at);
        }
        RewardEntry fixed = activity.rewardEntry(block, FILE + " " + at);
        if (fixed == null) {
            return null;
        }
        return new Reward(display == null || display.isBlank() ? fixedName(fixed) : display, icon, null, fixed);
    }

    private Reward poolReward(String pool, String display, String icon, String at) {
        if (pool(pool).isEmpty()) {
            log().warning(FILE + " " + at + " draws from '" + Utils.safeForLog(pool) + "', which is not a pool in "
                + FILE + " or the activity rewards, or has no usable entry - it stays unclaimable.");
        }
        return new Reward(display == null || display.isBlank() ? ActivityConfiguration.itemName(pool) : display,
            icon, pool, null);
    }

    private static String fixedName(RewardEntry fixed) {
        RewardEntry.Item first = fixed.items().isEmpty() ? null : fixed.items().get(0);
        return first == null ? "Reward" : ActivityConfiguration.itemName(first.path());
    }

    /** A pool from referral.yml, else the activity's (week-locked) reward pool of that name. */
    public List<RewardEntry> pool(String name) {
        List<RewardEntry> own = pools.get(name);
        return own != null ? own : activity.rewardPool(name);
    }

    /** The act whose start is the latest one not after {@code now}; null before the first act. */
    public Act act(LocalDateTime now) {
        Act current = null;
        for (Act act : acts) {
            if (!act.starts().isAfter(now)) {
                current = act;
            }
        }
        return current;
    }

    public List<Act> acts() {
        return acts;
    }

    public boolean enabled() {
        return enabled;
    }

    public LocalDateTime seasonStart() {
        return seasonStart;
    }

    /** Identifies the season the stored links belong to; a change starts a fresh referrals file. */
    public String seasonKey() {
        return seasonKey;
    }

    public int openDays() {
        return openDays;
    }

    public int newAccountDays() {
        return newAccountDays;
    }

    public int maxInvitees() {
        return maxInvitees;
    }

    public int maxLinksPerDay() {
        return maxLinksPerDay;
    }

    public boolean ipCheck() {
        return ipCheck;
    }

    public int afkMinutes() {
        return afkMinutes;
    }

    public int moveBlocks() {
        return moveBlocks;
    }

    public boolean ignoredWorld(String world) {
        return world != null && ignoredWorlds.contains(world.toLowerCase(Locale.ROOT));
    }

    public Messages messages() {
        return activity.messages();
    }

    public ActivityConfiguration activity() {
        return activity;
    }
}
