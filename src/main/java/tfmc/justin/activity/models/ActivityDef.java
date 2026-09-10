package tfmc.justin.activity.models;

import org.bukkit.Material;

// ====================================
// every: actions per award. points: awarded each time 'every' is met.
// dailyCap: most points this activity can add in one day, 0 = unlimited.
// ====================================
public record ActivityDef(String id, String display, Material icon, int every, int points, int dailyCap) {

    // What a day's action count is worth in points, after every and dailyCap
    public int worth(int count) {
        long raw = (long) (count / every) * points;
        long capped = dailyCap > 0 ? Math.min(raw, dailyCap) : raw;
        return (int) Math.min(Integer.MAX_VALUE, capped);
    }
}
