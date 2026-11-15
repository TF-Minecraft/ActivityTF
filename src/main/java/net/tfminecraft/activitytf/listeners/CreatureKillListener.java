package net.tfminecraft.activitytf.listeners;

import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import net.tfminecraft.activitytf.managers.ActivityManager;

/** Credits {@code defeat_creatures} for hostile creatures a player kills, but not ones from mob spawners. */
public class CreatureKillListener implements Listener {

    private final ActivityManager manager;

    public CreatureKillListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Enemy) || entity.fromMobSpawner()) {
            return;
        }
        Player killer = entity.getKiller();
        if (killer != null) {
            manager.recordAction(killer.getUniqueId(), "defeat_creatures", 1);
        }
    }
}
