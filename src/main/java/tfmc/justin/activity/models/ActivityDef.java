package tfmc.justin.activity.models;

import org.bukkit.Material;

import java.util.List;

// ====================================
// every: actions per award. points: awarded each time 'every' is met.
// dailyCap: most awards ('every' met) that count in one day, 0 = unlimited;
// capPoints() is the same limit in points.
// iconPath: the item path the GUI icon comes from - either the TLibs
// m.<type>.<id> form or the normalized ItemsAdder ia.<namespace:id> one,
// which is what routes ActivityGui.fromPath to the right hook - or null when
// the icon is the plain Material, in which case 'icon' is also the fallback
// used if the backing plugin is missing or the path no longer resolves.
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

    // The overwhelming majority of activities have no click commands and no
    // description; this keeps every one of those call sites reading the way it
    // always has. There is deliberately no 8-arg convenience constructor in
    // between: '..., dailyCap, List.of("&7Vote daily")' would compile and
    // register a description as a console command to dispatch.
    public ActivityDef(String id, String display, Material icon, String iconPath, int every, int points,
                       int dailyCap) {
        this(id, display, icon, iconPath, every, points, dailyCap, List.of(), List.of());
    }

    // What a day's action count is worth in points, after every and dailyCap
    public int worth(int count) {
        int raw = rawWorth(count);
        return dailyCap > 0 ? Math.min(raw, capPoints()) : raw;
    }

    // dailyCap in points - the most worth() can reach in a day. Saturates like
    // rawWorth. Only meaningful when dailyCap > 0.
    public int capPoints() {
        return (int) Math.min(Integer.MAX_VALUE, (long) dailyCap * points);
    }

    // ====================================
    // The same figure before dailyCap is applied - what /activity add --force
    // credits, since the whole point of the flag is that no cap holds it back.
    // Saturates rather than wrapping: a count near MAX_ADD times a big 'points'
    // overflows an int, and a negative worth would take points off the bar.
    // ====================================
    public int rawWorth(int count) {
        return (int) Math.min(Integer.MAX_VALUE, (long) (count / every) * points);
    }
}
