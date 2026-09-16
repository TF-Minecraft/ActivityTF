package tfmc.justin.activity.listeners;

import net.tfminecraft.cooking.events.DishCookedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

// ====================================
// Cooking fires DishCookedEvent synchronously on the main thread. Only
// constructed when Cooking is enabled - see ActivityPlugin.
// ====================================
public class DishCookedListener implements Listener {

    private final ActivityManager manager;

    public DishCookedListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDishCooked(DishCookedEvent event) {
        // The source plugin builds the event itself; a null player would
        // only be a bug there, but it must not take this listener down
        if (event.getPlayer() == null) {
            return;
        }

        manager.recordAction(event.getPlayer().getUniqueId(), "cook_dish", 1);
    }
}
