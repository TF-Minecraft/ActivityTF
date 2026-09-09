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

    private static ActivityManager instance;

    private final JavaPlugin plugin;
    private final ActivityConfiguration config;
    private final PlayerStore store;

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
        RecordResult result = data.record(activityId, amount, def.dailyGoal(), def.points());
        store.markDirty();

        // A goal met while the bar is already full awards nothing, and
        // "+0 points" is worse than silence
        if (result.goalJustMet() && result.pointsAwarded() > 0) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                Messages messages = config.messages();
                player.sendMessage(messages.get("goal-complete",
                    "%activity%", Utils.colorize(def.display()),
                    "%points%", result.pointsAwarded(),
                    "%total%", data.points()));
                playSound(player, config.goalCompleteSound());
            }
        }

        if (result.hitHundred()) {
            grantRewards(uuid, true);
        }

        return true;
    }

    // ====================================
    // Rewards are console commands, so they need the player online to be
    // meaningful (%player% has to resolve, and most give-commands need a
    // target). Offline players get a flag and collect on join.
    //
    // 'announce' is off on the join path, where reward-pending already says
    // what happened and a second "COMPLETE!" title would be noise.
    //
    // Returns true once the week is marked rewarded and the player has been
    // notified; false only when the player is offline (pending) or the week
    // was already rewarded.
    // ====================================
    public boolean grantRewards(UUID uuid, boolean announce) {
        PlayerData data = store.get(uuid);
        if (data.rewarded()) {
            return false;
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            data.setPendingReward(true);
            store.markDirty();
            return false;
        }

        // ====================================
        // Flagged and written before the commands run: a reward command that
        // loops back into this plugin must find the week already marked as
        // paid out, and a crash between the give and the next autosave tick
        // would otherwise hand the whole lot out a second time.
        // ====================================
        data.setRewarded(true);
        data.setPendingReward(false);
        store.markDirty();
        store.saveSoon();

        // ====================================
        // An empty rewards.commands list means there is nothing configured to
        // hand over - not a failure, so it stays silent. Otherwise, if every
        // command was skipped by the unsafe-name check, the name pasted into
        // %player% will never become safe, so retrying can never succeed:
        // warn once and leave the week marked rewarded rather than re-arming
        // a pending-reward that would just fail again on every future join.
        // ====================================
        if (!config.rewardCommands().isEmpty() && !dispatchRewards(player)) {
            plugin.getLogger().warning("No reward command could be run for '" + player.getName()
                + "': the name cannot be safely pasted into a console command. Use %uuid%-based"
                + " reward commands to support Bedrock/unsafe names.");
        }

        if (announce) {
            Messages messages = config.messages();
            player.sendMessage(messages.get("bar-complete"));
            player.sendTitle(messages.get("bar-complete-title"), messages.get("bar-complete-subtitle"), 10, 60, 20);
            playSound(player, config.barCompleteSound());
        }
        return true;
    }

    // ====================================
    // True only if at least one command actually ran. The name check is per
    // command rather than per player: an unsafe name skips the ones that paste
    // it and leaves the %uuid%-only ones working.
    // ====================================
    private boolean dispatchRewards(Player player) {
        String name = player.getName();
        String uuid = player.getUniqueId().toString();
        boolean ranAny = false;

        for (String command : config.rewardCommands()) {
            if (!canRunRewardCommand(command, name)) {
                plugin.getLogger().warning("Skipping reward command '" + command + "' for '" + name
                    + "': the name is not one that can be safely pasted into a console command.");
                continue;
            }

            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                command.replace("%player%", name).replace("%uuid%", uuid));
            ranAny = true;
        }

        return ranAny;
    }

    public void onJoin(Player player) {
        PlayerData data = store.get(player.getUniqueId());
        if (!data.pendingReward()) {
            return;
        }

        // Cleared before granting so a player who somehow carries both flags
        // does not get the pending message on every single join
        data.setPendingReward(false);
        store.markDirty();
        if (data.rewarded()) {
            return;
        }

        // Only announce what was actually handed over
        if (grantRewards(player.getUniqueId(), false)) {
            player.sendMessage(config.messages().get("reward-pending"));
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
