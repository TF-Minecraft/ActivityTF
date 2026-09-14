package tfmc.justin.activity.listeners;

import net.tfminecraft.VFBuilders.events.VehicleConstructEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

import java.util.UUID;

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
        // the uuid, not getConstructor(): that resolves to a live Player and is
        // null once the builder logs off, but recordAction takes a UUID and
        // credits an offline player just fine
        UUID constructor = event.getConstructorUuid();
        if (constructor == null) {
            return;
        }

        manager.recordAction(constructor, "vehicle_build", 1);
    }
}
