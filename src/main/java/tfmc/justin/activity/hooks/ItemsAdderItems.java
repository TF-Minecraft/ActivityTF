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
// it (a failing id is reported once, a null or AIR stack reads as "no item")
// can be exercised headless, and fromItemsAdder() - the only method that
// names an ItemsAdder class - only runs when the default instance is first
// used.
//
// Every failure mode of the ItemsAdder call ends the same way, with null:
// nothing ItemsAdder throws - from the call itself or from inspecting what it
// handed back - may escape into a GUI build or into a reward payout, where it
// would cost the whole menu or the whole claim rather than one icon.
// ====================================
public final class ItemsAdderItems {

    private final Function<String, ItemStack> creator;
    private final Logger logger;

    // ====================================
    // Ids whose call threw. That is a permanent condition (ItemsAdder absent
    // at class-load time, a reflective signature that does not exist), so the
    // creator - and ItemsAdder's own logging with it - is not invoked for them
    // again.
    //
    // Deliberately NOT where a "getInstance returned null" id lands: that is
    // ItemsAdder's answer for the whole duration of /iareload and of its async
    // pack load, and memoising it there would turn one GUI open inside that
    // window into a permanently broken icon and a reward that can never be
    // paid again. Those ids only go into reportedIds below, which silences the
    // log line and nothing else.
    // ====================================
    private final Set<String> brokenIds = ConcurrentHashMap.newKeySet();

    // Ids ItemsAdder did not know about, so a broken config.yml entry warns
    // once rather than once per GUI open or per claim. Resolution is still
    // retried every time - same idiom as ActivityManager.reportedItemPaths,
    // which gates the warning and not the work
    private final Set<String> reportedIds = ConcurrentHashMap.newKeySet();

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

    // Forgets both memos. Called from the /activity reload path, so an
    // operator who reinstalled or repacked an item has a way to clear a
    // permanently-broken id without restarting the server.
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
            // Compared against AIR rather than through Material#isAir(), which
            // goes through the block registry and so needs a running server.
            // Inside the try, so a stack whose getType() throws costs this icon
            // and not the build around it
            if (stack == null || stack.getType() == null || stack.getType() == Material.AIR) {
                warnOnce(reportedIds, id, "could not be resolved - check the namespace and id");
                return null;
            }
            // Cloned before anything is done to it: CustomStack#getItemStack()
            // hands back ItemsAdder's own instance, and callers set a display
            // name and lore on what they get. Mutating it would rename the
            // pack's item for every later /iagive and every recipe result
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
            return item instanceof ItemStack stack ? stack : null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
