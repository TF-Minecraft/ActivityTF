package tfmc.justin.activity.listeners;

import net.tfminecraft.RPCharacters.chat.CharacterChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tfmc.justin.activity.managers.ActivityManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

// ====================================
// RPCharacters builds CharacterChatEvent with async=false, so Bukkit refuses
// to deliver it off the primary thread. Only constructed when RPCharacters is
// enabled - see ActivityPlugin.
//
// MONITOR + ignoreCancelled: a message another plugin swallowed was never said.
//
// Anti-farm: a message only counts when it is longer than MIN_LENGTH and is
// not a near-duplicate of the sender's previous IC message. Main thread only,
// so a plain HashMap is enough.
// ====================================
public class CharacterChatListener implements Listener {

    static final int MIN_LENGTH = 15;

    // &a / §a legacy codes and &#RRGGBB hex, as players type them
    private static final Pattern COLOUR = Pattern.compile("[&§](#[0-9a-fA-F]{6}|[0-9a-fk-orxA-FK-ORX])");

    private final ActivityManager manager;
    private final Map<UUID, String> lastMessage = new HashMap<>();

    public CharacterChatListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCharacterChat(CharacterChatEvent event) {
        // The source plugin builds the event itself; a null sender would
        // only be a bug there, but it must not take this listener down
        if (event.getSender() == null || event.getMessage() == null) {
            return;
        }

        UUID uuid = event.getSender().getUniqueId();
        String text = strip(event.getMessage());
        // Updated whether or not it counts, so a chain of variations never pays
        String previous = lastMessage.put(uuid, text);

        if (counts(text, previous)) {
            manager.recordAction(uuid, "ic_chat", 1);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastMessage.remove(event.getPlayer().getUniqueId());
    }

    static String strip(String message) {
        return COLOUR.matcher(message).replaceAll("").trim();
    }

    // text is already stripped; previous is the sender's last stripped message, or null
    static boolean counts(String text, String previous) {
        if (text.length() <= MIN_LENGTH) {
            return false;
        }
        if (previous == null) {
            return true;
        }
        String a = letters(text);
        String b = letters(previous);
        // contains() also covers equality, and an empty side always matches
        return !a.contains(b) && !b.contains(a);
    }

    private static String letters(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toLowerCase().toCharArray()) {
            if (Character.isLetter(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
