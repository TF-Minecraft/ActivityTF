package tfmc.justin.activity.managers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.config.Messages;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.models.RecordResult;
import tfmc.justin.activity.models.Recorded;
import tfmc.justin.activity.models.RewardEntry;
import tfmc.justin.activity.store.PlayerStore;
import tfmc.justin.activity.utils.Utils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
    private boolean warnedStoreNotLoaded;

    // Same idea for an empty reward pool, which a player can hit at click rate
    // - said once per load rather than once per click
    private boolean warnedEmptyPool;

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
    // Whether one reward command can be run for one player. Only a command
    // that pastes %player% needs a name that survives being pasted - a
    // %uuid%-only command runs for a Bedrock name with a space in it just fine.
    // ====================================
    public static boolean canRunRewardCommand(String command, String name) {
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
        return record(uuid, activityId, amount, true);
    }

    // /activity add --force only: skips the daily-task gate on purpose, so an
    // admin can credit any activity while testing, drawn or not
    public Recorded recordActionUngated(UUID uuid, String activityId, int amount) {
        return record(uuid, activityId, amount, false);
    }

    private Recorded record(UUID uuid, String activityId, int amount, boolean gated) {
        ActivityDef def = config.activity(activityId);
        if (def == null || amount <= 0) {
            return Recorded.UNKNOWN;
        }

        // ====================================
        // The gated path never draws and never creates a row: a listener
        // firing for a player who has not opened the GUI today must not pin
        // that player in players.yml forever. No draw yet means no revealed
        // task, which means nothing to record.
        // ====================================
        PlayerData data = gated ? store.rolled(uuid) : store.get(uuid);
        if (gated && (data == null || !data.isRevealed(activityId))) {
            return Recorded.NOT_A_TASK;
        }

        RecordResult result = data.record(amount, def, config.barMax(), config.dailyMax(), config.milestones());
        store.markDirty();

        // A full bar or a met daily cap awards nothing, and "+0" is worse
        // than silence
        if (result.pointsAwarded() <= 0) {
            return result.outcome();
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return result.outcome();
        }

        Messages messages = config.messages();
        player.sendMessage(messages.get("points-earned",
            "%activity%", Utils.colorize(def.display()),
            "%points%", result.pointsAwarded(),
            "%total%", data.points(),
            "%max%", config.barMax()));
        playSound(player, config.goalCompleteSound());

        if (result.milestonesReached() > 0) {
            player.sendMessage(messages.get("reward-ready"));
            playSound(player, config.barCompleteSound());
        }
        return result.outcome();
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
        return data.ensureTasks(config.activities().stream().map(ActivityDef::id).toList(),
            config.guaranteed(), ThreadLocalRandom.current());
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
    // Hands over every milestone the player has reached but not yet claimed,
    // one draw from the reward pool per milestone. Only ever called for an
    // online player (a GUI click), so %player% always resolves. Returns how
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
        if (!store.isLoaded()) {
            if (!warnedStoreNotLoaded) {
                warnedStoreNotLoaded = true;
                plugin.getLogger().severe("Refusing every reward claim: " + PlayerStore.FILE
                    + " was never loaded, so nothing handed over could be saved.");
            }
            player.sendMessage(messages.get("reward-failed"));
            return 0;
        }

        PlayerData data = store.get(player.getUniqueId());
        List<Integer> due = PlayerData.due(data.points(), data.claimedPoints(), config.milestones());
        if (due.isEmpty()) {
            return 0;
        }

        List<RewardEntry> pool = config.rewardPool();

        // Nothing configured to hand over: burning the milestones here would
        // pay the player in silence. Load already warned about this, but a
        // claim is the moment an operator can tie the warning to a player.
        if (pool.isEmpty()) {
            if (!warnedEmptyPool) {
                warnedEmptyPool = true;
                plugin.getLogger().warning("Handed nothing to " + player.getUniqueId()
                    + ": rewards.pool has no usable entry, so the milestone stays claimable.");
            }
            return 0;
        }

        // A name no entry in the pool can pay is a failure the player should
        // hear about, and it is answered here - before any mutation or save -
        // because it is the one refusal a player can trigger at click rate.
        // Entries that can pay only sometimes (a mix of %player% and %uuid%
        // commands) stay in; only the ones this name cannot pay at all drop out.
        List<RewardEntry> runnablePool = runnableEntries(pool, player.getName());
        if (runnablePool.isEmpty()) {
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

        int paid = 0;
        for (int i = 0; i < due.size(); i++) {
            RewardEntry drawn = draw(runnablePool);
            if (drawn == null || !dispatchRewards(player, drawn.commands())) {
                break;
            }
            paid++;
            player.sendMessage(messages.get("reward-claimed", "%reward%", Utils.colorize(drawn.display())));
        }

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
            if (!store.saveNow()) {
                plugin.getLogger().severe("Reward payout for " + player.getUniqueId() + " is out of sync:"
                    + " paid " + paid + " of " + due.size() + " milestones, claimed-points is " + rolledBack
                    + " in memory but " + claimedAfter + " on disk. Repair " + PlayerStore.FILE + " by hand.");
            }
            player.sendMessage(messages.get("reward-failed"));
            return paid;
        }

        playSound(player, config.barCompleteSound());
        return paid;
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

    // The pool entries at least one of whose commands can be run for this
    // player's name - see canRunRewardCommand.
    static List<RewardEntry> runnableEntries(List<RewardEntry> pool, String name) {
        List<RewardEntry> runnable = new ArrayList<>();
        for (RewardEntry entry : pool) {
            for (String command : entry.commands()) {
                if (canRunRewardCommand(command, name)) {
                    runnable.add(entry);
                    break;
                }
            }
        }
        return runnable;
    }

    // ====================================
    // True only if at least one command actually ran. The name check is per
    // command rather than per player: an unsafe name skips the ones that paste
    // it and leaves the %uuid%-only ones working. A drawn entry none of whose
    // commands could run counts as unpaid, so that milestone stays claimable.
    // ====================================
    private boolean dispatchRewards(Player player, List<String> commands) {
        String name = player.getName();
        String uuid = player.getUniqueId().toString();
        boolean ranAny = false;

        for (String command : commands) {
            if (!canRunRewardCommand(command, name)) {
                plugin.getLogger().warning("Skipping reward command '" + command + "' for '"
                    + Utils.safeForLog(name) + "': the name is not one that can be safely pasted into a"
                    + " console command.");
                continue;
            }

            // ====================================
            // A reward command runs somebody else's plugin, which is free to
            // throw. Letting that unwind would leave claim() with the
            // thresholds already burned and saved and no chance to put them
            // back, so a throwing command counts as "did not run" and stops
            // the payout at the milestone it broke on.
            // ====================================
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    command.replace("%player%", name).replace("%uuid%", uuid));
                ranAny = true;
            } catch (Throwable t) {
                plugin.getLogger().severe("Reward command '" + command + "' threw for "
                    + Utils.safeForLog(name) + ": " + t);
            }
        }

        return ranAny;
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
    }

    private void playSound(Player player, String soundKey) {
        if (soundKey != null) {
            player.playSound(player.getLocation(), soundKey, 1.0f, 1.0f);
        }
    }
}
