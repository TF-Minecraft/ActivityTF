package tfmc.justin.activity.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.ActivityDef;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// What an activity's 'material:' key becomes: the Material the GUI falls back
// to, and the path the GUI asks a plugin for. Reached through the private
// loadActivities() the way ActivityConfigurationStationTest reaches it -
// load() itself needs a live Bukkit server.
//
// Not reachable headless, and so not covered here: ActivityGui#iconStack
// itself, because every branch of it ends in new ItemStack(Material), which
// goes through the item registry of a running server. What is pinned instead
// is the pair iconStack is handed - an ia. path plus the Material to fall back
// to when nothing can be built from it - and, in ItemsAdderItemsTest, that the
// hook does answer null for an id that cannot be resolved. The two together
// are the fallback.
// ====================================
class ActivityConfigurationIconPathTest {

    private final List<String> logged = new ArrayList<>();

    private ActivityDef load(String material) {
        ActivityConfiguration config = new ActivityConfiguration(TestPlugins.capturing(logged));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(
            "activities:\n  cook_dish:\n    points: 1\n    material: \"" + material + "\"\n"));
        ConfigurationSection section = yaml.getConfigurationSection("activities");
        try {
            Method method = ActivityConfiguration.class.getDeclaredMethod("loadActivities", ConfigurationSection.class);
            method.setAccessible(true);
            method.invoke(config, section);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return config.activity("cook_dish");
    }

    private boolean loggedContains(String fragment) {
        return logged.stream().anyMatch(message -> message.contains(fragment));
    }

    // The live server's own report: 'ia.tfmc:saucepan' used to be called an
    // unsupported path and lose its icon
    @Test
    void anItemsAdderIconIsKeptAsAPathWithPaperBehindIt() {
        ActivityDef def = load("ia.tfmc:saucepan");

        assertEquals("ia.tfmc:saucepan", def.iconPath());
        // what iconStack falls back to when ItemsAdder is absent or the id no
        // longer resolves
        assertEquals(Material.PAPER, def.icon());
        assertFalse(loggedContains("Unsupported item path"));
        assertFalse(loggedContains("Unknown material"));
    }

    // The dotted spelling is the same item, and only one form reaches the hook
    @Test
    void theDottedIconFormIsNormalizedToTheColonForm() {
        assertEquals("ia.tfmc:saucepan", load("ia.tfmc.saucepan").iconPath());
        assertEquals("ia.tfmc:saucepan", load("IA.tfmc.saucepan").iconPath());
    }

    // Nothing to ask ItemsAdder for, so no path is kept - the icon is PAPER
    // and the admin is told which key to fix
    @Test
    void aMalformedItemsAdderIconFallsBackToPaperAndIsNamed() {
        ActivityDef def = load("ia.saucepan");

        assertNull(def.iconPath());
        assertEquals(Material.PAPER, def.icon());
        assertTrue(loggedContains("Malformed item path 'ia.saucepan' at activities.cook_dish.material"));
        assertTrue(loggedContains("expected ia.<namespace:id>"));
    }

    // Still reported as a path rather than as an unknown material, and the
    // warning now names every form that would have worked
    @Test
    void anotherPluginsPrefixIsStillUnsupportedAndSaysWhatIsSupported() {
        ActivityDef def = load("nx.saucepan");

        assertNull(def.iconPath());
        assertEquals(Material.PAPER, def.icon());
        assertTrue(loggedContains("Unsupported item path 'nx.saucepan' at activities.cook_dish.material"));
        assertTrue(loggedContains("ia.<namespace:id>"));
    }

    // Whitespace around a segment is the admin's spacing, not part of the id:
    // ItemsAdder is whitespace-sensitive, so 'tfmc: saucepan' would resolve to
    // nothing at runtime while passing load clean
    @Test
    void spacingAroundAnItemsAdderSegmentIsStripped() {
        assertEquals("ia.tfmc:saucepan", load("ia.tfmc: saucepan").iconPath());
        assertEquals("ia.tfmc:saucepan", load("ia. tfmc . saucepan ").iconPath());
    }

    // Whitespace inside a segment cannot be spacing, so it is named at load
    // rather than failing silently per claim
    @Test
    void whitespaceInsideAnItemsAdderSegmentIsRefusedAtLoad() {
        ActivityDef def = load("ia.tfmc:sauce pan");

        assertNull(def.iconPath());
        assertEquals(Material.PAPER, def.icon());
        assertTrue(loggedContains("Malformed item path 'ia.tfmc:sauce pan' at activities.cook_dish.material"));
    }

    // Case is left exactly as the admin wrote it - ItemsAdder's matching rules
    // are not guessed at here
    @Test
    void theCaseOfAnItemsAdderIdIsNotTouched() {
        assertEquals("ia.TFMC:Saucepan", load("ia.TFMC:Saucepan").iconPath());
    }

    // The m. and bare forms are untouched by any of this. An m. path drops its
    // path here rather than at GUI-build time - itemPathsUsable is false with
    // no server behind this test - which is exactly what it did before; the
    // PAPER behind it is the part that matters.
    @Test
    void theOlderFormsStillLoadTheWayTheyDid() {
        assertNull(load("m.material.steel").iconPath());
        assertEquals(Material.PAPER, load("m.material.steel").icon());

        assertNull(load("DIAMOND").iconPath());
        assertEquals(Material.DIAMOND, load("DIAMOND").icon());

        assertNull(load("v.diamond").iconPath());
        assertEquals(Material.DIAMOND, load("v.diamond").icon());
    }
}
