package net.tfminecraft.activitytf.listeners;

import net.tfminecraft.vfbuilders.events.VehicleConstructEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.tfminecraft.activitytf.managers.ActivityManager;

import java.util.UUID;

public class VehicleBuildListener implements Listener {

    private final ActivityManager manager;

    public VehicleBuildListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleConstruct(VehicleConstructEvent event) {
        UUID constructor = event.getConstructorUuid();
        if (constructor == null) {
            return;
        }

        manager.recordAction(constructor, "vehicle_build", 1);
    }
}
