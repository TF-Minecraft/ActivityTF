package tfmc.justin.activity.listeners;

import com.nowko.archeology.events.FindRecoveredEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

// ====================================
// Archaeo fires FindRecoveredEvent synchronously on the main thread. Only
// constructed when Archaeo is enabled - see ActivityPlugin.
// ====================================
public class ArchaeologyListener implements Listener {

    private final ActivityManager manager;

    public ArchaeologyListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFindRecovered(FindRecoveredEvent event) {
        // The source plugin builds the event itself; a null player would
        // only be a bug there, but it must not take this listener down
        if (event.getPlayer() == null) {
            return;
        }

        manager.recordAction(event.getPlayer().getUniqueId(), "archaeology_find", 1);
    }
}
