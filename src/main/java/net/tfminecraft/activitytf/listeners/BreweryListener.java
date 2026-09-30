package net.tfminecraft.activitytf.listeners;

import com.dre.brewery.api.events.IngedientAddEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.tfminecraft.activitytf.managers.ActivityManager;

public class BreweryListener implements Listener {

    private final ActivityManager manager;

    public BreweryListener(ActivityManager manager) {
        this.manager = manager;
    }

    // BreweryX only fires this for an item a cauldron recipe accepts, once per item added.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIngredientAdd(IngedientAddEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        manager.recordAction(event.getPlayer().getUniqueId(), "brew_ingredient", 1);
    }
}
