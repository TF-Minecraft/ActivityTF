package tfmc.justin.activity.listeners;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// ====================================
// Leftover fraction of a point per player per activity, so a 5.5 xp gain or a
// 0.5 denar sale is not repeatedly rounded down to nothing. Kept per activity
// because each has its own goal and daily cap - a mining fraction must not be
// credited to fishing.
//
// The leftover is a BigDecimal, not a double: cent-denominated values drift
// when summed in binary (250 additions of 0.10 land just short of 25), so a
// player selling cheap items would never reach the goal. BigDecimal.valueOf
// takes the shortest decimal representation, so 0.10 goes in as exactly 0.10.
//
// A plain HashMap is safe: the events that feed this are not async, and Bukkit
// refuses to deliver a non-async event off the primary thread, so the source
// plugin would throw before ever reaching a listener.
//
// In-memory only - the worst a restart costs a player is under one point.
// ====================================
class FractionCarry {

    private static final BigDecimal MAX = BigDecimal.valueOf(Integer.MAX_VALUE);

    private final Map<UUID, Map<String, BigDecimal>> carry = new HashMap<>();

    // Adds this event's value to the player's leftover for the activity and
    // returns the whole points to record now (0 if there are none yet)
    int add(UUID uuid, String activityId, double value) {
        Map<String, BigDecimal> byActivity = carry.computeIfAbsent(uuid, key -> new HashMap<>());
        Credit credit = credit(byActivity.getOrDefault(activityId, BigDecimal.ZERO), value);
        byActivity.put(activityId, credit.exact());
        return credit.amount();
    }

    // Nothing to carry for a player who is gone
    void forget(UUID uuid) {
        carry.remove(uuid);
    }

    // Whole points to record now, and the fraction left over for next time
    record Credit(int amount, BigDecimal exact) {

        double carry() {
            return exact.doubleValue();
        }
    }

    // ====================================
    // Pure: (leftover fraction, this event's value) -> what to record. The
    // incoming value is sanitized first so a poisoned one cannot corrupt the
    // stored carry, which always stays in [0, 1).
    // ====================================
    static Credit credit(BigDecimal carry, double value) {
        BigDecimal total = carry.add(BigDecimal.valueOf(sanitize(value)));
        BigDecimal whole = total.setScale(0, RoundingMode.FLOOR);
        if (whole.compareTo(MAX) >= 0) {
            return new Credit(Integer.MAX_VALUE, BigDecimal.ZERO);
        }
        return new Credit(whole.intValueExact(), total.subtract(whole));
    }

    static Credit credit(double carry, double value) {
        return credit(BigDecimal.valueOf(carry), value);
    }

    // ====================================
    // These values are doubles other plugins can set: NaN, a negative (an XP
    // penalty) and infinity are all worth nothing. The comparison is written
    // this way round so NaN fails it. Huge values are capped rather than
    // overflowing recordAction's int - and the cap also keeps BigDecimal.valueOf
    // away from the NaN and infinity it refuses to convert.
    // ====================================
    private static double sanitize(double value) {
        if (!(value > 0)) {
            return 0;
        }
        return Math.min(value, Integer.MAX_VALUE);
    }
}
