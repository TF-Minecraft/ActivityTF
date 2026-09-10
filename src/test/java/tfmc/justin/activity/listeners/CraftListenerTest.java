package tfmc.justin.activity.listeners;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// The whole credit decision, which is the only place a craft can be paid for;
// the event plumbing around it needs a server, and building an ItemStack
// headless blows up on the Bukkit registry, so everything here is primitives.
//
// smallestIngredient is the shortest occupied stack in the crafting grid, read
// at MONITOR - before vanilla's transaction runs. freeSpace is the room for
// the result across the player's 36 storage slots, read at the same moment.
// Both bound vanilla's own shift-click loop, so neither can be inflated by
// anything the player does after the event returns.
// ====================================
class CraftListenerTest {

    private static final int ROOMY = 64 * 36;

    // A normal click is one batch by construction. It is NOT bounded by the
    // grid or by free space: vanilla has already decided the click is valid,
    // and it may legitimately land on the cursor, on the floor, or in a hotbar
    // slot - none of which free space describes
    @Test
    void aNormalClickIsAlwaysExactlyOneBatch() {
        assertEquals(1, CraftListener.creditedAmount(false, 1, 64, ROOMY));
        assertEquals(4, CraftListener.creditedAmount(false, 4, 1, 0));
        assertEquals(3, CraftListener.creditedAmount(false, 3, 0, 0));
    }

    // Round 1's bug: a full inventory shift-clicking a full grid moved nothing,
    // and was paid for the whole batch run anyway
    @Test
    void aFullInventoryShiftClickCreditsNothing() {
        assertEquals(0, CraftListener.creditedAmount(true, 1, 64, 0));
        assertEquals(0, CraftListener.creditedAmount(true, 4, 64, 0));
    }

    @Test
    void aShiftClickIsBoundedByTheShortestIngredientStack() {
        assertEquals(7, CraftListener.creditedAmount(true, 1, 7, ROOMY));
        // Four planks per log: the craft count multiplies, it is not the yield
        assertEquals(28, CraftListener.creditedAmount(true, 4, 7, ROOMY));
    }

    @Test
    void aShiftClickIsBoundedByTheRoomLeft() {
        // Room for 10 items, one per craft
        assertEquals(10, CraftListener.creditedAmount(true, 1, 64, 10));
        // Room for 10 items, four per craft: two whole batches fit, the third
        // would only partly land and is given up
        assertEquals(8, CraftListener.creditedAmount(true, 4, 64, 10));
    }

    @Test
    void anEmptyGridCreditsNothing() {
        assertEquals(0, CraftListener.creditedAmount(true, 1, 0, ROOMY));
        assertEquals(0, CraftListener.creditedAmount(true, 64, 0, ROOMY));
    }

    // A result stack that reports nothing is not a craft, and dividing by it
    // would throw
    @Test
    void aZeroSizedResultCreditsNothing() {
        assertEquals(0, CraftListener.creditedAmount(true, 0, 64, ROOMY));
        assertEquals(0, CraftListener.creditedAmount(false, 0, 64, ROOMY));
        assertEquals(0, CraftListener.creditedAmount(true, -1, 64, ROOMY));
    }

    // Neither bound may go negative and start paying
    @Test
    void negativeBoundsCreditNothing() {
        assertEquals(0, CraftListener.creditedAmount(true, 1, -5, ROOMY));
        assertEquals(0, CraftListener.creditedAmount(true, 1, 64, -5));
    }

    // ====================================
    // The four ways the old two-tick grid diff was inflated. All of them acted
    // on the world *after* the event; the seam below only ever sees the two
    // pre-transaction bounds, so each is now worth exactly one real craft.
    // ====================================

    // Ingredients dragged out of the grid in the same tick used to read as
    // "consumed". Here the grid was 8 gold nuggets and a carrot at click time,
    // one golden carrot fit, and the click was a normal one: one batch
    @Test
    void ingredientsPulledOutMidTickCannotInflate() {
        assertEquals(1, CraftListener.creditedAmount(false, 1, 8, ROOMY));
    }

    // Thirty craft clicks in one tick used to credit 30*31/2 because each
    // click's own 'before' was diffed against one shared 'after'. Each click
    // is now its own event and its own single batch
    @Test
    void manyClicksInOneTickAreOneBatchEach() {
        int total = 0;
        for (int i = 0; i < 30; i++) {
            total += CraftListener.creditedAmount(false, 1, 64, ROOMY);
        }
        assertEquals(30, total);
    }

