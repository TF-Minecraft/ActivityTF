package tfmc.justin.activity.managers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.config.Messages;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.models.RecordResult;
import tfmc.justin.activity.store.PlayerStore;
import tfmc.justin.activity.utils.Utils;

import java.util.List;
import java.util.UUID;
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

    // Nulled by shutdown() on the main thread while a vote can still be in
    // flight on VotingPlugin's own thread
    private static volatile ActivityManager instance;

    private final JavaPlugin plugin;
    private final ActivityConfiguration config;
    private final PlayerStore store;

    // A store that never loaded refuses every save for the rest of the
    // session, so saying so once per session is enough
    private boolean warnedStoreNotLoaded;

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
    }

    public void shutdown() {
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
    // Record progress towards an activity. Returns false for an unknown id or
    // a non-positive amount, so the admin command can say which it was;
    // callers that cannot act on it (events, the API) just ignore the result.
    // ====================================
    public boolean recordAction(UUID uuid, String activityId, int amount) {
        ActivityDef def = config.activity(activityId);
        if (def == null || amount <= 0) {
            return false;
        }

        PlayerData data = store.get(uuid);
        RecordResult result = data.record(amount, def, config.barMax(), config.rewardEvery());
        store.markDirty();

        // A full bar or a met daily cap awards nothing, and "+0" is worse
        // than silence
        if (result.pointsAwarded() <= 0) {
            return true;
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return true;
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
        return true;
    }

    // ====================================
    // Hands over every milestone the player has reached but not yet claimed.
    // Only ever called for an online player (a GUI click), so %player% always
    // resolves. Returns how many milestones were actually paid: 0 covers both
    // "nothing was due" and every refusal below, all of which leave the
    // thresholds there to claim once an operator has fixed what broke.
    //
    // The order is the whole point, because a reward has real in-game value:
    //
    //   1. Every reason to refuse is checked before anything is touched. The
    //      unsafe-name case especially - that is what a Bedrock player clicking
    //      the chest over and over hits, and it must not cost a disk write.
    //   2. The thresholds are burned and written to disk BEFORE the first
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
    // The command list is snapshotted once, so a reward command that runs
    // /activity reload cannot change what the rest of the loop hands out.
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
        int every = config.rewardEvery();
        int due = data.claimable(every);
        if (due == 0) {
            return 0;
        }

        List<String> commands = List.copyOf(config.rewardCommands());

        // Nothing configured to hand over: burning the thresholds here would
        // pay the player in silence. Load already warned about this.
        if (commands.isEmpty()) {
            return 0;
        }

        // A name no command can take is a failure the player should hear
        // about, and it is answered here - before any mutation or save -
        // because it is the one refusal a player can trigger at click rate
        if (!canRunAnyRewardCommand(commands, player.getName())) {
            plugin.getLogger().warning("No reward command could be run for '"
                + Utils.safeForLog(player.getName()) + "': the name cannot be safely pasted into a console"
                + " command. Use %uuid%-based reward commands to support Bedrock/unsafe names.");
            player.sendMessage(messages.get("reward-failed"));
            return 0;
        }

        int claimedBefore = data.claimedPoints();
        int claimedAfter = nextClaimedPoints(data.points(), every);
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
        for (int i = 0; i < due; i++) {
            if (!dispatchRewards(player, commands)) {
                break;
            }
            paid++;
        }

        // ====================================
        // Only what actually went out stays paid for. The second save is the
        // one that can leave memory and disk disagreeing, and the only honest
        // thing to do about it is to say exactly what each holds so an
        // operator can put the file right by hand.
        // ====================================
        if (paid < due) {
            int rolledBack = rollbackClaimedPoints(claimedBefore, paid, every);
            data.setClaimedPoints(rolledBack);
            store.markDirty();
            if (!store.saveNow()) {
                plugin.getLogger().severe("Reward payout for " + player.getUniqueId() + " is out of sync:"
                    + " paid " + paid + " of " + due + " milestones, claimed-points is " + rolledBack
                    + " in memory but " + claimedAfter + " on disk. Repair " + PlayerStore.FILE + " by hand.");
            }
            player.sendMessage(messages.get("reward-failed"));
            return paid;
        }

        player.sendMessage(messages.get("reward-claimed", "%count%", paid));
        playSound(player, config.barCompleteSound());
        return paid;
    }

    // The threshold that every point on the bar has now been paid for
    static int nextClaimedPoints(int points, int every) {
        return points / every * every;
    }

    // What stays burned when only part of the payout went out. Built up from
    // claimedBefore rather than down from the new threshold, so a failure can
    // never hand back a milestone that was paid before this click.
    static int rollbackClaimedPoints(int claimedBefore, int paid, int every) {
        return claimedBefore + paid * every;
    }

    private static boolean canRunAnyRewardCommand(List<String> commands, String name) {
        for (String command : commands) {
            if (canRunRewardCommand(command, name)) {
                return true;
            }
        }
        return false;
    }

    // ====================================
    // True only if at least one command actually ran. The name check is per
    // command rather than per player: an unsafe name skips the ones that paste
    // it and leaves the %uuid%-only ones working. The list is handed in so
    // every milestone of one claim pays out of the same snapshot.
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
        if (data != null && data.claimable(config.rewardEvery()) > 0) {
            player.sendMessage(config.messages().get("reward-ready"));
        }
    }

    public void reload() {
        config.load();
    }

    private void playSound(Player player, String soundKey) {
        if (soundKey != null) {
            player.playSound(player.getLocation(), soundKey, 1.0f, 1.0f);
        }
    }
}
