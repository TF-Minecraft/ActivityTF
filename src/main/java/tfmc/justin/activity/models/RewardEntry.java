package tfmc.justin.activity.models;

import java.util.List;

// ====================================
// One entry of the reward pool: the console commands one claim can hand over,
// what to call them in chat, and how likely they are to be drawn.
// Plain POJO with no Bukkit in it, so the draw stays unit testable.
// ====================================
public record RewardEntry(int weight, String display, List<String> commands) {

    public static int totalWeight(List<RewardEntry> pool) {
        int total = 0;
        for (RewardEntry entry : pool) {
            total += entry.weight();
        }
        return total;
    }

    // ====================================
    // The entry a roll in [0, totalWeight) lands on: cumulative weights, so
    // an entry of weight 3 is drawn three times as often as one of weight 1.
    // A roll outside the range (a caller that rolled against a stale total)
    // takes the last entry rather than paying nothing. Null on an empty pool.
    // ====================================
    public static RewardEntry pick(List<RewardEntry> pool, int roll) {
        if (pool.isEmpty()) {
            return null;
        }
        int cursor = 0;
        for (RewardEntry entry : pool) {
            cursor += entry.weight();
            if (roll < cursor) {
                return entry;
            }
        }
        return pool.get(pool.size() - 1);
    }
}
