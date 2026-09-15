package tfmc.justin.activity.gui;

import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.GroupDef;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// ActivityGui.build() needs a live Bukkit server (Bukkit.createInventory) and
// is not reachable here. What is checked instead is the slot geometry every
// build() call relies on: GROUP_SLOTS, SIZE etc are private, read through
// reflection rather than widened just for this test.
// ====================================
class ActivityGuiTest {

    private static int[] groupSlots() throws ReflectiveOperationException {
        Field field = ActivityGui.class.getDeclaredField("GROUP_SLOTS");
        field.setAccessible(true);
        return (int[]) field.get(null);
    }

    private static int size() throws ReflectiveOperationException {
        Field field = ActivityGui.class.getDeclaredField("SIZE");
        field.setAccessible(true);
        return field.getInt(null);
    }

    @Test
    void thereAreExactlyGroupRowsAnchors() throws ReflectiveOperationException {
        assertEquals(GroupDef.MAX_GROUPS, groupSlots().length);
    }

    @Test
    void everyAnchorIsColumnOneOfItsRow() throws ReflectiveOperationException {
        // Column 1 (0-indexed) of a 9-wide Marketblock row: the label sits one
        // slot in, not at the row's leftmost edge.
        for (int anchor : groupSlots()) {
            assertEquals(0, anchor % 9, "anchor " + anchor + " is not the first column of its row");
        }
    }

    @Test
    void anchorsAreRowsTwoThroughFive() throws ReflectiveOperationException {
        assertArrayEquals(new int[] {9, 18, 27, 36}, groupSlots());
    }

    @Test
    void anchorPlusGroupWidthStaysWithinTheSameRow() throws ReflectiveOperationException {
        int size = size();
        for (int anchor : groupSlots()) {
            int last = anchor + GroupDef.MAX_ACTIVITIES;
            assertTrue(last < size, "anchor " + anchor + " + MAX_ACTIVITIES overruns the inventory (" + last + ")");
            assertEquals(anchor / 9, last / 9,
                "anchor " + anchor + " + MAX_ACTIVITIES (" + last + ") spills into the next row");
        }
    }
}
