package tfmc.justin.activity.hooks;

import me.Plugins.TLibs.TLibs;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import tfmc.justin.activity.utils.Utils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

// ====================================
// The only class that touches TLibs. Like the listeners in
// ActivityPlugin#registerHooks it is never loaded on a server without the
// plugin, because every caller checks isPluginEnabled("TLibs") first.
//
// Both calls are wrapped: a path naming an MMOItems item that no longer
// exists is an admin typo in config.yml, and it must cost one icon or one
// craft credit, not the whole GUI or the craft event.
//
// The two TLibs calls are handed in rather than called inline, so the logic
// around them (first match wins, a throwing path is skipped, a failing path
// is logged once) can be exercised headless. fromTLibs() is the only place
// that names a TLibs class, and it only runs when the default instance is
// first used - so this class loads and tests fine without TLibs.
// ====================================
public final class TLibsItems<T> {

    private final Function<String, T> creator;
    private final BiPredicate<T, String> checker;
    private final Predicate<T> unresolved;
    private final Logger logger;

    // Paths already logged as failing, so a broken config.yml entry warns
    // once rather than once per GUI open or per craft.
    private final Set<String> reportedPaths = ConcurrentHashMap.newKeySet();

    // Paths already known unresolved or throwing in resolve(), so the creator
    // (and TLibs' own per-call logging) is not invoked again for them. Kept
    // separate from reportedPaths: that set is also written by firstMatch()
    // for a throwing checker, which says nothing about whether the creator
    // side resolves the same path.
    private final Set<String> unresolvedPaths = ConcurrentHashMap.newKeySet();

    TLibsItems(Function<String, T> creator, BiPredicate<T, String> checker,
               Predicate<T> unresolved, Logger logger) {
        this.creator = creator;
        this.checker = checker;
        this.unresolved = unresolved;
        this.logger = logger;
    }

    // Built on first use, not on class load: touching TLibs any earlier would
    // defeat the "never loaded without the plugin" rule above.
    private static final class Default {
        static final TLibsItems<ItemStack> INSTANCE = fromTLibs();
    }

    private static TLibsItems<ItemStack> fromTLibs() {
        return new TLibsItems<>(
            path -> TLibs.getItemAPI().getCreator().getItemFromPath(path),
            (item, path) -> TLibs.getItemAPI().getChecker().checkItemWithPath(item, path),
            item -> isUnresolved(item == null ? null : item.getType()),
            Bukkit.getLogger());
    }

    // TLibs answers a path it recognises the shape of but cannot resolve
    // (MMOItems/MythicLib missing, or the id deleted) with null, air, or its
    // DIRT sentinel - all three must read as "no icon", not as a dirt block.
    //
    // The air constants are compared by identity rather than through
    // Material#isAir(), which goes through the block registry and so needs a
    // running server; these three are the whole of it.
    static boolean isUnresolved(Material type) {
        return type == null || type == Material.AIR || type == Material.CAVE_AIR
            || type == Material.VOID_AIR || type == Material.DIRT;
    }

    // The item behind an m.<type>.<id> path, or null if TLibs cannot build one
    public static ItemStack item(String path) {
        return Default.INSTANCE.resolve(path);
    }

    // The first path in order that this item matches, mapped to its activity
    // id, or null when it matches none
    public static String match(ItemStack item, List<Map.Entry<String, String>> paths) {
        return Default.INSTANCE.firstMatch(item, paths);
    }

    // A path that resolves to nothing is reported once and then keeps
    // returning null without calling the creator again, so the caller falls
    // back every time without logging again - TLibs itself already logs on
    // every call. A path that resolves fine is never cached: the GUI wants a
    // fresh stack each time.
    T resolve(String path) {
        if (unresolvedPaths.contains(path)) {
            return null;
        }
        T result;
        try {
            result = creator.apply(path);
        } catch (RuntimeException | LinkageError e) {
            report(path, e);
            unresolvedPaths.add(path);
            return null;
        }
        if (unresolved.test(result)) {
            if (reportedPaths.add(path)) {
                logger.warning("[activity] item path '" + Utils.safeForLog(path) + "' could not be resolved"
                    + " by TLibs - check the MMOItems type/id.");
            }
            unresolvedPaths.add(path);
            return null;
        }
        return result;
    }

    // Each path is checked independently so one that throws (a deleted
    // MMOItems id, a malformed path) is skipped rather than aborting the whole
    // scan and losing every path after it.
    String firstMatch(T item, List<Map.Entry<String, String>> paths) {
        for (Map.Entry<String, String> entry : paths) {
            try {
                if (checker.test(item, entry.getKey())) {
                    return entry.getValue();
                }
            } catch (RuntimeException | LinkageError e) {
                report(entry.getKey(), e);
            }
        }
        return null;
    }

    private void report(String path, Throwable e) {
        if (reportedPaths.add(path)) {
            logger.warning("[activity] TLibs item path '" + Utils.safeForLog(path) + "' failed to resolve ("
                + e.getClass().getSimpleName() + ": " + e.getMessage() + ") - it will be skipped.");
        }
    }
}
