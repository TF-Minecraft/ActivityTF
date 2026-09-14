package tfmc.justin.activity.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;
import tfmc.justin.activity.managers.ActivityManager;

// ====================================
// Feeds the activities that carry a 'craft:' key. Vanilla only, so unlike
// the other listeners this one is always registered.
//
// CraftItemEvent fires *before* the container transaction runs, so nothing has
// been crafted yet. Rather than look at the world afterwards to find out what
// happened - every version of that is a race an autoclicker wins - the credit
// is computed here and now from the same two things that bound vanilla's own
// transaction:
//
//   - a normal click takes exactly one batch, always;
//   - a shift-click repeats `quickMoveStack` while the result slot keeps
//     refilling and the destination keeps accepting, so it is bounded by the
//     shortest ingredient stack and by the room in the player's 36 storage
//     slots (AbstractContainerMenu#doClick, QUICK_MOVE).
//
// Nothing is read after the event returns, so pulling ingredients back out of
// the grid, closing the window, disconnecting, or picking up items of the same
// type mid-tick cannot inflate anything.
//
// Points here buy console-command rewards, so every judgement call errs low.
// ====================================
public class CraftListener implements Listener {

    private final ActivityManager manager;

    public CraftListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // The stack sitting in the result slot, and only that - never
        // getRecipe().getResult(). For a shift-click this is one batch, not
        // the whole run.
        ItemStack result = event.getCurrentItem();

        if (!creditable(
            event.getRawSlot(),
            !isEmpty(result),
            event.getClick(),
            event.getAction(),
            destinationOccupied(player, event))) {
            return;
        }

        String activityId = manager.getConfiguration().craftActivity(result);
        if (activityId == null) {
            return;
        }

