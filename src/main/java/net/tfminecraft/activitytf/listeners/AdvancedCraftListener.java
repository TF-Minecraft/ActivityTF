package net.tfminecraft.activitytf.listeners;

import net.tfminecraft.advancedcrafting.lifecycle.AlloyCraftedEvent;
import net.tfminecraft.advancedcrafting.lifecycle.ItemCraftedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.tfminecraft.activitytf.managers.ActivityManager;

import java.util.UUID;

public class AdvancedCraftListener implements Listener {

    private final ActivityManager manager;

    public AdvancedCraftListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemCrafted(ItemCraftedEvent event) {
        record(event.getPlayerUuid());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAlloyCrafted(AlloyCraftedEvent event) {
        record(event.getPlayerUuid());
    }

    private void record(UUID uuid) {
        if (uuid == null) {
            return;
        }

        manager.recordAction(uuid, "advcraft_item", 1);
    }
}
