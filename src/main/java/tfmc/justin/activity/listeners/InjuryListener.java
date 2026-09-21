package tfmc.justin.activity.listeners;

import net.tfminecraft.RPCharacters.injuries.CharacterInjuredEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

public class InjuryListener implements Listener {

    private final ActivityManager manager;

    public InjuryListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCharacterInjured(CharacterInjuredEvent event) {
        if (event.getTarget() == null) {
            return;
        }

        manager.recordAction(event.getTarget().getUniqueId(), "injured", 1);
    }
}
