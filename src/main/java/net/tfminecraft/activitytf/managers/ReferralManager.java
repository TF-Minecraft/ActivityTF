package net.tfminecraft.activitytf.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import net.tfminecraft.activitytf.config.Messages;
import net.tfminecraft.activitytf.config.ReferralConfiguration;
import net.tfminecraft.activitytf.config.ReferralConfiguration.Act;
import net.tfminecraft.activitytf.config.ReferralConfiguration.Milestone;
import net.tfminecraft.activitytf.config.ReferralConfiguration.Reward;
import net.tfminecraft.activitytf.models.ReferralData;
import net.tfminecraft.activitytf.models.RewardEntry;
import net.tfminecraft.activitytf.store.ReferralStore;
import net.tfminecraft.activitytf.utils.Utils;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Referral links between a new player and the player who invited them, the AFK-aware playtime that
 * unlocks their milestones, and the claims both of them make from the referral menu.
 */
public class ReferralManager {

    private static final long MINUTE_TICKS = 60L * 20L;
    // Movement is sampled every second: once a minute misses someone pacing back to the same spot.
    private static final long SAMPLE_TICKS = 20L;
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    public enum Link {
        LINKED,
        DISABLED,
        FAILED,
        ALREADY_LINKED,
        SELF,
        UNKNOWN,
        CLOSED,
        SAME_IP,
        LOOP,
        INVITER_FULL,
        INVITER_BUSY
    }

    public enum Claim {
        CLAIMED,
        DISABLED,
        FAILED,
        NOT_LINKED,
        NOT_YOURS,
        NO_REWARD,
        ALREADY_CLAIMED,
        LOCKED,
        RARE_LIMIT,
        WRONG_WORLD,
        SAME_IP,
        UNCONFIGURED
    }

    public enum State {
        LOCKED,
        CLAIMABLE,
        CLAIMED,
        RARE_LIMIT
    }

    private final JavaPlugin plugin;
    private final ActivityManager activity;
    private final ReferralConfiguration config;
    private final ReferralStore store;

    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Map<UUID, Location> anchors = new ConcurrentHashMap<>();
    private final Set<String> warnedPools = ConcurrentHashMap.newKeySet();

    private LongSupplier clock = System::currentTimeMillis;

    private BukkitTask timer;
    private BukkitTask sampler;
    private BukkitTask autoSave;

    ReferralManager(JavaPlugin plugin, ActivityManager activity) {
        this(plugin, activity, new ReferralStore(plugin));
    }

    ReferralManager(JavaPlugin plugin, ActivityManager activity, ReferralStore store) {
        this.plugin = plugin;
        this.activity = activity;
        this.config = new ReferralConfiguration(plugin, activity.getConfiguration());
        this.store = store;
    }