        int credit = creditedAmount(
            event.isShiftClick(),
            result.getAmount(),
            smallestIngredient(event.getInventory().getMatrix()),
            freeSpaceFor(player, result));
        if (credit > 0) {
            manager.recordAction(player.getUniqueId(), activityId, credit);
        }
    }

    // ====================================
    // Is this click worth anything at all? Three necessary conditions for a
    // craft, all read before vanilla's transaction runs:
    //
    //   - it is a click on the result slot. CraftBukkit only builds a
    //     CraftItemEvent for raw slot 0, but nothing in the API promises that,
    //     and if it ever slipped getCurrentItem() would be an ingredient stack
    //     and a shift-click emptying the grid would credit that material.
    //   - the result slot is not empty. CraftItemEvent gates on
    //     CraftingInventory#getRecipe(), which reads ResultContainer's
    //     recipeUsed - a field that survives the grid ceasing to match, and in
    //     the player's own 2x2 menu survives for the whole session. So a grid
    //     that no longer matches still fires this event with an EMPTY result
    //     slot, and neither the click whitelist nor InventoryAction rules it
    //     out (CraftBukkit reports PLACE_ALL for a left-click with a full
    //     cursor, MOVE_TO_OTHER_INVENTORY for a shift-click). Vanilla crafts
    //     nothing in that state.
    //   - the click is one that takes the stack out of the slot.
    // ====================================
    static boolean creditable(int rawSlot, boolean resultPresent, ClickType click,
                              InventoryAction action, boolean destinationOccupied) {
        return rawSlot == 0
            && resultPresent
            && takesFromResult(click, action, destinationOccupied);
    }

    // ====================================
    // Does this click actually take the result stack out of the slot?
    //
    // CraftBukkit fires CraftItemEvent for *any* click on slot 0 while the
    // grid matches a recipe, and its InventoryAction guess is not a verdict on
    // whether vanilla will craft anything - it never reports NOTHING for the
    // two clicks below, both of which craft zero items:
    //
    //   - a double-click (ClickType.DOUBLE_CLICK, reported as
    //     COLLECT_TO_CURSOR). Vanilla's PICKUP_ALL branch only runs when the
    //     clicked slot is empty or refuses pickup, and a ResultSlot is neither;
    //     canTakeItemForPickAll excludes the result slots from the sweep too.
    //   - a number-key or offhand swap onto an *occupied* destination.
    //     ResultSlot#mayPlace is always false, so vanilla's SWAP branch needs
    //     the hotbar/offhand slot to be empty; CraftBukkit still reports
    //     HOTBAR_MOVE_AND_READD.
    //
    // So this is a whitelist, not a blacklist: an unknown or future click type
    // pays nothing rather than paying for a craft that never happened.
    // ====================================
    static boolean takesFromResult(ClickType click, InventoryAction action, boolean destinationOccupied) {
        // Vanilla's own verdict that this click is a no-op: e.g. a drop-click
        // with a full cursor
        if (action == InventoryAction.NOTHING) {
            return false;
        }
        return switch (click) {
            case LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT, DROP, CONTROL_DROP -> true;
            case NUMBER_KEY, SWAP_OFFHAND -> !destinationOccupied;
            default -> false;
        };
    }

    // The live destination of a swap, read at MONITOR - before vanilla's
    // transaction runs. Only the two swap clicks have one.
    private static boolean destinationOccupied(Player player, CraftItemEvent event) {
        return switch (event.getClick()) {
            // getHotbarButton() is -1 when the click was not a number-key one,
            // and an out-of-range index would throw; read that as occupied,
            // which pays nothing.
            case NUMBER_KEY -> {
                int button = event.getHotbarButton();
                yield button < 0 || button > 8 || !isEmpty(player.getInventory().getItem(button));
            }
            case SWAP_OFFHAND -> !isEmpty(player.getInventory().getItemInOffHand());
            default -> false;
        };
    }

    // ====================================
    // The whole credit decision. Batches, times the batch size.
    //
    // A normal click is one batch and needs no bounding: vanilla has already
    // decided the transaction is possible (InventoryAction.NOTHING is the
    // case where it is not), and it never runs twice for one click.
    //
    // A shift-click repeats until either the ingredients or the room run out,
    // so the smaller of the two is the ceiling. Vanilla will happily run one
    // extra pass that only partially lands, which this rounds away - erring
    // low is the acceptable direction.
    // ====================================
    static int creditedAmount(boolean shiftClick, int resultAmount, int smallestIngredient, int freeSpace) {
        if (resultAmount <= 0) {
            return 0;
        }
        if (!shiftClick) {
            return resultAmount;
        }
        int batches = Math.min(Math.max(0, smallestIngredient), Math.max(0, freeSpace) / resultAmount);
        return batches * resultAmount;
    }

    // ====================================
    // How many times the grid can still produce, ignoring the recipe: every
    // craft takes exactly one item out of every occupied slot (a recipe that
    // leaves a remainder swaps that slot's item instead, and then stops
    // matching, which the shortest-stack rule already caps at one).
    // ====================================
    private static int smallestIngredient(ItemStack[] matrix) {
        int smallest = 0;
        for (ItemStack item : matrix) {
            if (isEmpty(item)) {
                continue;
            }
            smallest = smallest == 0 ? item.getAmount() : Math.min(smallest, item.getAmount());
        }
        return smallest;
    }

    // ====================================
    // Room for the result in the slots vanilla's quick-move actually targets.
    // Both crafting menus move the result into the player's 36 storage slots
    // and nothing else - CraftingMenu#quickMoveStack moves into 10..46 and
    // InventoryMenu#quickMoveStack into 9..45, which in each case is the main
    // inventory plus the hotbar. Armour and the offhand are deliberately not
    // counted; getStorageContents() is exactly those 36 slots.
    //
    // ponytail: counts room for the result only, while ResultSlot#onTake also
    // drops the recipe's remainder items into the same 36 slots, so a
    // stackable remainder can steal the last slot the final pass needed and
    // the real craft count fall one batch short of the credit. No vanilla
    // recipe reaches it (smallestIngredient always binds first). Upgrade path
    // if a datapack recipe ever does: subtract one batch when the recipe has
    // remainders.
    // ====================================
    private static int freeSpaceFor(Player player, ItemStack result) {
        int max = result.getMaxStackSize();
        int room = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (isEmpty(slot)) {
                room += max;
            } else if (slot.isSimilar(result)) {
                room += Math.max(0, max - slot.getAmount());
            }
        }
        return room;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }
}
