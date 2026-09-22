package net.tfminecraft.activitytf.models;

import java.util.List;

public record RewardEntry(int weight, String display, List<String> commands, List<Item> items) {

    public record Item(String path, int amount) {
    }

    public static int totalWeight(List<RewardEntry> pool) {
        long total = 0;
        for (RewardEntry entry : pool) {
            total += entry.weight();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

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
