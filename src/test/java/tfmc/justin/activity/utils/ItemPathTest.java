package tfmc.justin.activity.utils;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// How the four forms an item-valued config key accepts are told apart.
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
        assertTrue(ItemPath.isUnsupportedPath("nx.foo:bar"));
        assertFalse(ItemPath.isPluginPath("nx.foo:bar"));
        assertNull(ItemPath.material("nx.foo:bar"));
        assertNull(ItemPath.pluginPath("nx.foo:bar"));

        assertTrue(ItemPath.isUnsupportedPath("nx.x"));
        assertFalse(ItemPath.isPluginPath("nx.x"));
        assertNull(ItemPath.material("nx.x"));

        assertFalse(ItemPath.isUnsupportedPath("IRON_INGOT"));
        assertFalse(ItemPath.isUnsupportedPath("v.iron_ingot"));
        assertFalse(ItemPath.isUnsupportedPath("m.material.steel"));
        assertFalse(ItemPath.isUnsupportedPath("ia.tfmc:saucepan"));
    }

    // ====================================
    // ia.<namespace:id> - the id /iagive takes. It is a path, never a
    // material, and never "unsupported": the whole point of the form is that
    // the admin gets an ItemsAdder lookup rather than a warning.
    // ====================================

    @Test
    void anItemsAdderPathIsAPathNotAMaterialAndNotUnsupported() {
        assertTrue(ItemPath.isItemsAdderPath("ia.tfmc:saucepan"));
        assertFalse(ItemPath.isUnsupportedPath("ia.tfmc:saucepan"));
        assertFalse(ItemPath.isPluginPath("ia.tfmc:saucepan"));
        assertNull(ItemPath.material("ia.tfmc:saucepan"));
        assertNull(ItemPath.pluginPath("ia.tfmc:saucepan"));
    }

    // The colon form goes to ItemsAdder untouched; the dotted form an admin
    // writes by analogy with m.<type>.<id> means the same item
    @Test
    void bothSeparatorsNormalizeToTheColonForm() {
        assertEquals("tfmc:saucepan", ItemPath.itemsAdderId("ia.tfmc:saucepan"));
        assertEquals("tfmc:saucepan", ItemPath.itemsAdderId("ia.tfmc.saucepan"));
        assertEquals("tfmc:saucepan", ItemPath.itemsAdderId("  IA.tfmc.saucepan  "));
        assertEquals("tfmc:saucepan", ItemPath.itemsAdderId("  Ia.tfmc:saucepan  "));
    }

    // Every one of these is still an ia. path - so it never falls through to
    // matchMaterial - but there is no id to hand over, and asking must not
    // throw
    @Test
    void aMalformedItemsAdderPathIsRejectedAsAPathNotAsAMaterial() {
        for (String broken : new String[]{"ia.", "ia.:", "ia.ns:", "ia.:id", "ia.a:b:c",
                                          "ia.onepart", "ia.a.b.c", "ia..b", "ia.a."}) {
            assertTrue(ItemPath.isItemsAdderPath(broken), broken);
            assertNull(ItemPath.itemsAdderId(broken), broken);
            assertNull(ItemPath.material(broken), broken);
            assertFalse(ItemPath.isUnsupportedPath(broken), broken);
        }
    }

    // 'ia' alone has no prefix at all: it is just an unknown material name,
    // and asking for an id must answer null rather than index out of bounds
    @Test
    void theBarePrefixIsNotAnItemsAdderPath() {
        assertFalse(ItemPath.isItemsAdderPath("ia"));
        assertNull(ItemPath.itemsAdderId("ia"));
        assertNull(ItemPath.itemsAdderId(null));
        assertFalse(ItemPath.isItemsAdderPath(null));
        assertNull(ItemPath.itemsAdderId("m.material.steel"));
        assertNull(ItemPath.itemsAdderId("IRON_INGOT"));
    }
}
