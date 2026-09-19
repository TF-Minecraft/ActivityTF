package tfmc.justin.activity.managers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.config.Messages;
import tfmc.justin.activity.gui.ActivityGui;
import tfmc.justin.activity.hooks.ItemsAdderItems;
import tfmc.justin.activity.hooks.TLibsItems;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.models.RecordResult;
import tfmc.justin.activity.models.Recorded;
import tfmc.justin.activity.models.RewardEntry;
import tfmc.justin.activity.store.PlayerStore;
import tfmc.justin.activity.utils.ItemPath;
import tfmc.justin.activity.utils.Utils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;

// ====================================
// The one place that turns "a player did something" into points, chat
// feedback and rewards. Every intake path (events, command, API) lands here.
// Main thread only.
// ====================================
public class ActivityManager {

    // ====================================
    // Vanilla name charset. Floodgate/Bedrock names carry a prefix and can
    // contain spaces, which would split a console command into two - such a
    // name is never pasted into one.
    // ====================================
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_.]{1,16}$");

    private static final long MINUTE_TICKS = 60L * 20L;

    // Nulled by shutdown() on the main thread while a vote can still be in
    // flight on VotingPlugin's own thread
    private static volatile ActivityManager instance;

    private final JavaPlugin plugin;
    private final ActivityConfiguration config;
    private final PlayerStore store;

    // A store that never loaded refuses every save for the rest of the
    // session, so saying so once per session is enough
    private final Set<String> warnedStoreNotLoaded = new HashSet<>();

    // Same idea for an empty reward pool, which a player can hit at click rate
    // - said once per load rather than once per click
    private boolean warnedEmptyPool;

    // Reward item paths already reported as unresolvable, so a broken config
    // warns once rather than once per claim click. Static because giveItems is,
    // and cleared by reload() the way warnedEmptyPool is.
    static final Set<String> reportedItemPaths = ConcurrentHashMap.newKeySet();

    // ====================================
    // Same idea for a configured command the console refused or that threw:
    // an unknown command (the shipped 'sudo %player% votelist' on a server
    // with neither CMI nor EssentialsX, say) would otherwise write a line per
    // click per player for the whole uptime. Keyed by kind + the configured
    // command, so the same text under 'reward' and under 'click' is still
    // reported once each, and cleared by reload() the way the set above is.
    // ====================================
    static final Set<String> reportedBrokenCommands = ConcurrentHashMap.newKeySet();

    // uuid -> the nanoTime of that player's last click-commands dispatch.
    // Written on the main thread (InventoryClickEvent) and emptied per player
    // by the GUI's quit handler, but concurrent like every other shared map
    // here: both accessors are public and this class is already touched off
    // the main thread.
    private final Map<UUID, Long> clickCooldowns = new ConcurrentHashMap<>();

    // ====================================
    // The server-wide ceiling the per-player cooldown cannot give: a token
    // bucket refilled at click-commands-per-second, holding at most one
    // second's worth. Twenty players each holding a mouse button are twenty
    // dispatches a second past the cooldown; a hundred players are a hundred,
    // all synchronous on the main thread. Main thread only - every reader is
    // inside InventoryClickEvent.
    // ====================================
    private double clickTokens;
    private long clickTokensAt;
    private boolean clickTokensPrimed;

    // One tick a minute crediting online, non-idle players. Cancelled by
    // shutdown; reload leaves it running, since it reads the AFK threshold
    // fresh on every tick.
    private BukkitTask playtime;

    private ActivityManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = new ActivityConfiguration(plugin);
        this.store = new PlayerStore(plugin, config);
    }

    public static ActivityManager getInstance(JavaPlugin plugin) {
        if (instance == null) {
            instance = new ActivityManager(plugin);
        }
        return instance;
    }

    public static ActivityManager getInstance() {
        return instance;
    }

    public static boolean isSafeCommandName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    // ====================================
    // Whether one configured console command can be run for one player. Only
    // a command that pastes %player% needs a name that survives being pasted
    // - a %uuid%-only command runs for a Bedrock name with a space in it just
    // fine. The same rule for a reward command and for a click command.
    // ====================================
    public static boolean canRunCommand(String command, String name) {
        return command != null && (!command.contains("%player%") || isSafeCommandName(name));
    }

    public void initialize() {
        config.load();
        store.load();
        store.startAutoSave();
        startPlaytime();
    }

    // ====================================
    // Whole minutes only. A player who joins or leaves mid-minute loses the
    // part-minute rather than carrying it, which costs at most a minute a
    // session and saves keeping a per-player accumulator alive.
    //
    // recordAction is a no-op when 'playtime' is not a configured activity,
    // so the tick can run unconditionally - and starts working the moment an
    // admin adds the entry and reloads.
    //
    // The period is 1200 ticks, not 60 seconds: a server running behind
    // credits slower than the wall clock. Under-crediting is the safe
    // direction, so it is left alone.
    // ====================================
    private void startPlaytime() {
        // A second initialize() would otherwise leak the first timer and pay
        // every online player twice a minute
        if (playtime != null) {
            playtime.cancel();
        }

        playtime = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Duration afk = afkThreshold(config.afkMinutes());
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (afk != null && player.getIdleDuration().compareTo(afk) >= 0) {
                    continue;
                }
                recordAction(player.getUniqueId(), "playtime", 1);
            }
        }, MINUTE_TICKS, MINUTE_TICKS);
    }

    // ====================================
    // null when the admin has switched the idle check off, which is the only
    // meaning 0 or a negative can carry - a zero-length threshold would
    // otherwise be met by every player on every tick and pay nobody.
    //
    // Worth knowing what this threshold is and is not: getIdleDuration()
    // resets on any packet from the client, so it only catches a client that
    // has stopped talking to the server at all.
    // ====================================
    static Duration afkThreshold(int minutes) {
        return minutes <= 0 ? null : Duration.ofMinutes(minutes);
    }

    public void shutdown() {
        if (playtime != null) {
            playtime.cancel();
            playtime = null;
        }
        // Cancels the autosave and writes the final snapshot under one lock
        store.shutdown();
        // A disabled plugin must not still be reachable through the API, or a
        // late event would score into a store nobody will ever save again
        instance = null;
    }

    public ActivityConfiguration getConfiguration() {
        return config;
    }

    public PlayerStore getStore() {
        return store;
    }

    // The plugin's logger, so the admin command can write its audit line
    public Logger logger() {
        return plugin.getLogger();
    }

    // ====================================
    // Record progress towards an activity. What came of it is returned so the
    // admin command can say so - an unknown id, a refused gate and a cap that
    // swallowed the points all read as failures to an admin, and only this
    // method can tell them apart. Callers that cannot act on it (events, the
    // API) just ignore the result.
    //
    // The daily-task gate lives here: every listener, the playtime tick and
    // the API come through this method, and an activity that is not one of
    // the player's revealed tasks today records nothing at all.
    // ====================================
    public Recorded recordAction(UUID uuid, String activityId, int amount) {
        return record(uuid, activityId, amount, true).outcome();
    }

    // ====================================
    // /activity add. Gated exactly like a listener unless the admin asked for
    // --force, which skips the daily-task gate AND credits the full worth of
    // the count past the activity's daily cap and today's budget (see
    // PlayerData.recordForced). The whole result comes back rather than just
    // the outcome, so the command can report how many points actually landed.
    // ====================================
    public RecordResult recordAdmin(UUID uuid, String activityId, int amount, boolean force) {
        return record(uuid, activityId, amount, !force);
    }

    private RecordResult record(UUID uuid, String activityId, int amount, boolean gated) {
        ActivityDef def = config.activity(activityId);
        if (def == null || amount <= 0) {
            return new RecordResult(0, 0, Recorded.UNKNOWN);
        }

        // ====================================
        // The gated path never draws and never creates a row: a listener
        // firing for a player who has not opened the GUI today must not pin
        // that player in players.yml forever. No draw yet means no revealed
        // task, which means nothing to record.
        // ====================================
        PlayerData data = gated ? store.rolled(uuid) : store.get(uuid);
        if (gated && (data == null || !data.isRevealed(activityId))) {
            return new RecordResult(0, 0, Recorded.NOT_A_TASK);
        }

        RecordResult result = gated
            ? data.record(amount, def, config.barMax(), config.dailyMax(), config.milestones())
            : data.recordForced(amount, def, config.barMax(), config.milestones());
        return credited(uuid, data, result, Utils.colorize(def.display()));
    }

    // ====================================
    // /activity addpoints: exactly this many points, with no activity and no
    // count behind them. Credited like a forced add (PlayerData.creditForced),
    // so bar.max clamps and neither daily limit applies, and announced by the
    // same tail as every other award.
    // ====================================
    public RecordResult recordPoints(UUID uuid, int points) {
        // Before store.get, which would create and pin a row for nothing
        if (points <= 0) {
            return new RecordResult(0, 0, Recorded.UNKNOWN);
        }
        PlayerData data = store.get(uuid);
        return credited(uuid, data, data.creditForced(points, config.barMax(), config.milestones()),
            config.messages().get("points-granted-source"));
    }

    // What every award does once the points are on the bar: save, and tell
    // an online player what landed and whether a reward is now waiting
    private RecordResult credited(UUID uuid, PlayerData data, RecordResult result, String source) {
        store.markDirty();

        // A full bar or a met daily cap awards nothing, and "+0" is worse
        // than silence
        if (result.pointsAwarded() <= 0) {
            return result;
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return result;
        }

        Messages messages = config.messages();
        player.sendMessage(messages.get("points-earned",
            "%activity%", source,
            "%points%", result.pointsAwarded(),
            "%total%", data.points(),
            "%max%", config.barMax()));
        playSound(player, config.goalCompleteSound());

        if (result.milestonesReached() > 0) {
            player.sendMessage(messages.get("reward-ready"));
            playSound(player, config.barCompleteSound());
        }
        return result;
    }

    // ====================================
    // Whether this activity would earn anything for this player right now:
    // it is loaded and one of their revealed tasks today. Listeners that
    // carry fractions ask this first, so no fraction banks up for a task
    // that is hidden or was never drawn. Like the record path it neither
    // draws nor creates a row - a player with no draw yet is simply false.
    // ====================================
    public boolean isTracked(UUID uuid, String activityId) {
        PlayerData data = store.rolled(uuid);
        return config.activity(activityId) != null && data != null && data.isRevealed(activityId);
    }

    // ====================================
    // The player's data with today's draw made good: drawn if this is the
    // first time today it is needed, and topped back up if a reload removed
    // one of the activities in it. Only the GUI paths (open and reveal) come
    // here, so no listener event can create an entry.
    // ====================================
    public PlayerData tasks(UUID uuid) {
        PlayerData data = store.get(uuid);
        if (ensureTasks(data)) {
            store.markDirty();
        }
        return data;
    }

    private boolean ensureTasks(PlayerData data) {
        return data.ensureTasks(activityIds(), config.guaranteed(), ThreadLocalRandom.current());
    }

    private List<String> activityIds() {
        return config.activities().stream().map(ActivityDef::id).toList();
    }

    // What a reroll click came to - the GUI says which of these it was, and
    // only DONE changed anything
    public enum Rerolled {
        DONE,
        NONE_LEFT,
        // Too much already earned today (reroll.max-points) - checked before
        // NONE_LEFT, since the threshold blocks the rest of the day whatever
        // the budget says, and a "rerolls left: 1" that refuses is worse
        TOO_LATE,
        DISABLED,
        // players.yml was never loaded, so the spent reroll and the points it
        // took back could not be saved - refused rather than done in memory
        FAILED
    }

    // ====================================
    // Throws today's draw away and hands out a fresh one, taking today's
    // points back off the weekly bar (never below what has already been paid
    // out - see PlayerData.reroll). The draw is the same one ensureTasks
    // makes, so daily-guaranteed activities are still guaranteed afterwards.
    // Nothing is touched unless DONE is returned.
    // ====================================
    public Rerolled reroll(UUID uuid) {
        int perDay = config.rerollsPerDay();
        if (perDay <= 0) {
            return Rerolled.DISABLED;
        }

        // Same rule as claim(): nothing that has to be persisted may happen
        // while nothing can be. A reroll done in memory only would cost the
        // player today's points until the next restart and hand the counter
        // back with them, making the per-day budget a per-restart one
        if (storeNeverLoaded("Refusing every reroll")) {
            return Rerolled.FAILED;
        }

        // After get(), so a rollover has already reset dailyPoints: yesterday's
        // earnings must not block today's reroll
        PlayerData data = store.get(uuid);
        if (data.dailyPoints() > config.rerollMaxPoints()) {
            return Rerolled.TOO_LATE;
        }

        if (data.rerolls() >= perDay) {
            return Rerolled.NONE_LEFT;
        }

        data.reroll(PlayerData.draw(activityIds(), config.guaranteed(), ThreadLocalRandom.current()),
            config.barMax());
        store.markDirty();
        return Rerolled.DONE;
    }

    // ====================================
    // True when players.yml was never read, in which case the store refuses
    // every save for the rest of the session and nothing that must survive a
    // restart may be done. Says so once per session per distinct 'refusing'
    // context, however many callers ask - reroll and reward-claim refusals
    // are warned about separately so one does not silence the other.
    // ====================================
    private boolean storeNeverLoaded(String refusing) {
        if (store.isLoaded()) {
            return false;
        }
        if (warnedStoreNotLoaded.add(refusing)) {
            plugin.getLogger().severe(refusing + ": " + PlayerStore.FILE
                + " was never loaded, so nothing done here could be saved.");
        }
        return true;
    }

    // ====================================
    // What a reveal click did: the id actually revealed (null when the slot
    // was empty, already revealed, or holds an activity a reload has since
    // removed), and whether making the draw good moved the tasks around.
    // A caller drawing the draw has to repaint every slot when it did - the
    // slot the player clicked is no longer the one they saw.
    // ====================================
    public record Reveal(String revealedId, boolean drawChanged) {
    }

    // ====================================
    // Reveals the task in this slot (0-based).
    //
    // The id in the slot is read BEFORE the draw is made good, and it is that
    // id that gets revealed: making the draw good can compact and top up the
    // list, and applying the slot index afterwards would reveal whatever
    // slid into the slot rather than what the player clicked. An id the
    // top-up dropped (its activity is gone) reveals nothing. A slot that held
    // nothing to begin with - a player whose draw is being made for the first
    // time here - is still taken by index; there was nothing else to mean.
    // ====================================
    public Reveal reveal(UUID uuid, int slot) {
        PlayerData data = store.get(uuid);
        List<String> before = data.tasks();
        String clicked = slot >= 0 && slot < before.size() ? before.get(slot) : null;

        boolean drawChanged = ensureTasks(data);
        int index = clicked == null ? slot : data.tasks().indexOf(clicked);

        boolean revealed = index >= 0 && index < data.tasks().size()
            && config.activity(data.tasks().get(index)) != null
            && data.reveal(index);

        if (drawChanged || revealed) {
            store.markDirty();
        }
        return new Reveal(revealed ? data.tasks().get(index) : null, drawChanged);
    }

    // ====================================
    // Hands over every milestone the player has reached but not yet claimed:
    // its fixed rewards.drops item, or else rewards.multiplier draws from the reward pool.
    // Only ever called for an online player (a GUI click), so %player% always
    // resolves. Returns how
    // many milestones were actually paid: 0 covers both "nothing was due" and
    // every refusal below, all of which leave the milestones there to claim
    // once an operator has fixed what broke.
    //
    // The order is the whole point, because a reward has real in-game value:
    //
    //   1. Every reason to refuse is checked before anything is touched. In
    //      particular, the pool is first narrowed to the entries this player's
    //      name can actually be paid from (see runnableEntries) - an unsafe
    //      name that only some entries can pay is not a reason to fail the
    //      whole claim, and it must not cost a disk write either way. That is
    //      what a Bedrock player clicking the bar over and over hits.
    //   2. The milestones are burned and written to disk BEFORE the first
    //      command runs: a crash between the give and the next autosave would
    //      otherwise hand the whole lot out again on restart, and a reward
    //      command that loops back in here must find them already paid.
    //   3. A save that failed pays nothing at all. The tick blocks on one file
    //      write, which is the price of never paying twice.
    //   4. If the dispatch stops early - and it can, since a reward command
    //      belongs to another plugin and is free to throw - only what actually
    //      went out stays burned; the rest goes back, never below where it
    //      started.
    //
    // The (already narrowed) pool is snapshotted once, so a reward command
    // that runs /activity reload cannot change what the rest of the loop
    // hands out.
    // ====================================
    public int claim(Player player) {
        Messages messages = config.messages();

        // Nothing may be paid while nothing can be persisted: a store that
        // never loaded refuses every write for the rest of the session, so a
        // payout here would last only until the next restart and then repeat
        if (storeNeverLoaded("Refusing every reward claim")) {
            player.sendMessage(messages.get("reward-failed"));
            return 0;
        }

        PlayerData data = store.get(player.getUniqueId());
        List<Integer> due = PlayerData.due(data.points(), data.claimedPoints(), config.milestones());
        if (due.isEmpty()) {
            return 0;
        }

        List<RewardEntry> pool = config.rewardPool();
        Map<Integer, RewardEntry> drops = config.milestoneDrops();
        // The pool refusals below only apply when a due milestone draws from it
        boolean needsPool = needsPool(due, drops);

        // Nothing configured to hand over: burning the milestones here would
        // pay the player in silence. Load already warned about this, but a
        // claim is the moment an operator can tie the warning to a player.
        if (needsPool && pool.isEmpty()) {
            if (!warnedEmptyPool) {
                warnedEmptyPool = true;
                plugin.getLogger().warning("Handed nothing to " + player.getUniqueId()
                    + ": rewards.pool has no usable entry, so the milestone stays claimable.");
            }
            // The one refusal the player cannot read off the bar
            player.sendMessage(messages.get("reward-unconfigured"));
            return 0;
        }

        // A name no entry in the pool can pay is a failure the player should
        // hear about, and it is answered here - before any mutation or save -
        // because it is the one refusal a player can trigger at click rate.
        // Entries that can pay only sometimes (a mix of %player% and %uuid%
        // commands) stay in; only the ones this name cannot pay at all drop out.
        List<RewardEntry> runnablePool = runnableEntries(pool, player.getName());
        if (needsPool && runnablePool.isEmpty()) {
            plugin.getLogger().warning("No reward command could be run for '"
                + Utils.safeForLog(player.getName()) + "': the name cannot be safely pasted into a console"
                + " command. Use %uuid%-based reward commands to support Bedrock/unsafe names.");
            player.sendMessage(messages.get("reward-failed"));
            return 0;
        }

        int claimedBefore = data.claimedPoints();
        int claimedAfter = Collections.max(due);
        data.setClaimedPoints(claimedAfter);
        store.markDirty();

        if (!store.saveNow()) {
            data.setClaimedPoints(claimedBefore);
            store.markDirty();
            plugin.getLogger().severe("Handed nothing to " + player.getUniqueId() + ": claimed-points could"
                + " not be saved before the rewards ran (would have gone " + claimedBefore + " -> "
                + claimedAfter + "), so nothing was dispatched.");
            player.sendMessage(messages.get("reward-failed"));
            return 0;
        }

        // Snapshotted with the pool (see payMilestones)
        Payout payout = payMilestones(due, drops, config.rewardMultiplier(), runnablePool, ActivityManager::draw,
            (milestone, drawn, itemMultiplier) -> {
                if (!dispatchRewards(player, drawn, "milestone " + milestone, itemMultiplier)) {
                    return false;
                }
                player.sendMessage(messages.get("reward-claimed", "%reward%", Utils.colorize(drawn.display())));
                return true;
            },
            Utils.safeForLog(player.getName()) + "/" + player.getUniqueId(), plugin.getLogger());
        int paid = payout.paid();
        boolean spinFailed = payout.failed();

        // ====================================
        // Only what actually went out stays paid for. The second save is the
        // one that can leave memory and disk disagreeing, and the only honest
        // thing to do about it is to say exactly what each holds so an
        // operator can put the file right by hand.
        // ====================================
        if (paid < due.size()) {
            int rolledBack = rollbackClaimedPoints(claimedBefore, paid, due);
            data.setClaimedPoints(rolledBack);
            store.markDirty();
            // Nothing went out at all - the case a player holding the mouse
            // down on a broken pool hits on every click. The row is back where
            // this click found it, so the whole store is not serialised a
            // second time per click; only a partial payout, which changes what
            // was already written, is worth blocking the tick on.
            //
            // Disk still says claimedAfter until that queued write lands,
            // though, and a crash in between costs the player every milestone
            // he reached this week. So the write is queued now rather than
            // left to the next autosave tick (save-interval-minutes, 5 by
            // default), and the window is stated in the log so an operator who
            // finds the file that way knows what it should have held.
            if (rolledBack == claimedBefore) {
                store.saveSoon();
                plugin.getLogger().warning("Reward payout for " + player.getUniqueId() + " paid none of the"
                    + " milestones " + due + ": claimed-points is back at " + claimedBefore + " in memory but"
                    + " still " + claimedAfter + " on disk until the queued save lands.");
            } else if (!store.saveNow()) {
                plugin.getLogger().severe("Reward payout for " + player.getUniqueId() + " is out of sync:"
                    + " paid " + paid + " of the milestones " + due + ", claimed-points is " + rolledBack
                    + " in memory but " + claimedAfter + " on disk. Repair " + PlayerStore.FILE + " by hand.");
            }
            player.sendMessage(messages.get("reward-failed"));
            return paid;
        }

        if (spinFailed) {
            player.sendMessage(messages.get("reward-failed"));
            return paid;
        }

        playSound(player, config.barCompleteSound());
        return paid;
    }

    // ====================================
    // The payout loop of claim(), in order over the due milestones: a fixed
    // drop is paid multiplier times its amount, a pool milestone is spun
    // multiplier times, each spin paid at multiplier 1. The spins are
    // independent, so one that fails does not stop the rest. A milestone
    // counts as paid once any of its spins went out - rolling it back would
    // spin it again on the next click and pay those twice - and the spins it
    // lost are logged every time, since only an operator can hand them over.
    // A milestone that paid nothing ends the loop and stays claimable, as does
    // every milestone after one that failed a spin. Takes the draw and the
    // payout so the loop can be pinned headless.
    // ====================================
    record Payout(int paid, boolean failed) {}

    @FunctionalInterface
    interface SpinPayer {
        boolean pay(int milestone, RewardEntry entry, int multiplier);
    }

    static Payout payMilestones(List<Integer> due, Map<Integer, RewardEntry> drops, int multiplier,
                                List<RewardEntry> pool, Function<List<RewardEntry>, RewardEntry> draw,
                                SpinPayer pay, String who, Logger logger) {
        int paid = 0;
        for (int milestone : due) {
            int itemMultiplier = drops.containsKey(milestone) ? multiplier : 1;
            List<RewardEntry> spins = rewardFor(milestone, drops, multiplier, pool, draw);
            int spun = 0;
            for (RewardEntry drawn : spins) {
                if (drawn != null && pay.pay(milestone, drawn, itemMultiplier)) {
                    spun++;
                }
            }
            if (spun == 0) {
                return new Payout(paid, true);
            }
            paid++;
            if (spun < spins.size()) {
                logger.severe("Milestone " + milestone + " paid " + spun + " of " + spins.size() + " spins for "
                    + who + " - it stays claimed, so hand " + (spins.size() - spun) + " spins over by hand.");
                return new Payout(paid, true);
            }
        }
        return new Payout(paid, false);
    }

    // Does any of these milestones draw from the pool, rather than pay a
    // fixed rewards.drops item?
    public static boolean needsPool(List<Integer> milestones, Map<Integer, RewardEntry> drops) {
        return !drops.keySet().containsAll(milestones);
    }

    // What one milestone pays, one element per spin: its fixed drop alone,
    // and the pool is never drawn from for it; otherwise 'multiplier'
    // independent draws, so the same entry can come up more than once. Takes
    // the draw so a test can see it is skipped. A fixed drop's display is its
    // bare item name (see loadMilestoneDrops) and gets the amount this payout
    // hands over, with the same multiplier giveItems applies to it.
    static List<RewardEntry> rewardFor(int milestone, Map<Integer, RewardEntry> drops, int multiplier,
                                       List<RewardEntry> pool, Function<List<RewardEntry>, RewardEntry> draw) {
        RewardEntry fixed = drops.get(milestone);
        if (fixed != null) {
            return List.of(withPaidAmount(fixed, multiplier));
        }
        List<RewardEntry> spins = new ArrayList<>();
        for (int spin = 0; spin < multiplier; spin++) {
            spins.add(draw.apply(pool));
        }
        return spins;
    }

    // A fixed one-item entry with the amount it hands over put in front of its
    // bare-name display: "x3 Steel" at multiplier 1, "x6 Steel" at 2
    static RewardEntry withPaidAmount(RewardEntry fixed, int multiplier) {
        return new RewardEntry(fixed.weight(), "#50d990x" + fixed.items().get(0).amount() * multiplier
            + " #b8906e" + fixed.display(), fixed.commands(), fixed.items());
    }

    // ====================================
    // daily-reward: paid once a day to a player in one of its groups, the
    // moment every task of today's draw is revealed. Called after a reveal
    // click and on every GUI open - the open is what retries a payout that
    // failed, and what pays a player who joined a group after revealing
    // everything. False when nothing was paid, which includes every case
    // where nothing is due.
    //
    // The item is resolved first, before anything is touched: a broken one
    // is refused without a disk write, since every open would otherwise pay
    // for a full players.yml save to find the same thing out. Then the same
    // burn-save-pay order as claim(): the flag is set and written before the
    // item goes out, so a crash cannot pay it twice, and handed back when
    // nothing reached the player, so the next open retries it. A player in no
    // listed group is not marked at all.
    // ====================================
    public boolean claimDailyReward(Player player) {
        return claimDailyReward(player, path -> usable(resolveRewardItem(path)),
            reward -> dispatchRewards(player, reward, "daily reward", config.rewardMultiplier()));
    }

    // The resolve check and the payout passed in, so the rules above can be
    // driven headless
    boolean claimDailyReward(Player player, Predicate<String> resolvable, Predicate<RewardEntry> pay) {
        // tasks(), not get(): a draw a reload has left short is made good
        // first, so it cannot count as "all revealed"
        PlayerData data = tasks(player.getUniqueId());
        if (data.dailyRewardClaimed() || !data.allRevealed()) {
            return false;
        }
        RewardEntry reward = dailyRewardFor(config.dailyRewards(), player);
        if (reward == null) {
            return false;
        }

        Messages messages = config.messages();
        String path = reward.items().get(0).path();
        boolean resolved;
        try {
            resolved = resolvable.test(path);
        } catch (Throwable t) {
            resolved = false;
        }
        if (!resolved) {
            // Once per path until a reload, like giveItems: this is reachable
            // on every open
            if (reportedItemPaths.add(path)) {
                plugin.getLogger().warning("Daily reward item '" + Utils.safeForLog(path) + "' could not be"
                    + " resolved for " + player.getUniqueId() + " - nothing was handed over; it is retried"
                    + " on the next /activity open.");
            }
            player.sendMessage(messages.get("reward-failed"));
            return false;
        }

        if (storeNeverLoaded("Refusing every daily reward")) {
            player.sendMessage(messages.get("reward-failed"));
            return false;
        }

        data.setDailyRewardClaimed(true);
        store.markDirty();
        if (!store.saveNow()) {
            data.setDailyRewardClaimed(false);
            store.markDirty();
            plugin.getLogger().severe("Handed nothing to " + player.getUniqueId() + ": the daily reward could"
                + " not be saved as claimed before it was paid, so nothing was handed over.");
            player.sendMessage(messages.get("reward-failed"));
            return false;
        }

        RewardEntry paid = withPaidAmount(reward, config.rewardMultiplier());
        if (!pay.test(paid)) {
            data.setDailyRewardClaimed(false);
            store.markDirty();
            store.saveSoon();
            plugin.getLogger().warning("Daily reward for " + player.getUniqueId() + " handed nothing over:"
                + " it is unclaimed again in memory, and on disk once the queued save lands.");
            player.sendMessage(messages.get("reward-failed"));
            return false;
        }

        player.sendMessage(messages.get("daily-reward-claimed", "%reward%", Utils.colorize(paid.display())));
        playSound(player, config.barCompleteSound());
        return true;
    }

    // ====================================
    // The reward of the first group, in config order, whose group.<name>
    // permission the player has - LuckPerms grants that to its members. Null
    // when he is in none of them.
    //
    // The node must be explicitly set, not just answered true: an unset node
    // falls back to its default, which is OP, so hasPermission alone hands
    // every operator the top group's reward. A '*' grant can still set it -
    // see the daily-reward comment in config.yml.
    // ====================================
    static RewardEntry dailyRewardFor(Map<String, RewardEntry> groups, Permissible player) {
        for (Map.Entry<String, RewardEntry> group : groups.entrySet()) {
            String node = "group." + group.getKey();
            if (player.isPermissionSet(node) && player.hasPermission(node)) {
                return group.getValue();
            }
        }
        return null;
    }

    // One weighted draw from the pool. Null only on an empty pool.
    private static RewardEntry draw(List<RewardEntry> pool) {
        int total = RewardEntry.totalWeight(pool);
        if (total <= 0) {
            return null;
        }
        return RewardEntry.pick(pool, ThreadLocalRandom.current().nextInt(total));
    }

    // ====================================
    // What stays burned when only part of the payout went out: the highest of
    // the milestones that actually paid, or where this click started when
    // none did. Never hands back a milestone paid before this click. Takes the
    // max of the paid prefix rather than assuming 'due' is sorted ascending,
    // so it stays correct even if a caller hands it an unsorted list.
    // ====================================
    static int rollbackClaimedPoints(int claimedBefore, int paid, List<Integer> due) {
        int cap = Math.min(paid, due.size());
        return cap <= 0 ? claimedBefore : Collections.max(due.subList(0, cap));
    }

    // ====================================
    // The pool entries that can pay this player at all: one whose commands can
    // be run for this name (see canRunCommand), or one that hands over
    // items - an item goes straight into the inventory and never has the name
    // pasted into it, so it pays a Bedrock/unsafe name like any other.
    // ====================================
    static List<RewardEntry> runnableEntries(List<RewardEntry> pool, String name) {
        List<RewardEntry> runnable = new ArrayList<>();
        for (RewardEntry entry : pool) {
            if (!entry.items().isEmpty()) {
                runnable.add(entry);
                continue;
            }
            for (String command : entry.commands()) {
                if (canRunCommand(command, name)) {
                    runnable.add(entry);
                    break;
                }
            }
        }
        return runnable;
    }

    // ====================================
    // One drawn entry handed over: its items, then its commands. True only if
    // something actually went out - the same rule dispatchCommands has always
    // used, now spanning both halves. An entry that gave its items but whose
    // commands all failed counts as paid on purpose: a milestone rolled back
    // is drawn and paid again on the next click, which would hand those items
    // over twice. A drawn entry that handed over nothing at all counts as
    // unpaid, so that milestone stays claimable.
    //
    // Both halves always run: the commands are not skipped just because the
    // items already succeeded.
    // ====================================
    private boolean dispatchRewards(Player player, RewardEntry entry, String at, int multiplier) {
        return dispatchRewards(
            () -> giveItems(player, entry, multiplier, at,
                this::resolveRewardItem, plugin.getLogger()),
            () -> dispatchCommands(player, entry.commands(), "reward", plugin.getLogger(), CONSOLE));
    }

    // The combine on its own, so the rule above can be pinned without a server
    // behind the command half
    static boolean dispatchRewards(BooleanSupplier items, BooleanSupplier commands) {
        boolean gaveItems = items.getAsBoolean();
        boolean ranCommands = commands.getAsBoolean();
        return gaveItems || ranCommands;
    }

    // ====================================
    // The stack behind one configured reward path, or null when nothing can be
    // built from it. m. paths go through TLibs exactly as the GUI's icons do,
    // and only while all of TLibs/MMOItems/MythicLib are enabled - touching
    // TLibsItems without them would fail on class initialisation, which is not
    // something resolve() can catch for us.
    //
    // ia. paths go through ItemsAdder the same way, on its own gate. Null out
    // of either branch is the existing "nothing could be built" answer, which
    // giveItems already treats as nothing handed over - so an ItemsAdder item
    // that stops resolving leaves its milestone unclaimed rather than burning
    // it for nothing.
    // ====================================
    private ItemStack resolveRewardItem(String path) {
        // Lambda, not a TLibsItems::item method reference: a reference would
        // resolve the TLibsItems class eagerly, ahead of the itemPathsUsable()
        // gate above, and reintroduce the class-init crash a server without
        // the TLibs trio would hit.
        return resolveRewardItem(path, config.itemPathsUsable(), config.itemsAdderUsable(),
            p -> TLibsItems.item(p), id -> ItemsAdderItems.item(id));
    }

    // The branch selection on its own, so the two gates can be pinned headless
    // in all four combinations: each path form must survive the other form's
    // backing plugin being absent
    static ItemStack resolveRewardItem(String path, boolean tlibs, boolean ia,
                                       Function<String, ItemStack> tlibsItems,
                                       Function<String, ItemStack> iaItems) {
        if (ItemPath.isPluginPath(path)) {
            return tlibs ? tlibsItems.apply(path) : null;
        }
        if (ItemPath.isItemsAdderPath(path)) {
            String id = ItemPath.itemsAdderId(path);
            return id != null && ia ? iaItems.apply(id) : null;
        }
        Material material = ItemPath.material(path);
        return material == null ? null : new ItemStack(material);
    }

    // ====================================
    // Can an inventory actually hold this? A block-only material (CARROTS,
    // WATER, FIRE, POWDER_SNOW) is turned into nothing by addItem, which then
    // reports no leftover - the entry would count as paid, the milestone would
    // stay burned and the player would be told he was paid having received
    // nothing. Asked of the resolved stack rather than of the bare-Material
    // branch alone, so an m.<type>.<id> built on such a material is refused by
    // the same guard.
    //
    // Material#isItem() reads the item registry, which only exists on a
    // running server - headless it throws on class initialisation. There the
    // stack is taken at face value: this guard exists to catch a payout on a
    // real server, and refusing every stack without a registry would mean
    // handing nothing over at all.
    // ====================================
    // A resolved stack an inventory can actually be handed: not nothing, not
    // air, not a block-only material. Compared against AIR rather than
    // through Material#isAir(), which needs the block registry of a running
    // server.
    static boolean usable(ItemStack stack) {
        return stack != null && stack.getType() != Material.AIR && isItem(stack);
    }

    static boolean isItem(ItemStack stack) {
        try {
            return stack.getType().isItem();
        } catch (Throwable t) {
            return true;
        }
    }

    // ====================================
    // Hands the items of one entry over, and answers whether anything actually
    // reached the player - the same "did any of it go out" rule dispatchCommands
    // uses. A path that no longer resolves (TLibs down, the MMOItems id deleted
    // since load) is logged and counts as nothing handed over, so an entry made
    // only of such paths fails its milestone and leaves it claimable.
    //
    // What does not fit is dropped at the player's feet rather than lost: a
    // reward that vanishes into a full inventory is a support ticket. This runs
    // on the main thread - claim() is only ever reached from the GUI's
    // InventoryClickEvent handler - so the inventory is touched inline.
    //
    // The resolved stack's own amount is whatever built it, so the total is set
    // on a clone rather than multiplied: 'amount: 3' at multiplier 2 means six
    // items (a fixed drop or the daily reward; a pool spin is paid at multiplier 1,
    // i.e. its amounts as written). That
    // total goes over as whole stacks of at most the resolved item's own
    // getMaxStackSize(), so six swords are six stacks of one and 192 diamonds
    // are three of 64. The clone also keeps a stack TLibs
    // might be holding on to out of reach.
    // ====================================
    // 'at' names what is being paid in the log lines: "milestone 20", or
    // "daily reward".
    static boolean giveItems(Player player, RewardEntry entry, int multiplier, String at,
                             Function<String, ItemStack> resolver, Logger logger) {
        boolean gaveAny = false;
        // Apart from gaveAny on purpose: gaveAny is set before addItem, so a
        // first insert that throws leaves it true having put nothing in the
        // inventory. Only a returned insert proves part of the entry actually
        // arrived, which is what the partial-payout warning below claims.
        boolean insertedAny = false;
        boolean missedAny = false;
        for (RewardEntry.Item item : entry.items()) {
            int total = item.amount() * multiplier;
            try {
                ItemStack stack = resolver.apply(item.path());
                if (!usable(stack)) {
                    missedAny = true;
                    // Memoised the way TLibsItems memoises a failing path: a
                    // claim can be repeated at click rate, and a path TLibs was
                    // never asked about (item paths unusable) memoises nowhere
                    // else
                    if (reportedItemPaths.add(item.path())) {
                        logger.warning("Reward item '" + Utils.safeForLog(item.path()) + "' (x" + total
                            + ", " + at + ") could not be resolved for "
                            + player.getUniqueId() + " - nothing was handed over for it.");
                    }
                    continue;
                }
                // The resolved item's own limit, not the vanilla 64: a stack of
                // 64 items whose max stack size is 1 is a dupe primitive in
                // every shift-click and crafting path that touches it after
                int max = Math.max(1, stack.getMaxStackSize());
                for (int left = total; left > 0; left -= max) {
                    ItemStack chunk = stack.clone();
                    chunk.setAmount(Math.min(left, max));
                    // Set before the inventory is touched, not after: addItem
                    // can throw having already filled some slots, and a
                    // milestone rolled back then would be drawn and paid again
                    // on the next click. A throwing drop must not un-pay it
                    // either.
                    gaveAny = true;
                    Map<Integer, ItemStack> leftovers = player.getInventory().addItem(chunk);
                    insertedAny = true;
                    for (ItemStack leftover : leftovers.values()) {
                        // Owner-locked for the first seconds, so the overflow
                        // cannot be picked up by whoever happens to be standing
                        // next to the claimer
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover, drop -> {
                            drop.setOwner(player.getUniqueId());
                            drop.setThrower(player.getUniqueId());
                        });
                    }
                }
            } catch (Throwable t) {
                // Same reason dispatchCommands swallows: claim() has already
                // burned and saved the milestones and must be able to put them
                // back rather than unwind through this loop.
                missedAny = true;
                logger.severe("Reward item '" + Utils.safeForLog(item.path()) + "' (x" + total
                    + ", " + at + ") threw for " + player.getUniqueId() + ": " + t);
            }
        }

        // Part of an entry went out and part did not. The milestone stays
        // burned on purpose - rolling it back would hand the part that did go
        // out over a second time - so the only way the rest ever reaches the
        // player is an operator reading this line.
        if (insertedAny && missedAny) {
            logger.warning("Only part of reward '" + Utils.safeForLog(entry.display()) + "' reached "
                + player.getUniqueId() + " at " + at + " - it stays claimed,"
                + " so hand the rest over by hand.");
        }
        if (gaveAny) {
            try {
                // The inventory was changed inside a cancelled
                // InventoryClickEvent, which leaves the client showing ghost
                // stacks until it reopens
                player.updateInventory();
            } catch (Throwable t) {
                // The one Bukkit call here that used to sit outside a try. It
                // must not unwind either: claim() has already burned and saved
                // the milestones and would skip its rollback, leaving disk
                // claiming milestones nobody was ever paid for. A stale client
                // view fixes itself on the next window open.
                logger.warning("Could not resync the inventory of " + player.getUniqueId()
                    + " after reward '" + Utils.safeForLog(entry.display()) + "' at " + at
                    + ": " + t);
            }
        }
        return gaveAny;
    }

    // What a dispatch actually runs on a live server. Passed in rather than
    // called directly so the loop below can be driven headless - the same
    // seam giveItems uses for its item resolver.
    // A Predicate rather than a Consumer so dispatchCommand's own answer is
    // captured: it returns false for a command the server does not know or
    // refused, which is otherwise indistinguishable from one that worked.
    private static final Predicate<String> CONSOLE =
        command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

    // ====================================
    // True only if at least one command actually ran. The name check is per
    // command rather than per player: an unsafe name skips the ones that paste
    // it and leaves the %uuid%-only ones working.
    //
    // 'what' names the kind of command in the log line - "reward" for a
    // milestone payout, "click" for an activity's click-commands - so one
    // dispatcher serves both without either lying about the other.
    //
    // A command the console refused (dispatchCommand returned false: unknown,
    // or the plugin behind it said no) still counts as run, which is what it
    // has always counted as and what the reward payout's burn-save-pay rule
    // is built on - a milestone rolled back is drawn and paid again. It is
    // reported, though, and only once per kind and command: every other
    // outcome here is bounded by something, and this one is reachable on
    // every click of a stock config on a server without CMI or EssentialsX.
    // ====================================
    static boolean dispatchCommands(Player player, List<String> commands, String what, Logger logger,
                                    Predicate<String> console) {
        String name = player.getName();
        String uuid = player.getUniqueId().toString();
        boolean ranAny = false;

        for (String command : commands) {
            if (!canRunCommand(command, name)) {
                logger.warning("Skipping " + what + " command '" + Utils.safeForLog(String.valueOf(command))
                    + "' for '" + Utils.safeForLog(name) + "': the name is not one that can be safely pasted"
                    + " into a console command.");
                continue;
            }

            // ====================================
            // A configured command runs somebody else's plugin, which is free
            // to throw. Letting that unwind would leave claim() with the
            // thresholds already burned and saved and no chance to put them
            // back, so a throwing command counts as "did not run" and stops
            // the payout at the milestone it broke on. The click path needs
            // the same guard for a different reason: an InventoryClickEvent
            // handler that throws leaves the click half-handled.
            // ====================================
            try {
                if (!console.test(command.replace("%player%", name).replace("%uuid%", uuid))
                    && reportOnce(what, command)) {
                    logger.warning("The " + what + " command '" + Utils.safeForLog(String.valueOf(command))
                        + "' was refused by the console - it is probably unknown on this server. Not"
                        + " reported again until /activity reload.");
                }
                ranAny = true;
            } catch (Throwable t) {
                // Keyed apart from the refusal above: the two are different
                // failures, and sharing one key means a command that throws
                // once and is refused ever after is never reported refused
                if (reportOnce(what + "-threw", command)) {
                    logger.severe("A " + what + " command '" + Utils.safeForLog(String.valueOf(command))
                        + "' threw for " + Utils.safeForLog(name) + ": " + t + ". Not reported again until"
                        + " /activity reload.");
                }
            }
        }

        return ranAny;
    }

    // True the first time this kind of command is reported broken, false
    // every time after, until reload() empties the set
    private static boolean reportOnce(String what, String command) {
        return reportedBrokenCommands.add(what + "|" + command);
    }

    // ====================================
    // An activity's 'click-commands', run for the player who clicked the task
    // in this slot (0-based). True only when something was actually
    // dispatched, so the caller knows whether to play its click sound.
    //
    // The slot is resolved and checked here rather than by the caller: the
    // whole safety of this path is that only a task the player has already
    // revealed can make the console run anything, and that invariant belongs
    // with the data it is about, the way reveal(UUID, int) owns its own slot
    // lookup. False covers a slot showing filler, an activity a reload has
    // dropped, a task that is still hidden, one that configures no
    // click-commands, and every refusal of the two rate limits below.
    //
    // Nothing is dispatched on this tick: the close and the dispatch are both
    // scheduled for the next one, by runClickCommandsNow below.
    //
    // So "true" here means scheduled, not dispatched - and the caller's click
    // sound plays on this tick, before the console has been asked anything.
    // Deliberate: the sound is the GUI's acknowledgement that the click was
    // accepted rather than refused by a rate limit, and that is exactly what
    // is known now. Waiting a tick to make it the result of the dispatch
    // would cost perceived responsiveness on the one control in this menu
    // that does something, and would still not mean much - a command the
    // console refuses counts as run here, so "dispatched" is not "worked".
    // ====================================
    public boolean runClickCommands(Player player, int slot) {
        PlayerData data = tasks(player.getUniqueId());
        List<String> drawn = data.tasks();
        if (slot < 0 || slot >= drawn.size()) {
            return false;
        }

        String id = drawn.get(slot);
        ActivityDef def = config.activity(id);
        if (def == null || !data.isRevealed(id)) {
            return false;
        }

        // ====================================
        // What will actually be dispatched, decided before anything is
        // committed: a click whose every command is skipped by the name guard
        // ran nothing, and must not cost a sound, a closed menu or a place in
        // either rate limit. The per-click cap is here too, since the
        // cooldown bounds clicks and not commands - a 20-entry list would
        // otherwise multiply the cost of every click by twenty.
        // ====================================
        List<String> commands = runnableClickCommands(def, player.getName(), config.clickCommandsPerClick(),
            plugin.getLogger());
        if (commands.isEmpty()) {
            return false;
        }

        long now = System.nanoTime();
        // Peeked before the cooldown is burned and taken after it passed, so
        // neither refusal costs the other anything: a click the server-wide
        // budget refused leaves the player's cooldown untouched, and one the
        // player's own cooldown refused does not spend a token other players
        // are queueing for
        if (!clickBudget(now, config.clickCommandsPerSecond(), false)) {
            return false;
        }
        // ====================================
        // The cooldown is burned here, where the dispatch is committed, and
        // not for a click that ran nothing. A command that is dispatched and
        // then throws or is refused still burns it: the console has already
        // been made to do work, which is the thing being rate-limited, and
        // not burning it would let a permanently broken command be retried at
        // click rate - exactly what these limits exist to stop.
        // ====================================
        if (!clickCooldownPassed(player.getUniqueId(), now,
            config.clickCommandCooldownMillis() * 1_000_000L)) {
            return false;
        }
        clickBudget(now, config.clickCommandsPerSecond(), commands.size(), true);

        return schedule(() -> runClickCommandsNow(player, commands, id, CONSOLE));
    }

    // ====================================
    // The tick after the click: close the menu, then dispatch. In that order
    // because config.yml promises the menu is gone before the command runs,
    // so a command that opens a GUI of its own keeps it; done the other way
    // round the close lands on whatever window the command opened and shuts
    // that instead.
    //
    // The window is identified by its holder and not by comparing the
    // InventoryView the click came from: nothing in Bukkit promises
    // getOpenInventory() hands back the same object twice, and a container
    // that returns a fresh wrapper per call would leave the menu silently
    // never closing. Asking the same question ActivityGui.onClick asks means
    // a player who closed the menu himself in the meantime keeps whatever he
    // opened instead, and a player who reopened the activity menu has that
    // one closed - which is the wanted outcome anyway.
    //
    // Package-private and taking the console call rather than reaching for
    // Bukkit, so the ordering and the audit line can be driven headless - the
    // same seam dispatchCommands uses.
    // ====================================
    void runClickCommandsNow(Player player, List<String> commands, String id, Predicate<String> console) {
        InventoryView open = player.getOpenInventory();
        if (open != null && open.getTopInventory().getHolder() instanceof ActivityGui.Marker) {
            player.closeInventory();
        }

        Logger logger = plugin.getLogger();
        String name = player.getName();
        UUID uuid = player.getUniqueId();

        dispatchCommands(player, commands, "click", logger, command -> {
            // ====================================
            // The audit line every staff command already writes, for the one
            // console dispatch a player can set off himself. A
            // console-dispatched command is not written to the server log the
            // way a player-issued one is, so without this nothing links the
            // player to what he caused.
            //
            // Emitted from a finally so a command that throws still leaves a
            // per-player trace - that being exactly the case an operator
            // would be investigating, and the one the once-per-uptime severe
            // above says the least about.
            // ====================================
            String result = "threw";
            try {
                boolean ran = console.test(command);
                result = ran ? "done" : "refused";
                return ran;
            } finally {
                logger.info("ACTIVITY-AUDIT sender=" + Utils.quotedForLog(name) + " uuid=" + uuid
                    + " action=click-command activity=" + Utils.quotedForLog(id)
                    + " command=" + Utils.quotedForLog(command) + " result=" + result);
            }
        });
    }

    // ====================================
    // The commands of one click that can actually be run for this name, at
    // most 'max' of them. The name guard is the dispatcher's own
    // (canRunCommand), asked early so a click that would run nothing can be
    // refused before it costs anything; the dispatcher asks again for each
    // one it runs, which is where the skip is logged.
    // ====================================
    static List<String> runnableClickCommands(ActivityDef def, String name, int max, Logger logger) {
        if (def == null) {
            return List.of();
        }
        List<String> runnable = new ArrayList<>();
        for (String command : def.clickCommands()) {
            if (!canRunCommand(command, name)) {
                continue;
            }
            if (runnable.size() == max) {
                if (reportOnce("click-cap", def.id())) {
                    logger.warning("Activity '" + Utils.safeForLog(def.id()) + "' lists more than " + max
                        + " click-commands - only the first " + max + " are run (see"
                        + " click-commands-per-click). Not reported again until /activity reload.");
                }
                break;
            }
            runnable.add(command);
        }
        return runnable;
    }

    // ====================================
    // Next tick's work, handed to the scheduler behind a guard: a plugin that
    // is disabling (a /reload or a shutdown mid-tick) throws
    // IllegalPluginAccessException out of runTask, which would unwind through
    // the click handler and leave the click half-handled - the exact failure
    // the ordering above exists to avoid. Nothing was dispatched then, so the
    // caller is told so.
    // ====================================
    private boolean schedule(Runnable work) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, work);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not schedule an activity click's commands: " + t);
            return false;
        }
    }

    // ====================================
    // The server-wide ceiling, refilled at 'perSecond' tokens a second and
    // holding at most one second's worth, so a burst is bounded by the same
    // number it sustains. Peeked with take=false and spent with take=true.
    // Package-private and taking its clock reading so it can be pinned
    // without a clock to wait on.
    //
    // A token is a command and not a click: config.yml documents the knob as
    // a ceiling on commands, and a click dispatching five of them costs the
    // console five commands' worth of main thread.
    //
    // 'cost' is what a take spends, and the bucket is allowed to go negative
    // paying it - the gate is one whole token, not 'cost' of them. Refusing a
    // click outright when it wants more than the bucket holds would make a
    // legal configuration (click-commands-per-second below
    // click-commands-per-click) silently never run anything, forever, with no
    // diagnostic. Overdrawing instead keeps the long-run rate at exactly
    // 'perSecond': the debt has to be refilled before the next click is
    // allowed, and it is bounded by one click's cap (at most 50), so the
    // bucket always recovers.
    // ====================================
    boolean clickBudget(long now, int perSecond, boolean take) {
        return clickBudget(now, perSecond, 1, take);
    }

    boolean clickBudget(long now, int perSecond, int cost, boolean take) {
        if (!clickTokensPrimed) {
            clickTokensPrimed = true;
            clickTokensAt = now;
            clickTokens = perSecond;
        }
        clickTokens = Math.min(perSecond, clickTokens + (now - clickTokensAt) / 1e9 * perSecond);
        clickTokensAt = now;
        if (clickTokens < 1) {
            return false;
        }
        if (take) {
            clickTokens -= cost;
        }
        return true;
    }

    // The rate limit on its own, so it can be pinned without a clock to wait
    // on. Per player rather than per activity: what is being bounded is how
    // often one player can make the console run anything.
    boolean clickCooldownPassed(UUID uuid, long now, long intervalNanos) {
        Long last = clickCooldowns.get(uuid);
        if (last != null && now - last < intervalNanos) {
            return false;
        }
        clickCooldowns.put(uuid, now);
        return true;
    }

    // ====================================
    // Called from the GUI's quit handler. Without it the map would keep one
    // entry per player who ever clicked a task, for the whole uptime.
    // ====================================
    public void forgetClickCooldown(UUID uuid) {
        clickCooldowns.remove(uuid);
    }

    // Package-private for the eviction test; nothing else reads it
    int clickCooldownEntries() {
        return clickCooldowns.size();
    }

    // A joiner who has never scored anything gets no entry: rolled() reads
    // what is already there rather than creating a row per player who ever
    // logged in
    public void onJoin(Player player) {
        PlayerData data = store.rolled(player.getUniqueId());
        if (data != null && data.claimable(config.milestones()) > 0) {
            player.sendMessage(config.messages().get("reward-ready"));
        }
    }

    public void reload() {
        config.load();
        // An operator who fixed rewards.pool deserves to hear about it again
        // if they got it wrong twice
        warnedEmptyPool = false;
        reportedItemPaths.clear();
        // The hook's own memo of ids whose call threw is otherwise permanent
        // for the JVM: this is the only way short of a restart to give an id
        // another chance once ItemsAdder is back
        ItemsAdderItems.reset();
        // An operator who fixed a broken command deserves to hear about it
        // again if it is still broken
        reportedBrokenCommands.clear();
    }

    private void playSound(Player player, String soundKey) {
        if (soundKey != null) {
            player.playSound(player.getLocation(), soundKey, 1.0f, 1.0f);
        }
    }
}
