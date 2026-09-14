package tfmc.justin.activity.listeners;

import net.tfminecraft.VFBuilders.events.VehicleConstructEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

// ====================================
// VFBuilders fires VehicleConstructEvent synchronously on the main thread.
// Not cancellable - the vehicle already exists by the time it fires. Only
// constructed when VFBuilders is enabled - see ActivityPlugin.
// ====================================
public class VehicleBuildListener implements Listener {

    private final ActivityManager manager;

    public VehicleBuildListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleConstruct(VehicleConstructEvent event) {
        // getConstructor() is the live Player the event was built with, and is
        // null when the builder was already gone
        if (event.getConstructor() == null) {
            return;
        }

        manager.recordAction(event.getConstructor().getUniqueId(), "vehicle_build", 1);
    }
}
