package tfmc.justin.activity.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.events.GeigerSourceCollectEvent;

// ====================================
// geiger_counter fires GeigerSourceCollectEvent synchronously on the main
// thread. Only constructed when geiger_counter is enabled - see
// ActivityPlugin.
// ====================================
public class GeigerListener implements Listener {

    private final ActivityManager manager;

    public GeigerListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGeigerSourceCollect(GeigerSourceCollectEvent event) {
        // The source plugin builds the event itself; a null player would
        // only be a bug there, but it must not take this listener down
        if (event.getPlayer() == null) {
            return;
        }

        manager.recordAction(event.getPlayer().getUniqueId(), "geiger", 1);
    }
}