    // Closing the 2x2 personal grid hands its contents back to the player,
    // which used to read as every ingredient consumed. Nothing is read after
    // the event any more, so the close is invisible: only the one real craft
    @Test
    void closingTheGridCannotInflate() {
        assertEquals(1, CraftListener.creditedAmount(false, 1, 64, ROOMY));
    }

    // Walking over dropped result items, or an alt throwing a stack, used to
    // raise the resultGained cap and let the inflated grid diff through. Free
    // space is measured before the transaction and result items arriving only
    // ever shrink it
    @Test
    void pickingUpResultItemsCannotInflate() {
        assertEquals(1, CraftListener.creditedAmount(false, 1, 8, ROOMY));
        assertEquals(3, CraftListener.creditedAmount(true, 1, 8, 3));
    }

    // ====================================
    // The click-type gate. CraftItemEvent fires for *any* click on the result
    // slot while the grid matches a recipe, and CraftBukkit's InventoryAction
    // is a guess, not a verdict - it reports COLLECT_TO_CURSOR for a
    // double-click and HOTBAR_MOVE_AND_READD for a blocked swap, both of which
    // craft nothing. So the gate is a whitelist.
    //
    // ClickType and InventoryAction are plain enums with no registry behind
    // them, which is why they can be used here at all.
    // ====================================

    private static final boolean OCCUPIED = true;
    private static final boolean EMPTY = false;

    @Test
    void everyAcceptedClickTakesFromTheResultSlot() {
        for (ClickType click : new ClickType[]{
            ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT,
            ClickType.DROP, ClickType.CONTROL_DROP}) {
            assertTrue(CraftListener.takesFromResult(click, InventoryAction.PICKUP_ALL, EMPTY), click.name());
            // These six have no destination slot, so occupancy is irrelevant
            assertTrue(CraftListener.takesFromResult(click, InventoryAction.PICKUP_ALL, OCCUPIED), click.name());
        }
        assertTrue(CraftListener.takesFromResult(ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, EMPTY));
        assertTrue(CraftListener.takesFromResult(ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP, EMPTY));
    }

    // CRITICAL: vanilla's PICKUP_ALL branch only fires when the clicked slot is
    // empty or refuses pickup, and a result slot is neither, so a double-click
    // crafts zero items and consumes zero ingredients. CraftBukkit reports
    // COLLECT_TO_CURSOR, never NOTHING, so the old blacklist paid a full batch
    // for it - unbounded, since the grid is never emptied
    @Test
    void aDoubleClickOnTheResultSlotTakesNothing() {
        assertFalse(CraftListener.takesFromResult(ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR, EMPTY));
        assertFalse(CraftListener.takesFromResult(ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR, OCCUPIED));
        assertFalse(CraftListener.takesFromResult(ClickType.DOUBLE_CLICK, InventoryAction.PICKUP_ALL, EMPTY));
    }

    // CRITICAL: ResultSlot#mayPlace is always false, so vanilla's swap branch
    // needs the destination empty. CraftBukkit reports HOTBAR_MOVE_AND_READD
    // either way, so a held number key over a loaded grid used to pay forever
    @Test
    // HOTBAR_MOVE_AND_READD is deprecated but is exactly what CraftBukkit
    // still reports for this click, which is the whole point
    @SuppressWarnings("deprecation")
    void aNumberKeySwapOntoAnOccupiedSlotTakesNothing() {
        assertFalse(CraftListener.takesFromResult(ClickType.NUMBER_KEY, InventoryAction.HOTBAR_MOVE_AND_READD, OCCUPIED));
        assertFalse(CraftListener.takesFromResult(ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, OCCUPIED));
        assertTrue(CraftListener.takesFromResult(ClickType.NUMBER_KEY, InventoryAction.HOTBAR_MOVE_AND_READD, EMPTY));
    }

    @Test
    // HOTBAR_MOVE_AND_READD is deprecated but is exactly what CraftBukkit
    // still reports for this click, which is the whole point
    @SuppressWarnings("deprecation")
    void anOffhandSwapOntoAnOccupiedOffhandTakesNothing() {
        assertFalse(CraftListener.takesFromResult(ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_MOVE_AND_READD, OCCUPIED));
        assertFalse(CraftListener.takesFromResult(ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP, OCCUPIED));
        assertTrue(CraftListener.takesFromResult(ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP, EMPTY));
    }

    // A drop-click with something already on the cursor is the one no-op
    // vanilla and CraftBukkit agree on
    @Test
    void nothingActionTakesNothingWhateverTheClick() {
        assertFalse(CraftListener.takesFromResult(ClickType.DROP, InventoryAction.NOTHING, EMPTY));
        assertFalse(CraftListener.takesFromResult(ClickType.LEFT, InventoryAction.NOTHING, EMPTY));
        assertFalse(CraftListener.takesFromResult(ClickType.SHIFT_LEFT, InventoryAction.NOTHING, EMPTY));
    }

