package tfmc.justin.activity.models;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ActivityDefTest {

    @Test
    void iconPathCanBeNull() {
        ActivityDef def = new ActivityDef("vote", "Vote", Material.PAPER, null, 3, 2, 5);

        assertNull(def.iconPath());
        assertEquals("vote", def.id());
        assertEquals("Vote", def.display());
        assertEquals(Material.PAPER, def.icon());
        assertEquals(3, def.every());
        assertEquals(2, def.points());
        assertEquals(5, def.dailyCap());
    }

    @Test
    void theFullConstructorRoundTripsIconPath() {
        ActivityDef def = new ActivityDef("vote", "Vote", Material.PAPER, "m.item.ballot",
            1, 1, 5);

        assertEquals("m.item.ballot", def.iconPath());
    }
}
