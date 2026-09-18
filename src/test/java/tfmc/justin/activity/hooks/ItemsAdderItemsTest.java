package tfmc.justin.activity.hooks;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.managers.TestManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        private int amount;

        TestStack(Material type) {
            this(type, 64);
        }

        TestStack(Material type, int amount) {
            this.type = type;
            this.amount = amount;
        }

        @Override
        public Material getType() {
            return type;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public void setAmount(int amount) {
            this.amount = amount;
        }

        // The real ItemStack#clone goes through the item registry; this is the
        // same "a copy, not the original" contract without a server
        @Override
        public TestStack clone() {
            return new TestStack(type, amount);
        }
    }

    // A copy, never ItemsAdder's own instance: CustomStack#getItemStack()
    // hands back the CustomStack's field, and the caller sets a display name
    // and lore on what it gets. The single-item amount is set on the copy.
    @Test
    void aResolvedIdIsReturnedAsACopy() {
        ItemStack saucepan = new TestStack(Material.DIAMOND_SWORD, 64);
        ItemsAdderItems ia = items(id -> saucepan);

        ItemStack resolved = ia.resolve("tfmc:saucepan");

        assertNotSame(saucepan, resolved);
        assertEquals(Material.DIAMOND_SWORD, resolved.getType());
        assertEquals(1, resolved.getAmount());
        // the hook's own stack is untouched by the amount the caller gets
        assertEquals(64, saucepan.getAmount());
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

    // ====================================
    // getInstance() answers null for the whole duration of /iareload and of
    // ItemsAdder's async pack load, so an id that came back null once is NOT
    // known bad - one GUI open inside that window must not cost the icon and
    // the reward for the rest of the uptime. Only the log line is silenced.
    // ====================================
    @Test
    void anUnresolvedIdIsRetriedOnEveryCall() {
        int[] calls = {0};
        ItemStack saucepan = new TestStack(Material.DIAMOND_SWORD);
        ItemsAdderItems ia = items(id -> calls[0]++ < 2 ? null : saucepan);

        assertNull(ia.resolve("tfmc:saucepan"));
        assertNull(ia.resolve("tfmc:saucepan"));
        // ItemsAdder is back
        assertEquals(Material.DIAMOND_SWORD, ia.resolve("tfmc:saucepan").getType());

        assertEquals(3, calls[0]);
        assertEquals(1, logged.size(), logged.toString());
    }

    // A throwing call is the genuinely permanent condition, so that id - and
    // ItemsAdder's own per-call logging with it - is not asked about again
    @Test
    void aThrowingIdStopsCallingTheCreator() {
        int[] calls = {0};
        ItemsAdderItems ia = items(id -> {
            calls[0]++;
            throw new IllegalStateException("boom");
        });

        ia.resolve("tfmc:nope");
        ia.resolve("tfmc:nope");
        ia.resolve("tfmc:nope");

        assertEquals(1, calls[0]);
    }

    // /activity reload is the operator's way out of that memo without a
    // server restart
    @Test
    void clearingTheMemosLetsAThrowingIdBeTriedAgain() {
        int[] calls = {0};
        ItemsAdderItems ia = items(id -> {
            calls[0]++;
            throw new IllegalStateException("boom");
        });

        ia.resolve("tfmc:nope");
        ia.resolve("tfmc:nope");
        ia.clearMemos();
        ia.resolve("tfmc:nope");

        assertEquals(2, calls[0]);
        assertEquals(2, logged.size(), logged.toString());
    }

    // A stack whose getType() throws is ItemsAdder's object too, and must cost
    // this icon rather than the GUI build around it
    @Test
    void aThrowingStackIsCaughtLikeAThrowingCall() {
        ItemsAdderItems ia = items(id -> new ItemStack() {
            @Override
            public Material getType() {
                throw new IllegalStateException("no registry");
            }
        });

        assertNull(ia.resolve("tfmc:saucepan"));
        assertEquals(1, logged.size(), logged.toString());
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

    // A resolved id must not stay silenced by an old memo: the id can fail for
    // a different reason later, and that later warning must still fire.
    @Test
    void aResolvedIdIsUnsilencedForALaterFailure() {
        int[] fail = {1};
        ItemsAdderItems ia = items(id -> fail[0] == 1
            ? null
            : new TestStack(Material.DIAMOND_SWORD));

        ia.resolve("tfmc:saucepan"); // fails, warns once
        fail[0] = 0;
        ia.resolve("tfmc:saucepan"); // resolves, should clear the memo
        fail[0] = 1;
        ia.resolve("tfmc:saucepan"); // fails again, must warn again

        assertEquals(2, logged.size(), logged.toString());
    }

    // ====================================
    // fromItemsAdder() is private and reflects into a hardcoded class name
    // (dev.lone.itemsadder.api.CustomStack), so the only way to drive a real
    // java.lang.reflect.InvocationTargetException through it - the exception
    // ItemsAdder's own getInstance()/getItemStack() throws while a pack is
    // mid-reload - is to put a fixture class on that exact name on the test
    // classpath (src/test/java/dev/lone/itemsadder/api/CustomStack.java) and
    // go through the real static entry point, not the creator seam.
    // ====================================
    @Test
    void anInvocationTargetExceptionFromItemsAdderIsRetriedOnEveryCall() {
        // Default.INSTANCE is built with Bukkit.getLogger(), which NPEs with no
        // server installed - and whether an earlier test in this fork already
        // installed one depends on class order (it differs between OSes)
        TestManagers.bukkit();
        dev.lone.itemsadder.api.CustomStack.getItemStackCalls = 0;
        ItemsAdderItems.reset();
        try {
            assertNull(ItemsAdderItems.item("tfmc:saucepan"));
            assertNull(ItemsAdderItems.item("tfmc:saucepan"));
            assertNull(ItemsAdderItems.item("tfmc:saucepan"));

            assertTrue(dev.lone.itemsadder.api.CustomStack.getItemStackCalls > 1,
                "expected the creator to be invoked more than once, got "
                    + dev.lone.itemsadder.api.CustomStack.getItemStackCalls);
        } finally {
            ItemsAdderItems.reset();
        }
    }
}
