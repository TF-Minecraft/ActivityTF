package tfmc.justin.activity.hooks;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import tfmc.justin.activity.utils.Utils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

// ====================================
// The only class that touches ItemsAdder, and it does so purely by
// reflection: ItemsAdder is not a compile-time dependency of this plugin and
// must not become one, so a server without it simply never resolves an
// ia.<namespace:id> path.
//
// Shaped like TLibsItems: the call itself is handed in, so the logic around
// it (a failing id is reported once and then short-circuits, a null or AIR
// stack reads as "no item") can be exercised headless, and fromItemsAdder()
// - the only method that names an ItemsAdder class - only runs when the
// default instance is first used.
//
// Every failure mode ends the same way, with null: no exception may escape
// into a GUI build or into a reward payout, where it would cost the whole
// menu or the whole claim rather than one icon.
// ====================================
public final class ItemsAdderItems {

    private final Function<String, ItemStack> creator;
    private final Logger logger;

    // Ids already known unresolved or throwing, so a broken config.yml entry
    // warns once rather than once per GUI open or per claim, and the creator
    // (with ItemsAdder's own logging) is not invoked for them again
    private final Set<String> unresolvedIds = ConcurrentHashMap.newKeySet();

    ItemsAdderItems(Function<String, ItemStack> creator, Logger logger) {
        this.creator = creator;
        this.logger = logger;
    }

    private static final class Default {
        static final ItemsAdderItems INSTANCE =
            new ItemsAdderItems(ItemsAdderItems::fromItemsAdder, Bukkit.getLogger());
    }

    // The item behind a '<namespace>:<id>', or null if ItemsAdder cannot build
    // one. Callers must have checked that ItemsAdder is enabled first.
    public static ItemStack item(String id) {
        return Default.INSTANCE.resolve(id);
    }

    ItemStack resolve(String id) {
        if (unresolvedIds.contains(id)) {
            return null;
        }
        ItemStack stack;
        try {
            stack = creator.apply(id);
        } catch (RuntimeException | LinkageError e) {
            return fail(id, "failed to resolve (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
        // Compared against AIR rather than through Material#isAir(), which
        // goes through the block registry and so needs a running server
        if (stack == null || stack.getType() == null || stack.getType() == Material.AIR) {
            return fail(id, "could not be resolved - check the namespace and id");
        }
        return stack;
    }

    private ItemStack fail(String id, String why) {
        if (unresolvedIds.add(id)) {
            logger.warning("[activity] ItemsAdder item '" + Utils.safeForLog(id) + "' " + why
                + " - it will be skipped.");
        }
        return null;
    }

    // CustomStack.getInstance("tfmc:saucepan").getItemStack(), by reflection:
    // nothing here is on the compile or runtime classpath. Any reflective
    // failure is rethrown as a RuntimeException so resolve() reports it the
    // same way it reports a throwing call.
    private static ItemStack fromItemsAdder(String id) {
        try {
            Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object instance = customStack.getMethod("getInstance", String.class).invoke(null, id);
            if (instance == null) {
                return null;
            }
            Object item = customStack.getMethod("getItemStack").invoke(instance);
            if (!(item instanceof ItemStack stack)) {
                return null;
            }
            stack.setAmount(1);
            return stack;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
