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

public class CharacterChatListener implements Listener {

    static final int MIN_LENGTH = 15;
    static final int MIN_NORMALIZED = 10;
    static final int MIN_DISTINCT = 5;
    static final int HISTORY = 30;
    static final double SIMILARITY = 0.8;

    private static final Pattern COLOUR = Pattern.compile("[&§](#[0-9a-fA-F]{6}|[0-9a-fk-orxA-FK-ORX])");

    private final ActivityManager manager;
    private final Map<UUID, Deque<String>> history = new HashMap<>();

    public CharacterChatListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCharacterChat(CharacterChatEvent event) {
        if (event.getSender() == null || event.getMessage() == null) {
            return;
        }

        UUID uuid = event.getSender().getUniqueId();
        if (process(uuid, strip(event.getMessage()))) {
            manager.recordAction(uuid, "ic_chat", 1);
        }
    }

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

    int historySize(UUID uuid) {
        Deque<String> recent = history.get(uuid);
        return recent == null ? 0 : recent.size();
    }

    static String strip(String message) {
        return COLOUR.matcher(message).replaceAll("").trim();
    }

    static boolean passesGate(String text, String norm) {
        return text.length() > MIN_LENGTH && norm.length() >= MIN_NORMALIZED
                && norm.chars().distinct().count() >= MIN_DISTINCT && !isPeriodic(norm);
    }

    static boolean isPeriodic(String s) {
        int n = s.length();
        if (n == 0) {
            return false;
        }
        int[] pi = new int[n];
        for (int i = 1; i < n; i++) {
            int j = pi[i - 1];
            while (j > 0 && s.charAt(i) != s.charAt(j)) {
                j = pi[j - 1];
            }
            if (s.charAt(i) == s.charAt(j)) {
                j++;
            }
            pi[i] = j;
        }
        int period = n - pi[n - 1];
        return period <= n / 2;
    }

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
        if (1.0 - (double) Math.abs(a.length() - b.length()) / max < SIMILARITY) {
            return false;
        }
        return 1.0 - (double) distance(a, b) / max >= SIMILARITY;
    }

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
