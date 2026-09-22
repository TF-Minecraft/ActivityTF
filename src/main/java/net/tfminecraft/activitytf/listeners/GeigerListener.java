package net.tfminecraft.activitytf.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.geigercounters.events.GeigerSourceCollectEvent;

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
