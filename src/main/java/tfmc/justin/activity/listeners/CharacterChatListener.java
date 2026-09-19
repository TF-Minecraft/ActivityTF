package tfmc.justin.activity.listeners;

import net.tfminecraft.RPCharacters.chat.CharacterChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
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
// Anti-farm: a message only counts when it is longer than MIN_LENGTH, its
// normalized form (lowercase letters only) has at least MIN_NORMALIZED
// characters, and that form is less than SIMILARITY alike (Levenshtein) to
// each of the sender's last HISTORY such messages. Messages failing either
// length gate never enter the history, so filler can't reset it. History is
// kept across quits so relogging can't clear it; memory is bounded at HISTORY
// short strings per player who has chatted since startup. Main thread only,
// so a plain HashMap is enough.
// ====================================
public class CharacterChatListener implements Listener {

    static final int MIN_LENGTH = 15;
    static final int MIN_NORMALIZED = 10;
    static final int HISTORY = 20;
    static final double SIMILARITY = 0.8;

    // &a / §a legacy codes and &#RRGGBB hex, as players type them
    private static final Pattern COLOUR = Pattern.compile("[&§](#[0-9a-fA-F]{6}|[0-9a-fk-orxA-FK-ORX])");

    private final ActivityManager manager;
    private final Map<UUID, Deque<String>> history = new HashMap<>();

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
        if (process(uuid, strip(event.getMessage()))) {
            manager.recordAction(uuid, "ic_chat", 1);
        }
    }

    // text is already stripped. Stored whether or not it counts, so a chain
    // of variations never pays
    boolean process(UUID uuid, String text) {
        String norm = normalize(text);
        if (!passesGate(text, norm)) {
            return false;
        }
        Deque<String> recent = history.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        boolean counts = isNovel(norm, recent);
        recent.addLast(norm);
        if (recent.size() > HISTORY) {
            recent.removeFirst();
        }
        return counts;
    }

    // for tests
    int historySize(UUID uuid) {
        Deque<String> recent = history.get(uuid);
        return recent == null ? 0 : recent.size();
    }

    static String strip(String message) {
        return COLOUR.matcher(message).replaceAll("").trim();
    }

    // text is stripped, norm is normalize(text)
    static boolean passesGate(String text, String norm) {
        return text.length() > MIN_LENGTH && norm.length() >= MIN_NORMALIZED;
    }

    // history holds normalize() of recent messages
    static boolean isNovel(String norm, Collection<String> history) {
        for (String b : history) {
            if (similar(norm, b)) {
                return false;
            }
        }
        return true;
    }

    static boolean similar(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return true;
        }
        // distance >= length difference, so this is an upper bound; same
        // formula as below so the boundary can't drift on rounding
        if (1.0 - (double) Math.abs(a.length() - b.length()) / max < SIMILARITY) {
            return false;
        }
        return 1.0 - (double) distance(a, b) / max >= SIMILARITY;
    }

    // Levenshtein edit distance, two rolling rows
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j], cur[j - 1]) + 1);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    static String normalize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toLowerCase().toCharArray()) {
            if (Character.isLetter(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
