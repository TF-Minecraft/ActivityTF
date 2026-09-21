package tfmc.justin.activity.hooks;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import tfmc.justin.activity.utils.Utils;

import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

public final class ItemsAdderItems {

    private final Function<String, ItemStack> creator;
    private final Logger logger;

    private final Set<String> brokenIds = ConcurrentHashMap.newKeySet();

    private final Set<String> reportedIds = ConcurrentHashMap.newKeySet();

    ItemsAdderItems(Function<String, ItemStack> creator, Logger logger) {
        this.creator = creator;
        this.logger = logger;
    }

    private static final class Default {
        static final ItemsAdderItems INSTANCE =
            new ItemsAdderItems(ItemsAdderItems::fromItemsAdder, Bukkit.getLogger());
    }

    public static ItemStack item(String id) {
        return Default.INSTANCE.resolve(id);
    }

    public static void reset() {
        Default.INSTANCE.clearMemos();
    }

    void clearMemos() {
        brokenIds.clear();
        reportedIds.clear();
    }

    ItemStack resolve(String id) {
        if (brokenIds.contains(id)) {
            return null;
        }
        try {
            ItemStack stack = creator.apply(id);
            if (stack == null || stack.getType() == null || stack.getType() == Material.AIR) {
                warnOnce(reportedIds, id, "could not be resolved - check the namespace and id");
                return null;
            }
            reportedIds.remove(id);
            ItemStack copy = stack.clone();
            copy.setAmount(1);
            return copy;
        } catch (RuntimeException | LinkageError e) {
            warnOnce(brokenIds, id,
                "failed to resolve (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            return null;
        }
    }

    private void warnOnce(Set<String> memo, String id, String why) {
        if (memo.add(id)) {
            logger.warning("[activity] ItemsAdder item '" + Utils.safeForLog(id) + "' " + why
                + " - it will be skipped.");
        }
    }

    private static ItemStack fromItemsAdder(String id) {
        try {
            Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object instance = customStack.getMethod("getInstance", String.class).invoke(null, id);
            if (instance == null) {
                return null;
            }
            Object item = customStack.getMethod("getItemStack").invoke(instance);
            return item instanceof ItemStack stack ? stack : null;
        } catch (InvocationTargetException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
