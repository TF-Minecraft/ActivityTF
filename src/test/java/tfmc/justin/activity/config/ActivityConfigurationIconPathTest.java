package tfmc.justin.activity.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.reflect.ReflectionFactory;
import tfmc.justin.activity.models.ActivityDef;

import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

    private static final class TestPlugin extends JavaPlugin {
    }

    private final List<String> logged = new ArrayList<>();

    private final Handler capture = new Handler() {
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
    };

    private static final Logger LOGGER = Logger.getLogger("ActivityConfigurationIconPathTest");

    @BeforeEach
    void capture() {
        logged.clear();
        LOGGER.addHandler(capture);
    }

    @AfterEach
    void stopCapturing() {
        LOGGER.removeHandler(capture);
    }

    private static JavaPlugin stubPlugin() {
        try {
            ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
            Constructor<Object> objectCtor = Object.class.getDeclaredConstructor();
            Constructor<?> bypass = rf.newConstructorForSerialization(TestPlugin.class, objectCtor);
            JavaPlugin plugin = (JavaPlugin) bypass.newInstance();

            Field loggerField = JavaPlugin.class.getDeclaredField("logger");
            loggerField.setAccessible(true);
            loggerField.set(plugin, LOGGER);

            return plugin;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static ActivityDef load(String material) {
        ActivityConfiguration config = new ActivityConfiguration(stubPlugin());
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
