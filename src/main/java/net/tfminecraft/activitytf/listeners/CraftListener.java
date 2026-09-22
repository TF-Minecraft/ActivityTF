package net.tfminecraft.activitytf.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;
import net.tfminecraft.activitytf.managers.ActivityManager;

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

    static boolean creditable(int rawSlot, boolean resultPresent, ClickType click,
                              InventoryAction action, boolean destinationOccupied) {
        return rawSlot == 0
            && resultPresent
            && takesFromResult(click, action, destinationOccupied);
    }

    static boolean takesFromResult(ClickType click, InventoryAction action, boolean destinationOccupied) {
        if (action == InventoryAction.NOTHING) {
            return false;
        }
        return switch (click) {
            case LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT, DROP, CONTROL_DROP -> true;
            case NUMBER_KEY, SWAP_OFFHAND -> !destinationOccupied;
            default -> false;
        };
    }

    private static boolean destinationOccupied(Player player, CraftItemEvent event) {
        return switch (event.getClick()) {
            case NUMBER_KEY -> {
                int button = event.getHotbarButton();
                yield button < 0 || button > 8 || !isEmpty(player.getInventory().getItem(button));
            }
            case SWAP_OFFHAND -> !isEmpty(player.getInventory().getItemInOffHand());
            default -> false;
        };
    }

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
