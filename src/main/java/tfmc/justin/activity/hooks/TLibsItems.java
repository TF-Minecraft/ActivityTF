package tfmc.justin.activity.hooks;

import me.Plugins.TLibs.TLibs;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

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

    private TLibsItems() {
    }

    // The item behind an m.<type>.<id> path, or null if TLibs cannot build one
    public static ItemStack item(String path) {
        try {
            return TLibs.getItemAPI().getCreator().getItemFromPath(path);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // The first path in order that this item matches, mapped to its activity
    // id, or null when it matches none
    public static String match(ItemStack item, List<Map.Entry<String, String>> paths) {
        try {
            var checker = TLibs.getItemAPI().getChecker();
            for (Map.Entry<String, String> entry : paths) {
                if (checker.checkItemWithPath(item, entry.getKey())) {
                    return entry.getValue();
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return null;
    }
}
