package tfmc.justin.activity.utils;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// How the three forms an item-valued config key accepts are told apart.
// Material.matchMaterial is a name lookup, so all of this runs headless.
// ====================================
class ItemPathTest {

    @Test
    void aBareNameIsAMaterial() {
        assertEquals(Material.IRON_INGOT, ItemPath.material("IRON_INGOT"));
        assertEquals(Material.IRON_INGOT, ItemPath.material("iron_ingot"));
        assertNull(ItemPath.pluginPath("IRON_INGOT"));
        assertFalse(ItemPath.isPluginPath("IRON_INGOT"));
    }

    @Test
    void aVanillaPathIsTheSameMaterial() {
        assertEquals(Material.IRON_INGOT, ItemPath.material("v.iron_ingot"));
        assertEquals(Material.DIAMOND_BLOCK, ItemPath.material("v.DIAMOND_BLOCK"));
        assertNull(ItemPath.pluginPath("v.iron_ingot"));
    }

    @Test
    void anItemPathIsKeptWhole() {
        assertTrue(ItemPath.isPluginPath("m.material.steel"));
        assertEquals("m.material.steel", ItemPath.pluginPath("m.material.steel"));
        // never a Material, so it can never be mistaken for a typo'd one
        assertNull(ItemPath.material("m.material.steel"));
    }

    @Test
    void aPathMissingASegmentIsRejectedAsAPathNotAsAMaterial() {
        for (String broken : new String[]{"m.material", "m.material.", "m..steel", "m.", "m.a.b.c"}) {
            assertTrue(ItemPath.isPluginPath(broken), broken);
            assertNull(ItemPath.pluginPath(broken), broken);
            assertNull(ItemPath.material(broken), broken);
        }
    }

    @Test
    void anUnknownOrMissingNameIsNull() {
        assertNull(ItemPath.material("DIMAOND_BLOCK"));
        assertNull(ItemPath.material("v.dimaond_block"));
        assertNull(ItemPath.material("v."));
        assertNull(ItemPath.material(null));
        assertNull(ItemPath.pluginPath(null));
        assertFalse(ItemPath.isPluginPath(null));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertTrue(ItemPath.isPluginPath("  m.material.steel  "));
        assertEquals("m.material.steel", ItemPath.pluginPath("  m.material.steel  "));
        assertEquals(Material.IRON_INGOT, ItemPath.material("  IRON_INGOT  "));
        assertEquals(Material.IRON_INGOT, ItemPath.material("  v.iron_ingot  "));
    }

    @Test
    void prefixesAreCaseInsensitive() {
        assertTrue(ItemPath.isPluginPath("M.MATERIAL.STEEL"));
        assertEquals("M.MATERIAL.STEEL", ItemPath.pluginPath("M.MATERIAL.STEEL"));
        assertNull(ItemPath.material("M.MATERIAL.STEEL"));

        assertEquals(Material.IRON_INGOT, ItemPath.material("V.IRON_INGOT"));
        assertFalse(ItemPath.isPluginPath("V.IRON_INGOT"));
    }

    @Test
    void anUnrecognizedPrefixIsUnsupportedNotUnknown() {
        assertTrue(ItemPath.isUnsupportedPath("ia.foo:bar"));
        assertFalse(ItemPath.isPluginPath("ia.foo:bar"));
        assertNull(ItemPath.material("ia.foo:bar"));
        assertNull(ItemPath.pluginPath("ia.foo:bar"));

        assertTrue(ItemPath.isUnsupportedPath("nx.x"));
        assertFalse(ItemPath.isPluginPath("nx.x"));
        assertNull(ItemPath.material("nx.x"));

        assertFalse(ItemPath.isUnsupportedPath("IRON_INGOT"));
        assertFalse(ItemPath.isUnsupportedPath("v.iron_ingot"));
        assertFalse(ItemPath.isUnsupportedPath("m.material.steel"));
    }
}
