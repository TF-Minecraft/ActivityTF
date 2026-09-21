package tfmc.justin.activity.listeners;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftListenerTest {

    private static final int ROOMY = 64 * 36;

    @Test
    void aNormalClickIsAlwaysExactlyOneBatch() {
        assertEquals(1, CraftListener.creditedAmount(false, 1, 64, ROOMY));
        assertEquals(4, CraftListener.creditedAmount(false, 4, 1, 0));
        assertEquals(3, CraftListener.creditedAmount(false, 3, 0, 0));
    }

    @Test
    void aFullInventoryShiftClickCreditsNothing() {
        assertEquals(0, CraftListener.creditedAmount(true, 1, 64, 0));
        assertEquals(0, CraftListener.creditedAmount(true, 4, 64, 0));
    }

    @Test
    void aShiftClickIsBoundedByTheShortestIngredientStack() {
        assertEquals(7, CraftListener.creditedAmount(true, 1, 7, ROOMY));
        assertEquals(28, CraftListener.creditedAmount(true, 4, 7, ROOMY));
    }

    @Test
    void aShiftClickIsBoundedByTheRoomLeft() {
        assertEquals(10, CraftListener.creditedAmount(true, 1, 64, 10));
        assertEquals(8, CraftListener.creditedAmount(true, 4, 64, 10));
    }

    @Test
    void anEmptyGridCreditsNothing() {
        assertEquals(0, CraftListener.creditedAmount(true, 1, 0, ROOMY));
        assertEquals(0, CraftListener.creditedAmount(true, 64, 0, ROOMY));
    }

    @Test
    void aZeroSizedResultCreditsNothing() {
        assertEquals(0, CraftListener.creditedAmount(true, 0, 64, ROOMY));
        assertEquals(0, CraftListener.creditedAmount(false, 0, 64, ROOMY));
        assertEquals(0, CraftListener.creditedAmount(true, -1, 64, ROOMY));
    }

    @Test
    void negativeBoundsCreditNothing() {
        assertEquals(0, CraftListener.creditedAmount(true, 1, -5, ROOMY));
        assertEquals(0, CraftListener.creditedAmount(true, 1, 64, -5));
    }

    @Test
    void ingredientsPulledOutMidTickCannotInflate() {
        assertEquals(1, CraftListener.creditedAmount(false, 1, 8, ROOMY));
    }

    @Test
    void manyClicksInOneTickAreOneBatchEach() {
        int total = 0;
        for (int i = 0; i < 30; i++) {
            total += CraftListener.creditedAmount(false, 1, 64, ROOMY);
        }
        assertEquals(30, total);
    }

    @Test
    void closingTheGridCannotInflate() {
        assertEquals(1, CraftListener.creditedAmount(false, 1, 64, ROOMY));
    }

    @Test
    void pickingUpResultItemsCannotInflate() {
        assertEquals(1, CraftListener.creditedAmount(false, 1, 8, ROOMY));
        assertEquals(3, CraftListener.creditedAmount(true, 1, 8, 3));
    }

    private static final boolean OCCUPIED = true;
    private static final boolean EMPTY = false;

    @Test
    void everyAcceptedClickTakesFromTheResultSlot() {
        for (ClickType click : new ClickType[]{
            ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT,
            ClickType.DROP, ClickType.CONTROL_DROP}) {
            assertTrue(CraftListener.takesFromResult(click, InventoryAction.PICKUP_ALL, EMPTY), click.name());
            assertTrue(CraftListener.takesFromResult(click, InventoryAction.PICKUP_ALL, OCCUPIED), click.name());
        }
        assertTrue(CraftListener.takesFromResult(ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, EMPTY));
        assertTrue(CraftListener.takesFromResult(ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP, EMPTY));
    }

    @Test
    void aDoubleClickOnTheResultSlotTakesNothing() {
        assertFalse(CraftListener.takesFromResult(ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR, EMPTY));
        assertFalse(CraftListener.takesFromResult(ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR, OCCUPIED));
        assertFalse(CraftListener.takesFromResult(ClickType.DOUBLE_CLICK, InventoryAction.PICKUP_ALL, EMPTY));
    }

    @Test
    @SuppressWarnings("deprecation")
    void aNumberKeySwapOntoAnOccupiedSlotTakesNothing() {
        assertFalse(CraftListener.takesFromResult(ClickType.NUMBER_KEY, InventoryAction.HOTBAR_MOVE_AND_READD, OCCUPIED));
        assertFalse(CraftListener.takesFromResult(ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, OCCUPIED));
        assertTrue(CraftListener.takesFromResult(ClickType.NUMBER_KEY, InventoryAction.HOTBAR_MOVE_AND_READD, EMPTY));
    }

    @Test
    @SuppressWarnings("deprecation")
    void anOffhandSwapOntoAnOccupiedOffhandTakesNothing() {
        assertFalse(CraftListener.takesFromResult(ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_MOVE_AND_READD, OCCUPIED));
        assertFalse(CraftListener.takesFromResult(ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP, OCCUPIED));
        assertTrue(CraftListener.takesFromResult(ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP, EMPTY));
    }

    @Test
    void nothingActionTakesNothingWhateverTheClick() {
        assertFalse(CraftListener.takesFromResult(ClickType.DROP, InventoryAction.NOTHING, EMPTY));
        assertFalse(CraftListener.takesFromResult(ClickType.LEFT, InventoryAction.NOTHING, EMPTY));
        assertFalse(CraftListener.takesFromResult(ClickType.SHIFT_LEFT, InventoryAction.NOTHING, EMPTY));
    }

    @Test
    void unlistedClickTypesTakeNothing() {
        for (ClickType click : ClickType.values()) {
            switch (click) {
                case LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT, DROP, CONTROL_DROP,
                     NUMBER_KEY, SWAP_OFFHAND -> {
                }
                default -> {
                    assertFalse(CraftListener.takesFromResult(click, InventoryAction.PICKUP_ALL, EMPTY), click.name());
                    assertFalse(CraftListener.takesFromResult(click, InventoryAction.PICKUP_ALL, OCCUPIED), click.name());
                }
            }
        }
        assertFalse(CraftListener.takesFromResult(ClickType.MIDDLE, InventoryAction.CLONE_STACK, EMPTY));
        assertFalse(CraftListener.takesFromResult(ClickType.CREATIVE, InventoryAction.PLACE_ALL, EMPTY));
        assertFalse(CraftListener.takesFromResult(ClickType.WINDOW_BORDER_LEFT, InventoryAction.DROP_ALL_CURSOR, EMPTY));
        assertFalse(CraftListener.takesFromResult(ClickType.UNKNOWN, InventoryAction.UNKNOWN, EMPTY));
    }

    private static final boolean PRESENT = true;
    private static final boolean ABSENT = false;
    private static final int RESULT_SLOT = 0;

    @Test
    @SuppressWarnings("deprecation")
    void anEmptyResultSlotCreditsNothingWhateverTheClick() {
        for (ClickType click : ClickType.values()) {
            for (InventoryAction action : InventoryAction.values()) {
                String where = click.name() + "/" + action.name();
                assertFalse(CraftListener.creditable(RESULT_SLOT, ABSENT, click, action, EMPTY), where);
                assertFalse(CraftListener.creditable(RESULT_SLOT, ABSENT, click, action, OCCUPIED), where);
            }
        }
    }

    @Test
    void theStaleRecipeExploitClicksCreditNothing() {
        assertFalse(CraftListener.creditable(RESULT_SLOT, ABSENT, ClickType.LEFT, InventoryAction.PLACE_ALL, EMPTY));
        assertFalse(CraftListener.creditable(RESULT_SLOT, ABSENT, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, EMPTY));

        assertTrue(CraftListener.creditable(RESULT_SLOT, PRESENT, ClickType.LEFT, InventoryAction.PICKUP_ALL, EMPTY));
        assertTrue(CraftListener.creditable(RESULT_SLOT, PRESENT, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, EMPTY));
    }

    @Test
    void aClickOutsideTheResultSlotCreditsNothing() {
        for (int rawSlot : new int[]{-1, 1, 2, 4, 9, 36, 45}) {
            assertFalse(
                CraftListener.creditable(rawSlot, PRESENT, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, EMPTY),
                "raw slot " + rawSlot);
        }
        assertTrue(CraftListener.creditable(RESULT_SLOT, PRESENT, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, EMPTY));
    }

    @Test
    void thePresentResultStillHasToBeTakenByTheClick() {
        assertFalse(CraftListener.creditable(RESULT_SLOT, PRESENT, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR, EMPTY));
        assertFalse(CraftListener.creditable(RESULT_SLOT, PRESENT, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, OCCUPIED));
        assertFalse(CraftListener.creditable(RESULT_SLOT, PRESENT, ClickType.LEFT, InventoryAction.NOTHING, EMPTY));
    }
}
