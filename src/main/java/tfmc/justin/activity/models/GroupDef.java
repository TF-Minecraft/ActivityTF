package tfmc.justin.activity.models;

import org.bukkit.Material;

// ====================================
// One row of the GUI: the label item in column 1 and, to its right, every
// activity whose 'group' is this id. icon/iconPath mean exactly what they
// mean on ActivityDef - iconPath is the TLibs m.<type>.<id> path the label
// comes from, or null when it is the plain Material, which is also the
// fallback if TLibs is missing or the path stops resolving.
// ====================================
public record GroupDef(String id, String display, Material icon, String iconPath) {

    // The GUI window's row/column budget: one row per group, up to
    // MAX_GROUPS, and up to MAX_ACTIVITIES icons beside each group's label.
    // Lives here rather than in ActivityGui so ActivityConfiguration can
    // validate against it without importing the gui package.
    public static final int MAX_GROUPS = 4;
    public static final int MAX_ACTIVITIES = 7;
}
