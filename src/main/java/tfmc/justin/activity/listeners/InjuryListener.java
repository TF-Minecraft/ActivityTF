package tfmc.justin.activity.listeners;

import net.tfminecraft.RPCharacters.injuries.CharacterInjuredEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

// ====================================
// RPCharacters fires CharacterInjuredEvent synchronously on the main thread
// once per successful injury. Only constructed when RPCharacters is enabled
// - see ActivityPlugin. Credits the injured player, not the attacker.
// ====================================
public class InjuryListener implements Listener {

    private final ActivityManager manager;

    public InjuryListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCharacterInjured(CharacterInjuredEvent event) {
        // The source plugin builds the event itself; a null target would
        // only be a bug there, but it must not take this listener down
        if (event.getTarget() == null) {
            return;
        }

        manager.recordAction(event.getTarget().getUniqueId(), "injured", 1);
    }
}
