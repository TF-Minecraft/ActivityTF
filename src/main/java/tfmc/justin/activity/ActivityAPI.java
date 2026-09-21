package tfmc.justin.activity;

import org.bukkit.Bukkit;
import tfmc.justin.activity.managers.ActivityManager;

import java.util.UUID;

public final class ActivityAPI {

    private ActivityAPI() {
    }

    public static void record(UUID uuid, String activityId, int amount) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ActivityAPI.record must be called on the main thread");
        }

        ActivityManager manager = ActivityManager.getInstance();
        if (manager != null) {
            manager.recordAction(uuid, activityId, amount);
        }
    }
}
