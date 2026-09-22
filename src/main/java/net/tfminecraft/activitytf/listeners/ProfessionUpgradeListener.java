package net.tfminecraft.activitytf.listeners;

import net.tfminecraft.rpcharacters.professions.ProfessionUpgradePurchasedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.tfminecraft.activitytf.managers.ActivityManager;

public class ProfessionUpgradeListener implements Listener {

    private final ActivityManager manager;

    public ProfessionUpgradeListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProfessionUpgradePurchased(ProfessionUpgradePurchasedEvent event) {
        if (event.getPlayer() == null) {
            return;
        }

        manager.recordAction(event.getPlayer().getUniqueId(), "profession_upgrade", 1);
    }
}
