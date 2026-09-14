package tfmc.justin.activity.listeners;

import net.tfminecraft.RPCharacters.chat.CharacterChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

// ====================================
// RPCharacters builds CharacterChatEvent with async=false, so Bukkit refuses
// to deliver it off the primary thread. Only constructed when RPCharacters is
// enabled - see ActivityPlugin.
//
// MONITOR + ignoreCancelled: a message another plugin swallowed was never said.
// ====================================
public class CharacterChatListener implements Listener {

    private final ActivityManager manager;

    public CharacterChatListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCharacterChat(CharacterChatEvent event) {
        // The source plugin builds the event itself; a null sender would
        // only be a bug there, but it must not take this listener down
        if (event.getSender() == null) {
            return;
        }

        manager.recordAction(event.getSender().getUniqueId(), "ic_chat", 1);
    }
}
