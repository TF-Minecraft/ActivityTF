package tfmc.justin.activity.models;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// The 6-arg constructor is the one every pre-existing test (PlayerDataTest,
// BarTest fixtures) already builds ActivityDef with, from before iconPath and
// group existed. It must still compile and now also default both of the new
// trailing fields to null, since callers that never set a group would
// otherwise get whatever Java defaults a record component to (which for an
// object type is null anyway, but this pins the behaviour rather than
// leaving it to compiler default).
class ActivityDefTest {

    @Test
    void theSixArgConstructorDefaultsIconPathAndGroupToNull() {
        ActivityDef def = new ActivityDef("vote", "Vote", Material.PAPER, 1, 1, 5);

        assertNull(def.iconPath());
        assertNull(def.group());
    }

    @Test
    void theEightArgConstructorRoundTripsIconPathAndGroup() {
        ActivityDef def = new ActivityDef("vote", "Vote", Material.PAPER, "m.item.ballot",
            1, 1, 5, "voting");

        assertEquals("m.item.ballot", def.iconPath());
        assertEquals("voting", def.group());
    }

    @Test
    void theSixArgConstructorStillFillsInTheOriginalFields() {
        ActivityDef def = new ActivityDef("vote", "Vote", Material.PAPER, 3, 2, 5);

        assertEquals("vote", def.id());
        assertEquals("Vote", def.display());
        assertEquals(Material.PAPER, def.icon());
        assertEquals(3, def.every());
        assertEquals(2, def.points());
        assertEquals(5, def.dailyCap());
    }
}
