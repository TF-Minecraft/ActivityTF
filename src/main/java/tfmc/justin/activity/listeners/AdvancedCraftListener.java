package tfmc.justin.activity.listeners;

import net.tfminecraft.AdvancedCrafting.lifecycle.AlloyCraftedEvent;
import net.tfminecraft.AdvancedCrafting.lifecycle.ItemCraftedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

import java.util.UUID;

// ====================================
// AdvancedCrafting fires both events synchronously on the main thread, once
// per completed craft - neither carries a count, so each is worth 1. Neither
// is cancellable. Only constructed when AdvancedCrafting is enabled - see
// ActivityPlugin.
//
// getPlayerUuid() rather than getPlayer(), like TFMCCore does: the UUID is
// carried separately, and recordAction does not need a live Player.
// ====================================
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
