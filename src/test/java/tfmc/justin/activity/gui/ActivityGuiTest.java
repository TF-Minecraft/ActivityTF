package tfmc.justin.activity.gui;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.GroupDef;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// ActivityGui.build() needs a live Bukkit server (Bukkit.createInventory) and
// is not reachable here. What is checked instead is the slot geometry every
// build() call relies on: GRID, SIZE etc are private, read through reflection
// rather than widened just for this test.
// ====================================
class ActivityGuiTest {

    private static int[] grid() throws ReflectiveOperationException {
        Field field = ActivityGui.class.getDeclaredField("GRID");
        field.setAccessible(true);
        return (int[]) field.get(null);
    }

    private static int slot(String name) throws ReflectiveOperationException {
        Field field = ActivityGui.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    @Test
    void theGridHoldsExactlyTheConfiguredMaximums() throws ReflectiveOperationException {
        assertEquals(GroupDef.MAX_GROUPS, grid().length);
        assertEquals(GroupDef.MAX_ACTIVITIES, grid().length);
    }

    @Test
    void everyGridSlotIsInsideRowsTwoThroughFive() throws ReflectiveOperationException {
        int size = slot("SIZE");
        for (int slot : grid()) {
            assertTrue(slot >= 0 && slot < size, "slot " + slot + " is outside the inventory (" + size + ")");
            int row = slot / 9;
            int column = slot % 9;
            assertTrue(row >= 1 && row <= 4, "slot " + slot + " is not in rows 2-5");
            assertTrue(column >= 1 && column <= 7, "slot " + slot + " is not in columns 2-8");
        }
    }

    @Test
    void theGridHasNoDuplicates() throws ReflectiveOperationException {
        assertEquals(grid().length, Arrays.stream(grid()).distinct().count(),
            "GRID lists the same slot twice");
    }

    @Test
    void dailyBarAndWeeklyBarSlotsAreFixed() throws ReflectiveOperationException {
        assertEquals(3, slot("DAILY_BAR_SLOT"));
        assertEquals(5, slot("BAR_SLOT"));
    }

    @Test
    void theGridAvoidsTheFixedControls() throws ReflectiveOperationException {
        for (String name : new String[] {"DAILY_BAR_SLOT", "BAR_SLOT", "BACK_SLOT"}) {
            int control = slot(name);
            assertTrue(Arrays.stream(grid()).noneMatch(value -> value == control),
                name + " (" + control + ") is also a grid slot");
        }
    }

    // GRID is walked slot-by-slot with an incrementing index in build() and
    // buildGroup() - if it were ever out of order, activities/groups would
    // render in a visually scrambled order even though nothing else breaks.
    @Test
    void theGridIsStrictlyIncreasing() throws ReflectiveOperationException {
        int[] grid = grid();
        for (int i = 1; i < grid.length; i++) {
            assertTrue(grid[i] > grid[i - 1],
                "GRID is not strictly increasing at index " + i + ": " + grid[i - 1] + " -> " + grid[i]);
        }
    }

    // ====================================
    // 30 config entries across two groups, interleaved rather than grouped
    // together: group A's two entries sit at the front, and group B's 30
    // entries (more than the grid's 28 slots) are threaded in after each of
    // them plus the rest of the list. pageOf("b") must return only B's
    // entries, in config order, capped at the grid's length - none of A's.
    // ====================================
    @Test
    void pageOfReturnsAGroupsActivitiesInOrderCappedAtTheGrid() throws ReflectiveOperationException {
        List<ActivityDef> all = new ArrayList<>();
        List<ActivityDef> expectedB = new ArrayList<>();

        ActivityDef a1 = activity("a1", "a");
        ActivityDef a2 = activity("a2", "a");
        ActivityDef b0 = activity("b0", "b");
        expectedB.add(b0);
        ActivityDef b1 = activity("b1", "b");
        expectedB.add(b1);

        // Interleave: a1, b0, a2, b1, then the rest of B straight through
        all.add(a1);
        all.add(b0);
        all.add(a2);
        all.add(b1);
        for (int i = 2; i < 30; i++) {
            ActivityDef def = activity("b" + i, "b");
            all.add(def);
            expectedB.add(def);
        }

        List<ActivityDef> page = ActivityGui.pageOf(all, "b");

        assertEquals(grid().length, page.size());
        assertEquals(expectedB.subList(0, grid().length), page);
    }

    private static ActivityDef activity(String id, String group) {
        return new ActivityDef(id, id, Material.PAPER, null, 1, 1, 0, group);
    }
}
