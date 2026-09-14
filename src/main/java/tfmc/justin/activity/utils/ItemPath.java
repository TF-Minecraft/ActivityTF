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
//
// The prefixes are matched case-insensitively and after trimming, since
// TLibs itself is lenient about both. Any other <letters>. prefix (ia.,
// nx., ...) is a made-up path, not a material - isUnsupportedPath tells
// the two apart so the caller can warn accordingly instead of reporting an
// "unknown material" for something that was never meant to be one.
// ====================================
public final class ItemPath {

    private ItemPath() {
    }

    // Is this the m.<type>.<id> form at all, well formed or not? A value that
    // looks like one must never fall through to matchMaterial - it would be
    // reported as an unknown material rather than as a broken path.
    public static boolean isPluginPath(String value) {
        if (value == null) {
            return false;
        }
        String v = value.strip();
        return v.length() >= 2 && v.regionMatches(true, 0, "m.", 0, 2);
    }

    // The m.<type>.<id> path to hand to TLibs, or null when the value is not
    // that form or is missing a segment
    public static String pluginPath(String value) {
        if (!isPluginPath(value)) {
            return null;
        }
        String v = value.strip();
        String[] parts = v.split("\\.", -1);
        if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
            return null;
        }
        return v;
    }

    // Does this value have a <letters>. prefix that is neither v. nor m.?
    // Such a value was written as some other plugin's item path and must be
    // reported as an unsupported path, not looked up as a material.
    public static boolean isUnsupportedPath(String value) {
        if (value == null) {
            return false;
        }
        String v = value.strip();
        int dot = v.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        String prefix = v.substring(0, dot);
        if (!prefix.chars().allMatch(Character::isLetter)) {
            return false;
        }
        return !prefix.equalsIgnoreCase("v") && !prefix.equalsIgnoreCase("m");
    }

    // The Material behind a bare name or the v.<material> form, or null when
    // the name is unknown, unsupported, or the value is a plugin path
    public static Material material(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        if (isPluginPath(v) || isUnsupportedPath(v)) {
            return null;
        }
        String name = v.regionMatches(true, 0, "v.", 0, 2) ? v.substring(2) : v;
        return name.isBlank() ? null : Material.matchMaterial(name);
    }
}
