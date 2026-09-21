package tfmc.justin.activity.utils;

import org.bukkit.Material;

public final class ItemPath {

    private ItemPath() {
    }

    public static boolean isPluginPath(String value) {
        if (value == null) {
            return false;
        }
        String v = value.strip();
        return v.length() >= 2 && v.regionMatches(true, 0, "m.", 0, 2);
    }

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

    public static boolean isItemsAdderPath(String value) {
        if (value == null) {
            return false;
        }
        String v = value.strip();
        return v.length() >= 3 && v.regionMatches(true, 0, "ia.", 0, 3);
    }

    public static String itemsAdderId(String value) {
        if (!isItemsAdderPath(value)) {
            return null;
        }
        String rest = value.strip().substring(3);
        String[] parts = rest.indexOf(':') >= 0 ? rest.split(":", -1) : rest.split("\\.", -1);
        if (parts.length != 2) {
            return null;
        }
        String namespace = parts[0].strip();
        String id = parts[1].strip();
        if (namespace.isEmpty() || id.isEmpty() || hasWhitespace(namespace) || hasWhitespace(id)) {
            return null;
        }
        return namespace + ":" + id;
    }

    private static boolean hasWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }

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
