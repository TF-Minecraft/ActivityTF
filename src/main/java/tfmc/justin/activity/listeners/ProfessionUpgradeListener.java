package tfmc.justin.activity.listeners;

import net.tfminecraft.RPCharacters.professions.ProfessionUpgradePurchasedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

// ====================================
// RPCharacters fires ProfessionUpgradePurchasedEvent synchronously on the
// main thread once per successful purchase. Only constructed when
// RPCharacters is enabled - see ActivityPlugin.
// ====================================
public class ProfessionUpgradeListener implements Listener {

    private final ActivityManager manager;

    public ProfessionUpgradeListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProfessionUpgradePurchased(ProfessionUpgradePurchasedEvent event) {
        // The source plugin builds the event itself; a null player would
        // only be a bug there, but it must not take this listener down
        if (event.getPlayer() == null) {
            return;
        }

        manager.recordAction(event.getPlayer().getUniqueId(), "profession_upgrade", 1);
    }
}
