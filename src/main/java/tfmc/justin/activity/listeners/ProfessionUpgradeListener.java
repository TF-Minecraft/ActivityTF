package tfmc.justin.activity.listeners;

import net.tfminecraft.RPCharacters.professions.ProfessionUpgradePurchasedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

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
