package tfmc.justin.activity.hooks;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// The logic around the one ItemsAdder call, with the call itself handed in -
// nothing here names an ItemsAdder class, so this runs on a classpath that
// has never heard of dev.lone.itemsadder.
//
// Not reachable headless, and so not covered here: fromItemsAdder(), the
// reflection into the real CustomStack - there is no such class to reflect
// into without ItemsAdder on a running server - and with it the
// Bukkit.getLogger() default instance it backs. What is pinned is everything
// resolve() does around that call, which is where a broken config.yml is
// either survived or not.
// ====================================
class ItemsAdderItemsTest {

    private final List<String> logged = new ArrayList<>();

    private ItemsAdderItems items(Function<String, ItemStack> creator) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return new ItemsAdderItems(creator, logger);
    }

    // The real ItemStack constructors go through the Bukkit registry, which
    // needs a running server; the protected no-arg one only nulls a field
    private static final class TestStack extends ItemStack {
        private final Material type;

        TestStack(Material type) {
            this.type = type;
        }

        @Override
        public Material getType() {
            return type;
        }
    }

    @Test
    void aResolvedIdIsReturnedAsIs() {
        ItemStack saucepan = new TestStack(Material.DIAMOND_SWORD);
        ItemsAdderItems ia = items(id -> saucepan);

        assertSame(saucepan, ia.resolve("tfmc:saucepan"));
        assertTrue(logged.isEmpty(), logged.toString());
    }

    // null is ItemsAdder saying "no such item", AIR is what an empty
    // CustomStack reads as - neither may reach a GUI icon or a payout
    @Test
    void anUnknownIdYieldsNoItem() {
        assertNull(items(id -> null).resolve("tfmc:nope"));
        assertNull(items(id -> new TestStack(Material.AIR)).resolve("tfmc:nope"));
    }

    // The GUI retries the path on every open and a payout on every claim, so
    // the warning must not repeat
    @Test
    void anUnknownIdIsLoggedOnceAcrossCalls() {
        ItemsAdderItems ia = items(id -> null);

        assertNull(ia.resolve("tfmc:nope"));
        assertNull(ia.resolve("tfmc:nope"));
        assertNull(ia.resolve("tfmc:nope"));

        assertEquals(1, logged.size(), logged.toString());
        assertTrue(logged.get(0).contains("tfmc:nope"), logged.get(0));
        assertTrue(logged.get(0).contains("could not be resolved"), logged.get(0));
    }

    // A throwing call must cost its own id only, never the GUI build or the
    // claim it happened inside
    @Test
    void aThrowingCreatorYieldsNoItemAndIsLoggedOnce() {
        ItemsAdderItems ia = items(id -> {
            throw new IllegalStateException("boom");
        });

        assertNull(ia.resolve("tfmc:nope"));
        assertNull(ia.resolve("tfmc:nope"));

        assertEquals(1, logged.size(), logged.toString());
        assertTrue(logged.get(0).contains("IllegalStateException"), logged.get(0));
    }

    // ItemsAdder missing at class-load time surfaces as a LinkageError, not a
    // RuntimeException - it must be caught the same way
    @Test
    void aLinkageErrorIsCaughtLikeAnyOtherFailure() {
        ItemsAdderItems ia = items(id -> {
            throw new NoClassDefFoundError("dev/lone/itemsadder/api/CustomStack");
        });

        assertNull(ia.resolve("tfmc:saucepan"));
        assertEquals(1, logged.size(), logged.toString());
    }

    // Once an id is known bad, ItemsAdder (and its own per-call logging) must
    // not be asked about it again
    @Test
    void aFailedIdStopsCallingTheCreator() {
        int[] calls = {0};
        ItemsAdderItems ia = items(id -> {
            calls[0]++;
            return null;
        });

        ia.resolve("tfmc:nope");
        ia.resolve("tfmc:nope");
        ia.resolve("tfmc:nope");

        assertEquals(1, calls[0]);
    }

    // A good id is never cached: the GUI wants a fresh stack each open, and a
    // payout must not hand out the same mutated instance twice
    @Test
    void aResolvableIdIsInvokedEveryTime() {
        int[] calls = {0};
        ItemsAdderItems ia = items(id -> {
            calls[0]++;
            return new TestStack(Material.DIAMOND_SWORD);
        });

        ia.resolve("tfmc:saucepan");
        ia.resolve("tfmc:saucepan");
        ia.resolve("tfmc:saucepan");

        assertEquals(3, calls[0]);
    }

    // One broken id must not silence a different one
    @Test
    void theMemoIsPerId() {
        ItemsAdderItems ia = items(id -> null);

        ia.resolve("tfmc:a");
        ia.resolve("tfmc:a");
        ia.resolve("tfmc:b");

        assertEquals(2, logged.size(), logged.toString());
    }
}
