package tfmc.justin.activity.models;

import org.bukkit.Material;

// ====================================
// every: actions per award. points: awarded each time 'every' is met.
// dailyCap: most points this activity can add in one day, 0 = unlimited.
// ====================================
public record ActivityDef(String id, String display, Material icon, int every, int points, int dailyCap) {
}
