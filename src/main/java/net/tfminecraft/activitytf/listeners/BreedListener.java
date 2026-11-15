package net.tfminecraft.activitytf.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import net.tfminecraft.activitytf.managers.ActivityManager;

/** Credits {@code breed_animals} for every animal a player breeds. */
public class BreedListener implements Listener {

    private final ActivityManager manager;

    public BreedListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player) {
            manager.recordAction(player.getUniqueId(), "breed_animals", 1);
        }
    }
}
