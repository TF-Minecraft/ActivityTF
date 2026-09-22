package net.tfminecraft.activitytf.listeners;

import net.tfminecraft.activitytf.config.ActivityConfiguration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class FractionCarry {

    private static final BigDecimal MAX = BigDecimal.valueOf(Integer.MAX_VALUE);

    private final Map<UUID, Map<String, BigDecimal>> carry = new HashMap<>();

    private ActivityConfiguration.Keys keys;

    int add(UUID uuid, String activityId, double value, ActivityConfiguration.Keys currentKeys) {
        if (!currentKeys.equals(keys)) {
            carry.clear();
            keys = currentKeys;
        }

        Map<String, BigDecimal> byActivity = carry.computeIfAbsent(uuid, key -> new HashMap<>());
        Credit credit = credit(byActivity.getOrDefault(activityId, BigDecimal.ZERO), value);
        byActivity.put(activityId, credit.exact());
        return credit.amount();
    }

    void forget(UUID uuid) {
        carry.remove(uuid);
    }

    void forget(UUID uuid, String activityId) {
        Map<String, BigDecimal> byActivity = carry.get(uuid);
        if (byActivity != null && byActivity.remove(activityId) != null && byActivity.isEmpty()) {
            carry.remove(uuid);
        }
    }

    record Credit(int amount, BigDecimal exact) {

        double carry() {
            return exact.doubleValue();
        }
    }

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

    private static double sanitize(double value) {
        if (!(value > 0)) {
            return 0;
        }
        return Math.min(value, Integer.MAX_VALUE);
    }
}
