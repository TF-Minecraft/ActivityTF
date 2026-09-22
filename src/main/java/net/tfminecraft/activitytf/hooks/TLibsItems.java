package net.tfminecraft.activitytf.hooks;

import net.tfminecraft.tlibs.TLibs;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import net.tfminecraft.activitytf.utils.Utils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

public final class TLibsItems<T> {

    private final Function<String, T> creator;
    private final BiPredicate<T, String> checker;
    private final Predicate<T> unresolved;
    private final Logger logger;

    private final Set<String> reportedPaths = ConcurrentHashMap.newKeySet();

    private final Set<String> unresolvedPaths = ConcurrentHashMap.newKeySet();

    TLibsItems(Function<String, T> creator, BiPredicate<T, String> checker,
               Predicate<T> unresolved, Logger logger) {
        this.creator = creator;
        this.checker = checker;
        this.unresolved = unresolved;
        this.logger = logger;
    }

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

    static boolean isUnresolved(Material type) {
        return type == null || type == Material.AIR || type == Material.CAVE_AIR
            || type == Material.VOID_AIR || type == Material.DIRT;
    }

    public static ItemStack item(String path) {
        return Default.INSTANCE.resolve(path);
    }

    public static String match(ItemStack item, List<Map.Entry<String, String>> paths) {
        return Default.INSTANCE.firstMatch(item, paths);
    }

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
