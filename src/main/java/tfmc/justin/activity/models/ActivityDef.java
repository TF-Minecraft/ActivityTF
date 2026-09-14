package tfmc.justin.activity.models;

import org.bukkit.Material;

// ====================================
// every: actions per award. points: awarded each time 'every' is met.
// dailyCap: most points this activity can add in one day, 0 = unlimited.
// iconPath: the TLibs m.<type>.<id> path the GUI icon comes from, or null
// when the icon is the plain Material - in which case 'icon' is also the
// fallback used if TLibs is missing or the path no longer resolves.
// ====================================
public record ActivityDef(String id, String display, Material icon, String iconPath, int every, int points,
                          int dailyCap) {

    public ActivityDef(String id, String display, Material icon, int every, int points, int dailyCap) {
        this(id, display, icon, null, every, points, dailyCap);
    }

    // What a day's action count is worth in points, after every and dailyCap
    public int worth(int count) {
        long raw = (long) (count / every) * points;
        long capped = dailyCap > 0 ? Math.min(raw, dailyCap) : raw;
        return (int) Math.min(Integer.MAX_VALUE, capped);
    }
}
