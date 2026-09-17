package tfmc.justin.activity.models;

import java.util.List;

// ====================================
// One entry of the reward pool: the console commands and the items one claim
// can hand over, what to call them in chat, and how likely it is to be drawn.
// Plain POJO with no Bukkit in it, so the draw stays unit testable - an item
// is held as its configured path and amount, not as a resolved ItemStack.
// ====================================
public record RewardEntry(int weight, String display, List<String> commands, List<Item> items) {

    // One 'items:' line: the item-path form ItemPath understands, and how many
    // of it to hand over.
    public record Item(String path, int amount) {
    }

    // The command-only entry every caller wrote before items existed
    public RewardEntry(int weight, String display, List<String> commands) {
        this(weight, display, commands, List.of());
    }

    public static int totalWeight(List<RewardEntry> pool) {
        long total = 0;
        for (RewardEntry entry : pool) {
            total += entry.weight();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
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
        long cursor = 0;
        for (RewardEntry entry : pool) {
            cursor += entry.weight();
            if (roll < cursor) {
                return entry;
            }
        }
        return pool.get(pool.size() - 1);
    }
}
