package net.tfminecraft.activitytf.hooks;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import net.tfminecraft.activitytf.managers.TestManagers;

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

        @Override
        public TestStack clone() {
            return new TestStack(type, amount);
        }
    }

    @Test
    void aResolvedIdIsReturnedAsACopy() {
        ItemStack saucepan = new TestStack(Material.DIAMOND_SWORD, 64);
        ItemsAdderItems ia = items(id -> saucepan);

        ItemStack resolved = ia.resolve("tfmc:saucepan");

        assertNotSame(saucepan, resolved);
        assertEquals(Material.DIAMOND_SWORD, resolved.getType());
        assertEquals(1, resolved.getAmount());
        assertEquals(64, saucepan.getAmount());
        assertTrue(logged.isEmpty(), logged.toString());
    }

    @Test
    void anUnknownIdYieldsNoItem() {
        assertNull(items(id -> null).resolve("tfmc:nope"));
        assertNull(items(id -> new TestStack(Material.AIR)).resolve("tfmc:nope"));
    }

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

    @Test
    void aLinkageErrorIsCaughtLikeAnyOtherFailure() {
        ItemsAdderItems ia = items(id -> {
            throw new NoClassDefFoundError("dev/lone/itemsadder/api/CustomStack");
        });

        assertNull(ia.resolve("tfmc:saucepan"));
        assertEquals(1, logged.size(), logged.toString());
    }

    @Test
    void anUnresolvedIdIsRetriedOnEveryCall() {
        int[] calls = {0};
        ItemStack saucepan = new TestStack(Material.DIAMOND_SWORD);
        ItemsAdderItems ia = items(id -> calls[0]++ < 2 ? null : saucepan);

        assertNull(ia.resolve("tfmc:saucepan"));
        assertNull(ia.resolve("tfmc:saucepan"));
        assertEquals(Material.DIAMOND_SWORD, ia.resolve("tfmc:saucepan").getType());

        assertEquals(3, calls[0]);
        assertEquals(1, logged.size(), logged.toString());
    }

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

    @Test
    void theMemoIsPerId() {
        ItemsAdderItems ia = items(id -> null);

        ia.resolve("tfmc:a");
        ia.resolve("tfmc:a");
        ia.resolve("tfmc:b");

        assertEquals(2, logged.size(), logged.toString());
    }

    @Test
    void aResolvedIdIsUnsilencedForALaterFailure() {
        int[] fail = {1};
        ItemsAdderItems ia = items(id -> fail[0] == 1
            ? null
            : new TestStack(Material.DIAMOND_SWORD));

        ia.resolve("tfmc:saucepan");
        fail[0] = 0;
        ia.resolve("tfmc:saucepan");
        fail[0] = 1;
        ia.resolve("tfmc:saucepan");

        assertEquals(2, logged.size(), logged.toString());
    }

    @Test
    void anInvocationTargetExceptionFromItemsAdderIsRetriedOnEveryCall() {
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
    @Test
    void reflectedProviderReturnsDetachedItemsAndRetriesMissingOrUnexpectedResults() {
        TestManagers.bukkit();
        var provider = org.mockito.Mockito.mock(dev.lone.itemsadder.api.CustomStack.class);
        ItemStack original = new TestStack(Material.DIAMOND, 64);
        try (var stacks = org.mockito.Mockito.mockStatic(dev.lone.itemsadder.api.CustomStack.class)) {
            ItemsAdderItems.reset();
            assertNull(ItemsAdderItems.item("tfmc:missing"));
            stacks.when(() -> dev.lone.itemsadder.api.CustomStack.getInstance("tfmc:missing")).thenReturn(provider);
            org.mockito.Mockito.when(provider.getItemStack()).thenReturn("invalid provider result", original);
            assertNull(ItemsAdderItems.item("tfmc:missing"));
            ItemStack resolved = ItemsAdderItems.item("tfmc:missing");
            assertNotSame(original, resolved);
            assertEquals(Material.DIAMOND, resolved.getType());
            assertEquals(1, resolved.getAmount());
            assertEquals(64, original.getAmount());
        } finally {
            ItemsAdderItems.reset();
        }
    }

    @Test
    void absentOptionalProviderFailsClosedAndLogsOnce() throws Exception {
        TestManagers.bukkit();
        Logger logger = org.mockito.Mockito.mock(Logger.class);
        ClassLoader parent = ItemsAdderItems.class.getClassLoader();
        ClassLoader withoutProvider = new ClassLoader(parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.equals("dev.lone.itemsadder.api.CustomStack")) throw new ClassNotFoundException(name);
                    if (!name.startsWith(ItemsAdderItems.class.getName())) return super.loadClass(name, resolve);
                    Class<?> result = findLoadedClass(name);
                    if (result == null) {
                        try (var stream = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            byte[] bytes = stream.readAllBytes();
                            result = defineClass(name, bytes, 0, bytes.length, ItemsAdderItems.class.getProtectionDomain());
                        } catch (java.io.IOException e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    }
                    if (resolve) resolveClass(result);
                    return result;
                }
            }
        };
        try (var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getLogger).thenReturn(logger);
            Class<?> isolated = withoutProvider.loadClass(ItemsAdderItems.class.getName());
            var item = isolated.getMethod("item", String.class);
            assertNull(item.invoke(null, "tfmc:optional"));
            assertNull(item.invoke(null, "tfmc:optional"));
            org.mockito.Mockito.verify(logger).warning(org.mockito.ArgumentMatchers.contains("IllegalStateException"));
        }
    }

}