    // Anything not on the whitelist fails closed - including click types that
    // do not exist yet
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
        // Named explicitly so the sweep above cannot pass by being empty
        assertFalse(CraftListener.takesFromResult(ClickType.MIDDLE, InventoryAction.CLONE_STACK, EMPTY));
        assertFalse(CraftListener.takesFromResult(ClickType.CREATIVE, InventoryAction.PLACE_ALL, EMPTY));
        assertFalse(CraftListener.takesFromResult(ClickType.WINDOW_BORDER_LEFT, InventoryAction.DROP_ALL_CURSOR, EMPTY));
        assertFalse(CraftListener.takesFromResult(ClickType.UNKNOWN, InventoryAction.UNKNOWN, EMPTY));
    }

    // ====================================
    // Is there anything in the result slot at all?
    //
    // CraftItemEvent is gated on CraftingInventory#getRecipe(), which reads
    // ResultContainer's recipeUsed - a field set on every match and cleared
    // only by awardUsedRecipes, so it SURVIVES the grid ceasing to match, and
    // in the player's own 2x2 menu survives for the whole session. So the
    // event still fires on a grid that crafts nothing, with an EMPTY result
    // slot. Neither guard above catches it: CraftBukkit calls a left-click
    // with a full cursor PLACE_ALL (its empty-clicked-item branch has no
    // mayPlace check) and a shift-click MOVE_TO_OTHER_INVENTORY, both on the
    // whitelist and neither ever NOTHING.
    //
    // The old getRecipe().getResult() fallback paid for exactly that click:
    // 9 diamonds in, one pulled back out, then click the empty result slot
    // forever. The recipe and the grid are not inputs here at all, which is
    // the point - an empty result slot is worth nothing whatever they hold.
    // ====================================

    private static final boolean PRESENT = true;
    private static final boolean ABSENT = false;
    private static final int RESULT_SLOT = 0;

    @Test
    @SuppressWarnings("deprecation") // the sweep covers deprecated InventoryActions on purpose
    void anEmptyResultSlotCreditsNothingWhateverTheClick() {
        for (ClickType click : ClickType.values()) {
            for (InventoryAction action : InventoryAction.values()) {
                String where = click.name() + "/" + action.name();
                assertFalse(CraftListener.creditable(RESULT_SLOT, ABSENT, click, action, EMPTY), where);
                assertFalse(CraftListener.creditable(RESULT_SLOT, ABSENT, click, action, OCCUPIED), where);
            }
        }
    }

    // The two clicks the stale-recipe exploit actually used, named so the
    // sweep above cannot pass by being empty - and the same clicks with a real
    // result present, so the guard is not just refusing everything
    @Test
    void theStaleRecipeExploitClicksCreditNothing() {
        assertFalse(CraftListener.creditable(RESULT_SLOT, ABSENT, ClickType.LEFT, InventoryAction.PLACE_ALL, EMPTY));
        assertFalse(CraftListener.creditable(RESULT_SLOT, ABSENT, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, EMPTY));

        assertTrue(CraftListener.creditable(RESULT_SLOT, PRESENT, ClickType.LEFT, InventoryAction.PICKUP_ALL, EMPTY));
        assertTrue(CraftListener.creditable(RESULT_SLOT, PRESENT, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, EMPTY));
    }

    // CraftBukkit only builds a CraftItemEvent for raw slot 0 today, but that
    // is an invariant of its code, not a promise of the API. Anywhere else the
    // clicked stack is an ingredient, and shift-pulling 64 sticks out of a
    // grid would credit 64 sticks crafted
    @Test
    void aClickOutsideTheResultSlotCreditsNothing() {
        for (int rawSlot : new int[]{-1, 1, 2, 4, 9, 36, 45}) {
            assertFalse(
                CraftListener.creditable(rawSlot, PRESENT, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, EMPTY),
                "raw slot " + rawSlot);
        }
        assertTrue(CraftListener.creditable(RESULT_SLOT, PRESENT, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, EMPTY));
    }

    // The whitelist still has the last word once a result is present
    @Test
    void thePresentResultStillHasToBeTakenByTheClick() {
        assertFalse(CraftListener.creditable(RESULT_SLOT, PRESENT, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR, EMPTY));
        assertFalse(CraftListener.creditable(RESULT_SLOT, PRESENT, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, OCCUPIED));
        assertFalse(CraftListener.creditable(RESULT_SLOT, PRESENT, ClickType.LEFT, InventoryAction.NOTHING, EMPTY));
    }
}
