package tfmc.justin.activity.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.events.GeigerSourceCollectEvent;

public class GeigerListener implements Listener {

    private final ActivityManager manager;

    public GeigerListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGeigerSourceCollect(GeigerSourceCollectEvent event) {
        if (event.getPlayer() == null) {
            return;
        }

        manager.recordAction(event.getPlayer().getUniqueId(), "geiger", 1);
    }
}