    public void initialize() {
        config.load();
        store.load(config.seasonKey());
        seedOnline();
        stopTimers();
        timer = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, MINUTE_TICKS, MINUTE_TICKS);
        sampler = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sample, SAMPLE_TICKS, SAMPLE_TICKS);
        long save = activity.getConfiguration().saveIntervalMinutes() * MINUTE_TICKS;
        autoSave = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (store.isDirty()) {
                store.saveSoon();
            }
        }, save, save);
    }

    public void reload() {
        config.load();
        warnedPools.clear();
        // A file that failed to load is read again, so fixing it by hand needs no restart.
        if (!store.isLoaded() || !Objects.equals(store.season(), config.seasonKey())) {
            if (store.isLoaded()) {
                store.saveNow();
            }
            store.load(config.seasonKey());
            seedOnline();
        }
    }

    // Players already online when the store is (re)loaded never fire a join, so record them now.
    private void seedOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            seed(player);
            anchors.putIfAbsent(player.getUniqueId(), player.getLocation());
        }
    }

    /** Runs GUI work on the next tick, outside the inventory event that asked for it. */
    public void runNextTick(Runnable work) {
        plugin.getServer().getScheduler().runTask(plugin, work);
    }

    public void shutdown() {
        stopTimers();
        store.shutdown();
    }

    private void stopTimers() {
        if (sampler != null) {
            sampler.cancel();
            sampler = null;
        }
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        if (autoSave != null) {
            autoSave.cancel();
            autoSave = null;
        }
    }

    public ReferralConfiguration getConfiguration() {
        return config;
    }

    public ReferralStore getStore() {
        return store;
    }

    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    public long millis() {
        return clock.getAsLong();
    }

    public LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.getAsLong()), ZoneId.systemDefault());
    }

    public Act act() {
        return config.act(now());
    }

    private Messages messages() {
        return config.messages();
    }

    // ---- playtime ----------------------------------------------------------------------------

    void sample() {
        long now = clock.getAsLong();
        for (Player player : Bukkit.getOnlinePlayers()) {
            sampleMovement(player, now);
        }
    }

    void tick() {
        long now = clock.getAsLong();
        for (Player player : Bukkit.getOnlinePlayers()) {
            sampleMovement(player, now);
            if (counts(player, now)) {
                store.get(player.getUniqueId(), player.getName()).addMinute();
                store.markDirty();
            }
        }
    }

    // A move of at least playtime.move-blocks since the last one counts as being at the keyboard.
    // The first sample only sets the anchor: joining (or a reconnect loop) is not activity.
    void sampleMovement(Player player, long now) {
        Location here = player.getLocation();
        Location anchor = anchors.get(player.getUniqueId());
        double move = config.moveBlocks();
        if (anchor == null) {
            anchors.put(player.getUniqueId(), here);
        } else if (anchor.getWorld() != here.getWorld() || anchor.distanceSquared(here) >= move * move) {
            anchors.put(player.getUniqueId(), here);
            noteActive(player.getUniqueId(), now);
        }
    }

    /** Chat, commands and building also show someone is at the keyboard. */
    public void noteActive(UUID uuid) {
        noteActive(uuid, clock.getAsLong());
    }

    private void noteActive(UUID uuid, long now) {
        lastActive.put(uuid, now);
    }

    boolean counts(Player player, long now) {
        if (!config.enabled() || !seasonStarted() || config.ignoredWorld(player.getWorld().getName())) {
            return false;
        }
        Duration afk = Duration.ofMinutes(config.afkMinutes());
        if (player.getIdleDuration().compareTo(afk) >= 0) {
            return false;
        }
        Long last = lastActive.get(player.getUniqueId());
        return last != null && now - last < afk.toMillis();
    }

    boolean seasonStarted() {
        LocalDateTime start = config.seasonStart();
        return start == null || !now().isBefore(start);
    }

    public void onJoin(Player player) {
        seed(player);
        anchors.put(player.getUniqueId(), player.getLocation());
        lastActive.remove(player.getUniqueId());
        if (config.enabled() && hasClaimable(player.getUniqueId())) {
            player.sendMessage(messages().get("referral.reward-ready"));
        }
    }

    // Name, first join, last seen and connection of an online account.
    private void seed(Player player) {
        long now = clock.getAsLong();
        ReferralData data = store.get(player.getUniqueId(), player.getName());
        long first = player.getFirstPlayed() > 0 ? player.getFirstPlayed() : now;
        if (data.firstSeen() <= 0 || first < data.firstSeen()) {
            data.setFirstSeen(first);
        }
        data.setLastSeen(now);
        InetSocketAddress address = player.getAddress();
        if (address != null && address.getAddress() != null) {
            data.addIp(store.ipHash(address.getAddress().getHostAddress()));
        }
        store.markDirty();
    }

    /** True when the IP check is on, staff have not paired the two, and they have shared a connection. */
    boolean sharedConnection(UUID oneId, ReferralData one, UUID otherId, ReferralData other) {
        return config.ipCheck() && !one.ipPartners().contains(otherId) && !other.ipPartners().contains(oneId)
            && one.sharesIp(other);
    }

    /**
     * Staff override for two players who really share a connection (family, a shared house). It covers
     * only that pair, so neither can use it for alts of their own. Returns true when now allowed.
     */
    public boolean toggleIpPair(UUID one, UUID other) {
        ReferralData first = store.peek(one);
        ReferralData second = store.peek(other);
        boolean allow = !first.ipPartners().contains(other);
        if (allow) {
            first.ipPartners().add(other);
            second.ipPartners().add(one);
        } else {
            first.ipPartners().remove(other);
            second.ipPartners().remove(one);
        }
        store.markDirty();
        store.saveNow();
        return allow;
    }

    public void onQuit(UUID uuid) {
        lastActive.remove(uuid);
        anchors.remove(uuid);
    }

    // ---- linking -----------------------------------------------------------------------------

    /** Why this account cannot pick an inviter right now, or null when it can. */
    public Link linkable(UUID uuid) {
        if (!config.enabled()) {
            return Link.DISABLED;
        }
        ReferralData data = store.peek(uuid);
        if (data != null && data.invitedBy() != null) {
            return Link.ALREADY_LINKED;
        }
        return inWindow(data) ? null : Link.CLOSED;
    }

    /** The season's opening days, or the account's own first days on the server. */
    boolean inWindow(ReferralData data) {
        LocalDateTime start = config.seasonStart();
        LocalDateTime now = now();
        if (start != null && !now.isBefore(start) && now.isBefore(start.plusDays(config.openDays()))) {
            return true;
        }
        return data != null && data.firstSeen() > 0
            && clock.getAsLong() - data.firstSeen() < config.newAccountDays() * DAY_MILLIS;
    }

    public Link link(Player player, UUID inviterId) {
        UUID uuid = player.getUniqueId();
        Link refused = linkable(uuid);
        if (refused == null) {
            refused = linkRefusal(uuid, inviterId);
        }
        if (refused != null) {
            audit(player, "action=link inviter=" + inviterId + " result=" + refused.name().toLowerCase(Locale.ROOT));
            return refused;
        }

        ReferralData data = store.get(uuid, player.getName());
        data.link(inviterId, clock.getAsLong());
        store.markDirty();
        if (!store.saveNow()) {
            data.link(null, 0);
            store.markDirty();
            audit(player, "action=link inviter=" + inviterId + " result=save-failed");
            return Link.FAILED;
        }
        audit(player, "action=link inviter=" + inviterId + " inviter-name="
            + Utils.quotedForLog(store.peek(inviterId).name()) + " result=done");
        return Link.LINKED;
    }

    private Link linkRefusal(UUID uuid, UUID inviterId) {
        if (!store.isLoaded()) {
            return Link.FAILED;
        }
        if (uuid.equals(inviterId)) {
            return Link.SELF;
        }
        ReferralData inviter = inviterId == null ? null : store.peek(inviterId);
        if (inviter == null) {
            return Link.UNKNOWN;
        }
        if (leadsTo(inviterId, uuid)) {
            return Link.LOOP;
        }
        ReferralData data = store.peek(uuid);
        if (data != null && sharedConnection(uuid, data, inviterId, inviter)) {
            return Link.SAME_IP;
        }
        List<UUID> invited = invitees(inviterId);
        if (config.maxInvitees() > 0 && invited.size() >= config.maxInvitees()) {
            return Link.INVITER_FULL;
        }
        long since = clock.getAsLong() - DAY_MILLIS;
        long today = invited.stream().filter(id -> store.peek(id).linkedAt() > since).count();
        if (config.maxLinksPerDay() > 0 && today >= config.maxLinksPerDay()) {
            return Link.INVITER_BUSY;
        }
        return null;
    }

    /** True when following "invited by" up from {@code start} reaches {@code target}. */
    boolean leadsTo(UUID start, UUID target) {
        Set<UUID> seen = new HashSet<>();
        UUID current = start;
        while (current != null && seen.add(current)) {
            ReferralData data = store.peek(current);
            current = data == null ? null : data.invitedBy();
            if (target.equals(current)) {
                return true;
            }
        }
        return false;
    }

    /** Everyone linked to this inviter, by name. */
    public List<UUID> invitees(UUID inviter) {
        List<UUID> invited = new ArrayList<>();
        for (Map.Entry<UUID, ReferralData> entry : store.all().entrySet()) {
            if (inviter.equals(entry.getValue().invitedBy())) {
                invited.add(entry.getKey());
            }
        }
        invited.sort(Comparator.comparing(id -> String.valueOf(store.peek(id).name()).toLowerCase(Locale.ROOT)));
        return invited;
    }

    /** Typed name to account: someone who has played this season. */
    public UUID find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && store.peek(online.getUniqueId()) != null) {
            return online.getUniqueId();
        }
        return store.byName(name);
    }

    public boolean unlink(UUID uuid) {
        ReferralData data = store.peek(uuid);
        if (data == null || data.invitedBy() == null) {
            return false;
        }
        data.link(null, 0);
        store.markDirty();
        store.saveNow();
        return true;
    }

    // ---- milestones --------------------------------------------------------------------------

    public static Milestone milestone(Act act, int hours) {
        if (act != null) {
            for (Milestone milestone : act.milestones()) {
                if (milestone.hours() == hours) {
                    return milestone;
                }
            }
        }
        return null;
    }

    public State ownState(ReferralData data, Milestone milestone) {
        if (data.claimed().contains(milestone.hours())) {
            return State.CLAIMED;
        }
        return data.invitedBy() != null && data.minutes() >= milestone.minutes() ? State.CLAIMABLE : State.LOCKED;
    }

    public State inviterState(ReferralData inviter, ReferralData invitee, Act act, Milestone milestone) {
        if (invitee.inviterClaimed().contains(milestone.hours())) {
            return State.CLAIMED;
        }
        if (invitee.minutes() < milestone.minutes()) {
            return State.LOCKED;
        }
        return rareLimitReached(inviter, act, milestone) ? State.RARE_LIMIT : State.CLAIMABLE;
    }

    private static boolean rareLimitReached(ReferralData inviter, Act act, Milestone milestone) {
        return milestone.rare() && act.rareLimit() >= 0 && inviter.rareClaims(act.id()) >= act.rareLimit();
    }

    /** True when this account has any milestone, its own or an invitee's, it could claim now. */
    public boolean hasClaimable(UUID uuid) {
        Act act = act();
        ReferralData data = store.peek(uuid);
        if (act == null || data == null) {
            return false;
        }
        for (Milestone milestone : act.milestones()) {
            if (milestone.newPlayer() != null && ownState(data, milestone) == State.CLAIMABLE) {
                return true;
            }
        }
        for (UUID invitee : invitees(uuid)) {
            for (Milestone milestone : act.milestones()) {
                if (milestone.inviter() != null
                    && inviterState(data, store.peek(invitee), act, milestone) == State.CLAIMABLE) {
                    return true;
                }
            }
        }
        return false;
    }

    public Claim claimOwn(Player player, int hours) {
        if (!config.enabled()) {
            return Claim.DISABLED;
        }
        if (!store.isLoaded()) {
            return Claim.FAILED;
        }
        ReferralData data = store.get(player.getUniqueId(), player.getName());
        if (data.invitedBy() == null) {
            return Claim.NOT_LINKED;
        }
        Act act = act();
        Milestone milestone = milestone(act, hours);
        if (milestone == null || milestone.newPlayer() == null) {
            return Claim.NO_REWARD;
        }
        if (data.claimed().contains(hours)) {
            return Claim.ALREADY_CLAIMED;
        }
        if (data.minutes() < milestone.minutes()) {
            return Claim.LOCKED;
        }
        Claim refused = placeAndConnection(player, data, data.invitedBy(), store.peek(data.invitedBy()));
        if (refused != null) {
            return refused;
        }
        return pay(player, act, milestone.newPlayer(), "referral " + hours + "h",
            () -> data.claimed().add(hours), () -> data.claimed().remove(hours));
    }

    public Claim claimForInvitee(Player player, UUID inviteeId, int hours) {
        if (!config.enabled()) {
            return Claim.DISABLED;
        }
        if (!store.isLoaded()) {
            return Claim.FAILED;
        }
        ReferralData invitee = store.peek(inviteeId);
        if (invitee == null || !player.getUniqueId().equals(invitee.invitedBy())) {
            return Claim.NOT_YOURS;
        }
        Act act = act();
        Milestone milestone = milestone(act, hours);
        if (milestone == null || milestone.inviter() == null) {
            return Claim.NO_REWARD;
        }
        ReferralData data = store.get(player.getUniqueId(), player.getName());
        switch (inviterState(data, invitee, act, milestone)) {
            case CLAIMED -> {
                return Claim.ALREADY_CLAIMED;
            }
            case LOCKED -> {
                return Claim.LOCKED;
            }
            case RARE_LIMIT -> {
                return Claim.RARE_LIMIT;
            }
            case CLAIMABLE -> {
            }
        }
        Claim refused = placeAndConnection(player, data, inviteeId, invitee);
        if (refused != null) {
            return refused;
        }
        int rareDelta = milestone.rare() ? 1 : 0;
        return pay(player, act, milestone.inviter(), "referral " + hours + "h for " + inviteeId,
            () -> {
                invitee.inviterClaimed().add(hours);
                data.setRareClaims(act.id(), data.rareClaims(act.id()) + rareDelta);
            },
            () -> {
                invitee.inviterClaimed().remove(hours);
                data.setRareClaims(act.id(), data.rareClaims(act.id()) - rareDelta);
            });
    }

    /** Claims every milestone of one invitee that can be claimed now, stopping at the first refusal. */
    public List<Claim> claimAllForInvitee(Player player, UUID inviteeId) {
        List<Claim> results = new ArrayList<>();
        Act act = act();
        ReferralData invitee = store.peek(inviteeId);
        if (act == null || invitee == null) {
            return results;
        }
        ReferralData data = store.get(player.getUniqueId(), player.getName());
        for (Milestone milestone : act.milestones()) {
            if (milestone.inviter() != null && inviterState(data, invitee, act, milestone) == State.CLAIMABLE) {
                Claim claim = claimForInvitee(player, inviteeId, milestone.hours());
                results.add(claim);
                if (claim != Claim.CLAIMED) {
                    break;
                }
            }
        }
        return results;
    }

    // Rewards go to the character being played, never to the character-selection room, and are
    // held for accounts that have been seen on the same connection.
    private Claim placeAndConnection(Player player, ReferralData mine, UUID otherId, ReferralData other) {
        if (config.ignoredWorld(player.getWorld().getName())) {
            return Claim.WRONG_WORLD;
        }
        if (other != null && sharedConnection(player.getUniqueId(), mine, otherId, other)) {
            return Claim.SAME_IP;
        }
        return null;
    }

    // One act for the whole claim, so a claim at an act's start minute counts and pays in the same act.
    private Claim pay(Player player, Act act, Reward reward, String at, Runnable apply, Runnable revert) {
        RewardEntry entry = resolve(act, reward, player.getName());
        if (entry == null) {
            audit(player, "action=claim at=" + Utils.quotedForLog(at) + " result=unconfigured");
            return Claim.UNCONFIGURED;
        }

        apply.run();
        store.markDirty();
        if (!store.saveNow()) {
            revert.run();
            store.markDirty();
            audit(player, "action=claim at=" + Utils.quotedForLog(at) + " result=save-failed");
            return Claim.FAILED;
        }
        if (!activity.dispatchRewards(player, entry, at, 1)) {
            revert.run();
            store.markDirty();
            store.saveSoon();
            audit(player, "action=claim at=" + Utils.quotedForLog(at) + " result=pay-failed");
            return Claim.FAILED;
        }
        String shown = entry.display() == null || entry.display().isBlank() ? reward.display() : entry.display();
        player.sendMessage(messages().get("referral.claimed", "%reward%", Utils.colorize(shown)));
        audit(player, "action=claim at=" + Utils.quotedForLog(at) + " reward=" + Utils.quotedForLog(plainText(shown))
            + " result=done");
        return Claim.CLAIMED;
    }

    RewardEntry resolve(Act act, Reward reward, String name) {
        if (reward.fixed() != null) {
            return reward.fixed();
        }
        List<RewardEntry> pool = config.pool(act, reward.pool());
        RewardEntry drawn = ActivityManager.draw(ActivityManager.runnableEntries(pool, name));
        if (drawn == null && warnedPools.add(act.id() + "/" + reward.pool())) {
            plugin.getLogger().warning("Referral pool '" + Utils.safeForLog(reward.pool()) + "' has no entry that"
                + " can be paid to '" + Utils.safeForLog(name) + "' - the milestone stays claimable.");
        }
        return drawn;
    }

    static String plainText(String text) {
        return text.replaceAll("#[0-9a-fA-F]{6}|[&\u00a7][0-9a-fk-orA-FK-OR]", "");
    }

    private void audit(Player player, String what) {
        plugin.getLogger().info("REFERRAL-AUDIT player=" + Utils.quotedForLog(player.getName()) + " uuid="
            + player.getUniqueId() + " " + what);
    }
}
