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

/**
 * referral.yml: who may link, how playtime is counted, the timed milestones (the same for every act, each
 * claimed once ever), the inviter's rare track, and per act the pools those rewards draw from.
 */
public class ReferralConfiguration {

    public static final String FILE = "referral.yml";

    /** The rare-track menu has four rows of seven steps. */
    public static final int MAX_RARE_STEPS = 28;

    /** A milestone reward: a fixed entry, or one draw from {@code pool}. */
    public record Reward(String display, String icon, String pool, RewardEntry fixed) {
    }

    public record Milestone(int hours, Reward newPlayer, Reward inviter) {
        public int minutes() {
            return hours * 60;
        }
    }

    /** A rare-track step: the inviter claims it once {@code players} invitees have reached the last milestone. */
    public record RareStep(int players, Reward reward) {
    }

    /**
     * {@code milestones} and {@code rareTrack} are the shared lists; {@code pools} holds this act's loot for
     * the pool names they draw from.
     */
    public record Act(String id, String display, LocalDateTime starts, List<Milestone> milestones,
                      List<RareStep> rareTrack, Map<String, List<RewardEntry>> pools) {
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
    private volatile List<Milestone> milestones = List.of();
    private volatile List<RareStep> rareTrack = List.of();
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
        maxInvitees = Math.max(0, config.getInt("link.max-invitees", 0));
        maxLinksPerDay = Math.max(0, config.getInt("link.max-links-per-day", 3));
        ipCheck = config.getBoolean("link.same-ip-check", true);

        afkMinutes = Math.max(1, config.getInt("playtime.afk-minutes", 5));
        moveBlocks = Math.max(1, config.getInt("playtime.move-blocks", 3));
        Set<String> worlds = new HashSet<>();
        for (String world : config.getStringList("playtime.ignored-worlds")) {
            worlds.add(world.strip().toLowerCase(Locale.ROOT));
        }
        ignoredWorlds = Set.copyOf(worlds);

        pools = loadPools(config.getConfigurationSection("pools"), "pools");
        milestones = loadMilestones(config);
        rareTrack = loadRareTrack(config);
        acts = loadActs(config.getConfigurationSection("acts"));
        if (enabled && milestones.isEmpty()) {
            log().warning(FILE + " has no usable entry under 'milestones' - nothing can be claimed.");
        }
        if (enabled && acts.isEmpty()) {
            log().warning(FILE + " has no usable act under 'acts' - nothing can be claimed.");
        }
        if (enabled && maxInvitees > 0) {
            for (RareStep step : rareTrack) {
                if (step.players() > maxInvitees) {
                    log().warning(FILE + " rare-track step for " + step.players() + " players can never be"
                        + " claimed: link.max-invitees is " + maxInvitees + ".");
                }
            }
        }
        if (enabled) {
            Set<String> warned = new HashSet<>();
            for (Act act : acts) {
                warnMissingPools(act, warned);
            }
        }
    }

    // Every pool a reward draws from must exist for every act, or that reward can't be claimed then.
    private void warnMissingPools(Act act, Set<String> warned) {
        for (Milestone milestone : milestones) {
            warnMissingPool(act, milestone.newPlayer(), "the " + milestone.hours() + "h milestone", warned);
            warnMissingPool(act, milestone.inviter(), "the " + milestone.hours() + "h milestone", warned);
        }
        for (RareStep step : rareTrack) {
            warnMissingPool(act, step.reward(), "the " + step.players() + "-player rare step", warned);
        }
    }

