package tfmc.justin.activity;

import org.bukkit.Bukkit;
import tfmc.justin.activity.managers.ActivityManager;

import java.util.UUID;

// ====================================
// The intake path for plugins that compile against this jar. The other two
// are the custom events and /activity add, so nothing has to depend on us.
// ====================================
public final class ActivityAPI {

    private ActivityAPI() {
    }

    /**
     * Record progress towards an activity for a player.
     * <p>
     * Main thread only. Unknown activity ids and non-positive amounts are ignored.
     *
     * @param uuid       the player, online or not
     * @param activityId an id from the 'activities' section of config.yml
     * @param amount     how much progress to add
     * @throws IllegalStateException if called off the main thread
     */
    public static void record(UUID uuid, String activityId, int amount) {
        // Loud rather than a corrupted store: everything downstream mutates
        // player state without a lock because it assumes it is on a tick
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ActivityAPI.record must be called on the main thread");
        }

        ActivityManager manager = ActivityManager.getInstance();
        if (manager != null) {
            manager.recordAction(uuid, activityId, amount);
        }
    }
}
