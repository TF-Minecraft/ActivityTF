package net.tfminecraft.activitytf.listeners;

import net.tfminecraft.cooking.events.DishCookedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.tfminecraft.activitytf.managers.ActivityManager;

public class DishCookedListener implements Listener {

    private final ActivityManager manager;

    public DishCookedListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDishCooked(DishCookedEvent event) {
        if (event.getPlayer() == null) {
            return;
        }

        if ("trough".equals(event.getMethod())) {
            manager.recordAction(event.getPlayer().getUniqueId(), "animal_universal_feed", 1);
        } else {
            manager.recordAction(event.getPlayer().getUniqueId(), "cook_dish", 1);
        }
    }
}
