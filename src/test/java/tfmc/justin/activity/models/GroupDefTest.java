package tfmc.justin.activity.models;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// GroupDef is a plain record - nothing to validate beyond the accessors
// actually returning what the constructor was given, including the
// iconPath-is-optional case ActivityConfiguration relies on (null when the
// icon is a plain Material rather than a TLibs m.<type>.<id> path).
class GroupDefTest {

    @Test
    void accessorsReturnTheConstructorArguments() {
        GroupDef group = new GroupDef("mining", "&6Mining", Material.DIAMOND_PICKAXE, "m.tool.pickaxe");

        assertEquals("mining", group.id());
        assertEquals("&6Mining", group.display());
        assertEquals(Material.DIAMOND_PICKAXE, group.icon());
        assertEquals("m.tool.pickaxe", group.iconPath());
    }

    @Test
    void iconPathCanBeNullForAPlainMaterialIcon() {
        GroupDef group = new GroupDef("mining", "Mining", Material.DIAMOND_PICKAXE, null);

        assertNull(group.iconPath());
    }

    @Test
    void recordEqualityIsFieldwise() {
        GroupDef a = new GroupDef("mining", "Mining", Material.DIAMOND_PICKAXE, null);
        GroupDef b = new GroupDef("mining", "Mining", Material.DIAMOND_PICKAXE, null);
        GroupDef differentId = new GroupDef("farming", "Mining", Material.DIAMOND_PICKAXE, null);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(false, a.equals(differentId));
    }
}
