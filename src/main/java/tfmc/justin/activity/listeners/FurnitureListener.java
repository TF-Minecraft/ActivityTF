package tfmc.justin.activity.listeners;

import net.tfminecraft.events.FurniturePlaceEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

// ====================================
// InteractibleFurniture fires FurniturePlaceEvent synchronously on the main
// thread. Only constructed when InteractibleFurniture is enabled - see
// ActivityPlugin.
//
// MONITOR + ignoreCancelled: nothing was placed if the event was cancelled.
// ====================================
public class FurnitureListener implements Listener {

    private final ActivityManager manager;

    public FurnitureListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent event) {
        // The event carries its own hasPlayer() check, so furniture placed by
        // the plugin rather than by someone is skipped
        if (!event.hasPlayer() || event.getPlayer() == null) {
            return;
        }

        manager.recordAction(event.getPlayer().getUniqueId(), "furniture_place", 1);
    }
}
