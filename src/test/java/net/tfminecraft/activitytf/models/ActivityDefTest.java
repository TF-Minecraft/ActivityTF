package net.tfminecraft.activitytf.models;

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

    @Test
    void dailyCapCountsAwardsNotPoints() {
        ActivityDef def = new ActivityDef("injured", "Injured", Material.BONE, null, 1, 5, 1);

        assertEquals(5, def.worth(1));
        assertEquals(5, def.worth(3));
        assertEquals(5, def.capPoints());
    }

    @Test
    void dailyCapWithEveryAboveOne() {
        ActivityDef def = new ActivityDef("x", "X", Material.PAPER, null, 2, 3, 2);

        assertEquals(0, def.worth(1));
        assertEquals(3, def.worth(2));
        assertEquals(3, def.worth(3));
        assertEquals(6, def.worth(4));
        assertEquals(6, def.worth(5));
    }

    @Test
    void zeroDailyCapIsUnlimited() {
        ActivityDef def = new ActivityDef("x", "X", Material.PAPER, null, 1, 5, 0);

        assertEquals(500, def.worth(100));
    }

    @Test
    void capPointsAndWorthSaturateInsteadOfOverflowing() {
        ActivityDef def = new ActivityDef("x", "X", Material.PAPER, null, 1, 1_000_000, 1_000_000);

        assertEquals(Integer.MAX_VALUE, def.capPoints());
        assertEquals(Integer.MAX_VALUE, def.worth(Integer.MAX_VALUE));
        assertEquals(1_000_000, def.worth(1));
    }

    @Test
    void completionsCountPayoutsNotPoints() {
        ActivityDef def = new ActivityDef("injured", "Injured", Material.BONE, null, 1, 5, 1);

        assertEquals(0, def.completions(0));
        assertEquals(1, def.completions(1));
        assertEquals(1, def.completions(2));
        assertEquals(1, def.completions(5));
    }

    @Test
    void completionsWithEveryAboveOneAndDailyCap() {
        ActivityDef def = new ActivityDef("x", "X", Material.PAPER, null, 3, 1, 2);

        assertEquals(0, def.completions(0));
        assertEquals(0, def.completions(2));
        assertEquals(1, def.completions(3));
        assertEquals(2, def.completions(6));
        assertEquals(2, def.completions(9));
    }

    @Test
    void uncappedCompletionsAreNotClamped() {
        ActivityDef def = new ActivityDef("x", "X", Material.PAPER, null, 1, 2, 0);

        assertEquals(4, def.completions(4));
    }
}
