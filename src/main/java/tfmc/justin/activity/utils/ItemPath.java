package tfmc.justin.activity.utils;

import org.bukkit.Material;

// ====================================
// The four forms an item-valued config key ('material:', 'craft:') accepts,
// classified here so both keys read them the same way:
//
//   IRON_INGOT   a bare Material name, as before
//   v.iron_ingot the same thing in TLibs path notation
//   m.material.steel   an MMOItems item, resolved through TLibs at runtime
//   ia.tfmc:saucepan   an ItemsAdder item, resolved through ItemsAdder at
//                      runtime - 'ia.tfmc.saucepan' is the same item written
//                      with a dot, and normalizes to the colon form
//
// A Material name never contains a dot, so the 'v.'/'m.'/'ia.' prefixes cannot
// collide with one. Pure and headless: matchMaterial is a name lookup and
// needs no running server, and nothing here touches a TLibs or ItemsAdder
// class.
//
// The prefixes are matched case-insensitively and after trimming, since
// TLibs itself is lenient about both. Any other <letters>. prefix (nx., ...)
// is a made-up path, not a material - isUnsupportedPath tells the two apart
// so the caller can warn accordingly instead of reporting an "unknown
// material" for something that was never meant to be one.
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

    // Is this the ia.<namespace:id> form at all, well formed or not? Same rule
    // as isPluginPath: it must never reach matchMaterial.
    public static boolean isItemsAdderPath(String value) {
        if (value == null) {
            return false;
        }
        String v = value.strip();
        return v.length() >= 3 && v.regionMatches(true, 0, "ia.", 0, 3);
    }

    // The '<namespace>:<id>' ItemsAdder knows the item by - the same string
    // /iagive takes - or null when the value is not that form or is missing a
    // segment.
    //
    // Both 'ia.tfmc:saucepan' and 'ia.tfmc.saucepan' normalize to
    // 'tfmc:saucepan': the dotted form is what an admin writes by analogy with
    // m.<type>.<id>, and rejecting it would only teach him that the plugin is
    // picky about a separator ItemsAdder never sees.
    public static String itemsAdderId(String value) {
        if (!isItemsAdderPath(value)) {
            return null;
        }
        String rest = value.strip().substring(3);
        if (rest.indexOf(':') >= 0) {
            String[] parts = rest.split(":", -1);
            return parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank() ? rest : null;
        }
        String[] parts = rest.split("\\.", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        return parts[0] + ":" + parts[1];
    }

    // Does this value have a <letters>. prefix that is none of v., m. and ia.?
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
        return !prefix.equalsIgnoreCase("v") && !prefix.equalsIgnoreCase("m")
            && !prefix.equalsIgnoreCase("ia");
    }

    // The Material behind a bare name or the v.<material> form, or null when
    // the name is unknown, unsupported, or the value is a plugin path
    public static Material material(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        if (isPluginPath(v) || isItemsAdderPath(v) || isUnsupportedPath(v)) {
            return null;
        }
        String name = v.regionMatches(true, 0, "v.", 0, 2) ? v.substring(2) : v;
        return name.isBlank() ? null : Material.matchMaterial(name);
    }
}
