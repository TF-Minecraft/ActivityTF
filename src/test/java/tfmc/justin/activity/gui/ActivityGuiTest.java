package tfmc.justin.activity.gui;

import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.GroupDef;

import java.lang.reflect.Field;
import java.util.Arrays;

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
    void theGridAvoidsTheFixedControls() throws ReflectiveOperationException {
        for (String name : new String[] {"BAR_SLOT", "REWARD_SLOT", "BACK_SLOT"}) {
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
}