    private void warnMissingPool(Act act, Reward reward, String what, Set<String> warned) {
        if (reward != null && reward.pool() != null && pool(act, reward.pool()).isEmpty()
            && warned.add(act.id() + "/" + reward.pool())) {
            log().warning(FILE + " acts." + Utils.safeForLog(act.id()) + " has no usable pool '"
                + Utils.safeForLog(reward.pool()) + "' (nor does pools or the activity rewards) - " + what
                + " can't be claimed during that act.");
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

    private Map<String, List<RewardEntry>> loadPools(ConfigurationSection section, String at) {
        if (section == null) {
            return Map.of();
        }
        Map<String, List<RewardEntry>> loaded = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if (!ActivityConfiguration.isPoolName(key)) {
                log().warning(FILE + " " + at + "." + Utils.safeForLog(key) + " must use a pool_<name> name - ignored.");
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
            if (act.contains("milestones")) {
                log().warning(FILE + " " + at + ".milestones is the old layout and is ignored - milestones are"
                    + " now one top-level list shared by every act; an act only sets its pools.");
            }
            if (act.contains("rare-limit")) {
                log().warning(FILE + " " + at + ".rare-limit is no longer used and is ignored - rare rewards are"
                    + " the top-level rare-track list now.");
            }
            LocalDateTime starts = dateTime(act.getString("starts", "").strip(), at + ".starts");
            if (starts == null) {
                log().warning(FILE + " " + at + " has no usable 'starts' date - the act is ignored.");
                continue;
            }
            loaded.add(new Act(id, act.getString("display", id), starts, milestones, rareTrack,
                loadPools(act.getConfigurationSection("pools"), at + ".pools")));
        }
        loaded.sort(Comparator.comparing(Act::starts));
        return List.copyOf(loaded);
    }

    private List<Milestone> loadMilestones(ConfigurationSection config) {
        List<Milestone> milestones = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int index = 0;
        for (Map<?, ?> raw : config.getMapList("milestones")) {
            String where = "milestones[" + index++ + "]";
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
            if (raw.containsKey("rare")) {
                log().warning(FILE + " " + where + ".rare is no longer used and is ignored - rare rewards are"
                    + " the top-level rare-track list now.");
            }
            seen.add(hours);
            milestones.add(new Milestone(hours, newPlayer, inviter));
        }
        milestones.sort(Comparator.comparingInt(Milestone::hours));
        return List.copyOf(milestones);
    }

    private List<RareStep> loadRareTrack(ConfigurationSection config) {
        List<RareStep> steps = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int index = 0;
        for (Map<?, ?> raw : config.getMapList("rare-track")) {
            String where = "rare-track[" + index++ + "]";
            int players = raw.get("players") instanceof Number number ? number.intValue() : 0;
            if (players < 1 || players > 1_000) {
                log().warning(FILE + " " + where + " needs 'players' between 1 and 1000 - ignored.");
                continue;
            }
            if (seen.contains(players)) {
                log().warning(FILE + " " + where + " repeats " + players + " players - ignored.");
                continue;
            }
            Reward reward = reward(raw.get("reward"), where + ".reward");
            if (reward == null) {
                log().warning(FILE + " " + where + " has no usable 'reward' - ignored.");
                continue;
            }
            seen.add(players);
            steps.add(new RareStep(players, reward));
        }
        steps.sort(Comparator.comparingInt(RareStep::players));
        if (steps.size() > MAX_RARE_STEPS) {
            log().warning(FILE + " has " + steps.size() + " rare-track steps - only the first "
                + MAX_RARE_STEPS + " fit in the menu, the rest are ignored.");
            return List.copyOf(steps.subList(0, MAX_RARE_STEPS));
        }
        return List.copyOf(steps);
    }

    private Reward reward(Object raw, String at) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String string && ActivityConfiguration.isPoolName(string)) {
            return poolReward(ActivityConfiguration.poolName(string), null, null);
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
            return poolReward(ActivityConfiguration.poolName(pool), display, icon);
        }
        RewardEntry fixed = activity.rewardEntry(block, FILE + " " + at);
        if (fixed == null) {
            return null;
        }
        return new Reward(display == null || display.isBlank() ? fixedName(fixed) : display, icon, null, fixed);
    }

    private static Reward poolReward(String pool, String display, String icon) {
        return new Reward(display == null || display.isBlank() ? ActivityConfiguration.itemName(pool) : display,
            icon, pool, null);
    }

    private static String fixedName(RewardEntry fixed) {
        RewardEntry.Item first = fixed.items().isEmpty() ? null : fixed.items().get(0);
        return first == null ? "Reward" : ActivityConfiguration.itemName(first.path());
    }

    /**
     * The act's own pool of that name, else referral.yml's shared {@code pools}, else the activity's
     * (week-locked) reward pool. {@code act} may be null.
     */
    public List<RewardEntry> pool(Act act, String name) {
        List<RewardEntry> own = act == null ? null : act.pools().get(name);
        if (own == null || own.isEmpty()) {
            own = pools.get(name);
        }
        return own != null && !own.isEmpty() ? own : activity.rewardPool(name);
    }

    /** The milestones every act shares; each is claimed once ever (per season). */
    public List<Milestone> milestones() {
        return milestones;
    }

    /** The inviter's rare track, by how many invitees reached the last milestone; each step claimed once. */
    public List<RareStep> rareTrack() {
        return rareTrack;
    }

    /** Counted minutes an invitee needs to count on the rare track: the last milestone's (0 without any). */
    public int completionMinutes() {
        List<Milestone> all = milestones;
        return all.isEmpty() ? 0 : all.get(all.size() - 1).minutes();
    }

    /**
     * The act whose start is the latest one not after {@code now}; null before the first act, and
     * while there are no milestones (nothing could be claimed in it anyway).
     */
    public Act act(LocalDateTime now) {
        if (milestones.isEmpty()) {
            return null;
        }
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

    /** Puts back the season start from before a reload, when the stored links could not be saved first. */
    public void keepSeason(LocalDateTime start) {
        seasonStart = start;
        seasonKey = start == null ? "" : start.toString();
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
