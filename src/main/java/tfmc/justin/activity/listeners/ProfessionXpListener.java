package tfmc.justin.activity.listeners;

import net.Indyuce.mmocore.api.event.PlayerExperienceGainEvent;
import net.Indyuce.mmocore.experience.Profession;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tfmc.justin.activity.managers.ActivityManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// ====================================
// Feeds MMOCore profession XP into whichever activity declares
// 'profession: <id>' in config.yml. Only constructed when MMOCore is enabled
// - see ActivityPlugin.
//
// MONITOR + ignoreCancelled: the XP only counts once every other plugin has
// had its say and the gain is actually going through.
// ====================================
public class ProfessionXpListener implements Listener {

    private final ActivityManager manager;

    // ====================================
    // Leftover fraction of an XP point per player per activity, so a boosted
    // 5.5 xp gain is not repeatedly rounded down to 5. Kept per activity
    // because each has its own goal and daily cap - a mining fraction must
    // not be credited to fishing.
    //
    // A plain HashMap is safe: PlayerExperienceGainEvent is not async, and
    // Bukkit refuses to deliver a non-async event off the primary thread, so
    // MMOCore would throw before ever reaching this listener.
    //
    // In-memory only - the worst a restart costs a player is under one point.
    // ====================================
    private final Map<UUID, Map<String, Double>> carry = new HashMap<>();

    public ProfessionXpListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExperienceGain(PlayerExperienceGainEvent event) {
        // Without a profession this is main-class/character XP, which no
        // activity tracks
        if (!event.hasProfession()) {
            return;
        }

        Profession profession = event.getProfession();
        if (profession == null) {
            return;
        }

        String activityId = manager.getConfiguration().professionActivity(profession.getId());
        if (activityId == null) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Map<String, Double> byActivity = carry.computeIfAbsent(uuid, key -> new HashMap<>());
        Credit credit = credit(byActivity.getOrDefault(activityId, 0.0), event.getExperience());
        byActivity.put(activityId, credit.carry());

        if (credit.amount() > 0) {
            manager.recordAction(uuid, activityId, credit.amount());
        }
    }

    // Nothing to carry for a player who is gone
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        carry.remove(event.getPlayer().getUniqueId());
    }

    // Whole points to record now, and the fraction left over for next time
    record Credit(int amount, double carry) {
    }

    // ====================================
    // Pure: (leftover fraction, this event's XP) -> what to record. The
    // incoming XP is sanitized first so a poisoned value cannot corrupt the
    // stored carry, which always stays in [0, 1).
    // ====================================
    static Credit credit(double carry, double experience) {
        double total = carry + sanitize(experience);
        double whole = Math.floor(total);
        if (whole >= Integer.MAX_VALUE) {
            return new Credit(Integer.MAX_VALUE, 0);
        }
        return new Credit((int) whole, total - whole);
    }

    // ====================================
    // getExperience() is a double and other plugins can set it: NaN, a
    // negative (an XP penalty) and infinity are all worth nothing. The
    // comparison is written this way round so NaN fails it. Huge values are
    // capped rather than overflowing recordAction's int.
    // ====================================
    private static double sanitize(double experience) {
        if (!(experience > 0)) {
            return 0;
        }
        return Math.min(experience, Integer.MAX_VALUE);
    }
}
