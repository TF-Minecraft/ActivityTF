package net.tfminecraft.activitytf.listeners;

import net.tfminecraft.archaeo.events.FindRecoveredEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.tfminecraft.activitytf.managers.ActivityManager;

public class ArchaeologyListener implements Listener {

    private final ActivityManager manager;

    public ArchaeologyListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFindRecovered(FindRecoveredEvent event) {
        if (event.getPlayer() == null) {
            return;
        }

        manager.recordAction(event.getPlayer().getUniqueId(), "archaeology_find", 1);
    }
}
