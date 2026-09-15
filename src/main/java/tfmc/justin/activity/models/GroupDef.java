package tfmc.justin.activity.models;

import org.bukkit.Material;

// ====================================
// One tile on the GUI's main view, opening a page of every activity whose
// 'group' is this id. icon/iconPath mean exactly what they
// mean on ActivityDef - iconPath is the TLibs m.<type>.<id> path the label
// comes from, or null when it is the plain Material, which is also the
// fallback if TLibs is missing or the path stops resolving.
// ====================================
public record GroupDef(String id, String display, Material icon, String iconPath) {

    // The GUI grid's budget: both views draw into the same 28 slots, so up
    // to MAX_GROUPS tiles fit on the main view and up to MAX_ACTIVITIES icons
    // on a group's page. Lives here rather than in ActivityGui so
    // ActivityConfiguration can validate against it without importing the gui
    // package.
    public static final int MAX_GROUPS = 28;
    public static final int MAX_ACTIVITIES = 28;
}
