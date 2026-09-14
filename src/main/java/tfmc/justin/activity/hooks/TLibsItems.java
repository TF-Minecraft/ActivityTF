package tfmc.justin.activity.hooks;

import me.Plugins.TLibs.TLibs;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// ====================================
// The only class that touches TLibs. Like the listeners in
// ActivityPlugin#registerHooks it is never loaded on a server without the
// plugin, because every caller checks isPluginEnabled("TLibs") first.
//
// Both calls are wrapped: a path naming an MMOItems item that no longer
// exists is an admin typo in config.yml, and it must cost one icon or one
// craft credit, not the whole GUI or the craft event.
// ====================================
public final class TLibsItems {

    // Paths already logged as failing, so a broken config.yml entry warns
    // once on server start rather than once per GUI open or per craft.
    private static final Set<String> reportedPaths = ConcurrentHashMap.newKeySet();

    private TLibsItems() {
    }

    // The item behind an m.<type>.<id> path, or null if TLibs cannot build
    // one - including TLibs' own DIRT sentinel for a path it recognises the
    // shape of but cannot resolve (MMOItems/MythicLib missing or the id
    // deleted), which must read as "no icon" rather than as a dirt block.
    public static ItemStack item(String path) {
        try {
            ItemStack result = TLibs.getItemAPI().getCreator().getItemFromPath(path);
            return isUnresolved(result) ? null : result;
        } catch (RuntimeException | LinkageError e) {
            report(path, e);
            return null;
        }
    }

    private static boolean isUnresolved(ItemStack item) {
        return item == null || item.getType().isAir() || item.getType() == Material.DIRT;
    }

    // The first path in order that this item matches, mapped to its activity
    // id, or null when it matches none. Each path is checked independently
    // so one that throws (a deleted MMOItems id, a malformed path) is
    // skipped rather than aborting the whole scan and losing every path
    // after it.
    public static String match(ItemStack item, List<Map.Entry<String, String>> paths) {
        try {
            var checker = TLibs.getItemAPI().getChecker();
            for (Map.Entry<String, String> entry : paths) {
                try {
                    if (checker.checkItemWithPath(item, entry.getKey())) {
                        return entry.getValue();
                    }
                } catch (RuntimeException | LinkageError e) {
                    report(entry.getKey(), e);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            report("<getChecker>", e);
        }
        return null;
    }

    private static void report(String path, Throwable e) {
        if (reportedPaths.add(path)) {
            Bukkit.getLogger().warning("[activity] TLibs item path '" + path + "' failed to resolve ("
                + e.getClass().getSimpleName() + ": " + e.getMessage() + ") - it will be skipped.");
        }
    }
}
