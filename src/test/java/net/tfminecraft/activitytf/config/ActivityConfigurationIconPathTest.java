package net.tfminecraft.activitytf.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import net.tfminecraft.activitytf.models.ActivityDef;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void anItemsAdderIconIsKeptAsAPathWithPaperBehindIt() {
        ActivityDef def = load("ia.tfmc:saucepan");

        assertEquals("ia.tfmc:saucepan", def.iconPath());
        assertEquals(Material.PAPER, def.icon());
        assertFalse(loggedContains("Unsupported item path"));
        assertFalse(loggedContains("Unknown material"));
    }

    @Test
    void theDottedIconFormIsNormalizedToTheColonForm() {
        assertEquals("ia.tfmc:saucepan", load("ia.tfmc.saucepan").iconPath());
        assertEquals("ia.tfmc:saucepan", load("IA.tfmc.saucepan").iconPath());
    }

    @Test
    void aMalformedItemsAdderIconFallsBackToPaperAndIsNamed() {
        ActivityDef def = load("ia.saucepan");

        assertNull(def.iconPath());
        assertEquals(Material.PAPER, def.icon());
        assertTrue(loggedContains("Malformed item path 'ia.saucepan' at activities.cook_dish.material"));
        assertTrue(loggedContains("expected ia.<namespace:id>"));
    }

    @Test
    void anotherPluginsPrefixIsStillUnsupportedAndSaysWhatIsSupported() {
        ActivityDef def = load("nx.saucepan");

        assertNull(def.iconPath());
        assertEquals(Material.PAPER, def.icon());
        assertTrue(loggedContains("Unsupported item path 'nx.saucepan' at activities.cook_dish.material"));
        assertTrue(loggedContains("ia.<namespace:id>"));
    }

    @Test
    void spacingAroundAnItemsAdderSegmentIsStripped() {
        assertEquals("ia.tfmc:saucepan", load("ia.tfmc: saucepan").iconPath());
        assertEquals("ia.tfmc:saucepan", load("ia. tfmc . saucepan ").iconPath());
    }

    @Test
    void whitespaceInsideAnItemsAdderSegmentIsRefusedAtLoad() {
        ActivityDef def = load("ia.tfmc:sauce pan");

        assertNull(def.iconPath());
        assertEquals(Material.PAPER, def.icon());
        assertTrue(loggedContains("Malformed item path 'ia.tfmc:sauce pan' at activities.cook_dish.material"));
    }

    @Test
    void theCaseOfAnItemsAdderIdIsNotTouched() {
        assertEquals("ia.TFMC:Saucepan", load("ia.TFMC:Saucepan").iconPath());
    }

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
