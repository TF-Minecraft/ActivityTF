package tfmc.justin.activity.utils;

import org.bukkit.Material;

// ====================================
// The three forms an item-valued config key ('material:', 'craft:') accepts,
// classified here so both keys read them the same way:
//
//   IRON_INGOT   a bare Material name, as before
//   v.iron_ingot the same thing in TLibs path notation
//   m.material.steel   an MMOItems item, resolved through TLibs at runtime
//
// A Material name never contains a dot, so the 'v.'/'m.' prefixes cannot
// collide with one. Pure and headless: matchMaterial is a name lookup and
// needs no running server, and nothing here touches a TLibs class.
// ====================================
public final class ItemPath {

    private ItemPath() {
    }

    // Is this the m.<type>.<id> form at all, well formed or not? A value that
    // looks like one must never fall through to matchMaterial - it would be
    // reported as an unknown material rather than as a broken path.
    public static boolean isPluginPath(String value) {
        return value != null && value.startsWith("m.");
    }

    // The m.<type>.<id> path to hand to TLibs, or null when the value is not
    // that form or is missing a segment
    public static String pluginPath(String value) {
        if (!isPluginPath(value)) {
            return null;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
            return null;
        }
        return value;
    }

    // The Material behind a bare name or the v.<material> form, or null when
    // the name is unknown or the value is a plugin path
    public static Material material(String value) {
        if (value == null || isPluginPath(value)) {
            return null;
        }
        String name = value.startsWith("v.") ? value.substring(2) : value;
        return name.isBlank() ? null : Material.matchMaterial(name);
    }
}
