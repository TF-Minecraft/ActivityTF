package net.tfminecraft.activitytf.hooks;

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

    @Test
    void anUnresolvedPathYieldsNoItem() {
        assertNull(items(path -> null, (item, path) -> false).resolve("m.material.x"));
        assertNull(items(path -> Material.AIR, (item, path) -> false).resolve("m.material.x"));
        assertNull(items(path -> Material.DIRT, (item, path) -> false).resolve("m.material.x"));
    }

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

    @Test
    void aResolvedPathIsReturnedAsIs() {
        TLibsItems<Material> tlibs = items(path -> Material.DIAMOND_SWORD, (item, path) -> false);

        assertSame(Material.DIAMOND_SWORD, tlibs.resolve("m.sword.excalibur"));
        assertTrue(logged.isEmpty(), logged.toString());
    }

    @Test
    void anUnresolvedPathStopsCallingTheCreator() {
        int[] calls = {0};
        TLibsItems<Material> tlibs = items(path -> {
            calls[0]++;
            return Material.DIRT;
        }, (item, path) -> false);

        assertNull(tlibs.resolve("m.material.x"));
        assertNull(tlibs.resolve("m.material.x"));
        assertNull(tlibs.resolve("m.material.x"));

        assertEquals(1, calls[0]);
    }

    @Test
    void aThrowingCreatorStopsBeingCalledAgain() {
        int[] calls = {0};
        TLibsItems<Material> tlibs = items(path -> {
            calls[0]++;
            throw new IllegalStateException("boom");
        }, (item, path) -> false);

        assertNull(tlibs.resolve("m.material.x"));
        assertNull(tlibs.resolve("m.material.x"));
        assertNull(tlibs.resolve("m.material.x"));

        assertEquals(1, calls[0]);
    }

    @Test
    void aResolvablePathIsInvokedEveryTime() {
        int[] calls = {0};
        TLibsItems<Material> tlibs = items(path -> {
            calls[0]++;
            return Material.DIAMOND_SWORD;
        }, (item, path) -> false);

        tlibs.resolve("m.sword.excalibur");
        tlibs.resolve("m.sword.excalibur");
        tlibs.resolve("m.sword.excalibur");

        assertEquals(3, calls[0]);
    }
}
