package tfmc.justin.activity.models;

import org.bukkit.Material;

import java.util.List;

// ====================================
// every: actions per award. points: awarded each time 'every' is met.
// dailyCap: most points this activity can add in one day, 0 = unlimited.
// iconPath: the TLibs m.<type>.<id> path the GUI icon comes from, or null
// when the icon is the plain Material - in which case 'icon' is also the
// fallback used if TLibs is missing or the path no longer resolves.
// clickCommands: console commands run when the player clicks this activity's
// task after it is already revealed. Empty for every activity that does not
// configure 'click-commands', which is the normal case.
// description: optional operator text shown at the top of the task's lore,
// one entry per line. Empty for every activity that does not configure
// 'description', which is the normal case.
// ====================================
public record ActivityDef(String id, String display, Material icon, String iconPath, int every, int points,
                          int dailyCap, List<String> clickCommands, List<String> description) {

    // Immutable and never null, so neither the GUI nor the dispatcher has to
    // guard what config handed over
    public ActivityDef {
        clickCommands = clickCommands == null ? List.of() : List.copyOf(clickCommands);
        description = description == null ? List.of() : List.copyOf(description);
    }

    // The overwhelming majority of activities have no description; this keeps
    // the click-command call sites reading the way they always have
    public ActivityDef(String id, String display, Material icon, String iconPath, int every, int points,
                       int dailyCap, List<String> clickCommands) {
        this(id, display, icon, iconPath, every, points, dailyCap, clickCommands, List.of());
    }

    // The overwhelming majority of activities have no click commands either;
    // this keeps every one of those call sites reading the way it always has
    public ActivityDef(String id, String display, Material icon, String iconPath, int every, int points,
                       int dailyCap) {
        this(id, display, icon, iconPath, every, points, dailyCap, List.of(), List.of());
    }

    // What a day's action count is worth in points, after every and dailyCap
    public int worth(int count) {
        long raw = (long) (count / every) * points;
        long capped = dailyCap > 0 ? Math.min(raw, dailyCap) : raw;
        return (int) Math.min(Integer.MAX_VALUE, capped);
    }
}
