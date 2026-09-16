package tfmc.justin.activity.listeners;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// ====================================
// Leftover fraction of a point per player per activity, so a 5.5 xp gain or a
// 0.5 denar sale is not repeatedly rounded down to nothing. Kept per activity
// because each has its own goal and daily cap - a mining fraction must not be
// credited to fishing.
//
// A plain HashMap is safe: the events that feed this are not async, and Bukkit
// refuses to deliver a non-async event off the primary thread, so the source
// plugin would throw before ever reaching a listener.
//
// In-memory only - the worst a restart costs a player is under one point.
// ====================================
class FractionCarry {

    private final Map<UUID, Map<String, Double>> carry = new HashMap<>();

    // Adds this event's value to the player's leftover for the activity and
    // returns the whole points to record now (0 if there are none yet)
    int add(UUID uuid, String activityId, double value) {
        Map<String, Double> byActivity = carry.computeIfAbsent(uuid, key -> new HashMap<>());
        Credit credit = credit(byActivity.getOrDefault(activityId, 0.0), value);
        byActivity.put(activityId, credit.carry());
        return credit.amount();
    }

    // Nothing to carry for a player who is gone
    void forget(UUID uuid) {
        carry.remove(uuid);
    }

    // Whole points to record now, and the fraction left over for next time
    record Credit(int amount, double carry) {
    }

    // ====================================
    // Pure: (leftover fraction, this event's value) -> what to record. The
    // incoming value is sanitized first so a poisoned one cannot corrupt the
    // stored carry, which always stays in [0, 1).
    // ====================================
    static Credit credit(double carry, double value) {
        double total = carry + sanitize(value);
        double whole = Math.floor(total);
        if (whole >= Integer.MAX_VALUE) {
            return new Credit(Integer.MAX_VALUE, 0);
        }
        return new Credit((int) whole, total - whole);
    }

    // ====================================
    // These values are doubles other plugins can set: NaN, a negative (an XP
    // penalty) and infinity are all worth nothing. The comparison is written
    // this way round so NaN fails it. Huge values are capped rather than
    // overflowing recordAction's int.
    // ====================================
    private static double sanitize(double value) {
        if (!(value > 0)) {
            return 0;
        }
        return Math.min(value, Integer.MAX_VALUE);
    }
}
