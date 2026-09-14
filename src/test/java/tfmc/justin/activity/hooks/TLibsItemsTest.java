package tfmc.justin.activity.hooks;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
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
// The logic around the two TLibs calls, with the calls themselves handed in -
// nothing here names a TLibs class, and nothing here builds an ItemStack,
// which blows up headless on the Bukkit registry. The instance is therefore
// parameterised on Material, a plain enum: that also lets the real unresolved
// rule (null / air / TLibs' DIRT sentinel) be the one under test.
// ====================================
class TLibsItemsTest {

    private final List<String> logged = new ArrayList<>();

    private TLibsItems<Material> items(Function<String, Material> creator,
                                       BiPredicate<Material, String> checker) {
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
        return new TLibsItems<>(creator, checker, TLibsItems::isUnresolved, logger);
    }

    private static List<Map.Entry<String, String>> paths(String... pathThenId) {
        List<Map.Entry<String, String>> paths = new ArrayList<>();
        for (int i = 0; i < pathThenId.length; i += 2) {
            paths.add(Map.entry(pathThenId[i], pathThenId[i + 1]));
        }
        return paths;
    }

    // Config order decides: the first path the item matches is the activity
    // credited, even when a later one would match too
    @Test
    void firstMatchingPathWins() {
        TLibsItems<Material> tlibs = items(path -> null, (item, path) -> true);

        assertEquals("first", tlibs.firstMatch(Material.STONE,
            paths("m.material.a", "first", "m.material.b", "second")));
    }

    @Test
    void anItemMatchingNoPathCreditsNothing() {
        TLibsItems<Material> tlibs = items(path -> null, (item, path) -> false);

        assertNull(tlibs.firstMatch(Material.STONE, paths("m.material.a", "first")));
        assertNull(tlibs.firstMatch(Material.STONE, paths()));
    }

    // A deleted MMOItems id throws out of the checker. That must cost its own
    // path only - every path after it still gets its chance
    @Test
    void aThrowingPathIsSkippedAndTheNextOneStillMatches() {
        TLibsItems<Material> tlibs = items(path -> null, (item, path) -> {
            if (path.equals("m.material.broken")) {
                throw new IllegalStateException("no such item");
            }
            return path.equals("m.material.good");
        });

        assertEquals("good", tlibs.firstMatch(Material.STONE,
            paths("m.material.broken", "broken", "m.material.good", "good")));
    }

    // Every craft of that item would otherwise flood the console
    @Test
    void aThrowingPathIsLoggedOnceAcrossCalls() {
        TLibsItems<Material> tlibs = items(path -> null, (item, path) -> {
            throw new IllegalStateException("no such item");
        });

        tlibs.firstMatch(Material.STONE, paths("m.material.broken", "broken"));
        tlibs.firstMatch(Material.STONE, paths("m.material.broken", "broken"));

        assertEquals(1, logged.size(), logged.toString());
        assertTrue(logged.get(0).contains("m.material.broken"), logged.get(0));
    }

    // null, air and the DIRT sentinel are all TLibs saying "I could not build
    // that" - none of them may reach the GUI as an icon
    @Test
    void anUnresolvedPathYieldsNoItem() {
        assertNull(items(path -> null, (item, path) -> false).resolve("m.material.x"));
        assertNull(items(path -> Material.AIR, (item, path) -> false).resolve("m.material.x"));
        assertNull(items(path -> Material.DIRT, (item, path) -> false).resolve("m.material.x"));
    }

    // The GUI retries the path on every open, so the warning must not repeat
    @Test
    void anUnresolvedPathIsLoggedOnceAcrossCalls() {
        TLibsItems<Material> tlibs = items(path -> Material.DIRT, (item, path) -> false);

        assertNull(tlibs.resolve("m.material.x"));
        assertNull(tlibs.resolve("m.material.x"));

        assertEquals(1, logged.size(), logged.toString());
        assertTrue(logged.get(0).contains("m.material.x"), logged.get(0));
        assertTrue(logged.get(0).contains("could not be resolved"), logged.get(0));
    }

    @Test
    void aThrowingCreatorYieldsNoItemAndIsLoggedOnce() {
        TLibsItems<Material> tlibs = items(path -> {
            throw new IllegalStateException("boom");
        }, (item, path) -> false);

        assertNull(tlibs.resolve("m.material.x"));
        assertNull(tlibs.resolve("m.material.x"));

        assertEquals(1, logged.size(), logged.toString());
    }

    // A real item comes back untouched - the GUI then puts the activity's own
    // name and lore on it
    @Test
    void aResolvedPathIsReturnedAsIs() {
        TLibsItems<Material> tlibs = items(path -> Material.DIAMOND_SWORD, (item, path) -> false);

        assertSame(Material.DIAMOND_SWORD, tlibs.resolve("m.sword.excalibur"));
        assertTrue(logged.isEmpty(), logged.toString());
    }
}
