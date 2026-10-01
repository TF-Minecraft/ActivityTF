package net.tfminecraft.activitytf.listeners;

import com.dre.brewery.BIngredients;
import com.dre.brewery.Barrel;
import com.dre.brewery.Brew;
import com.dre.brewery.api.events.IngedientAddEvent;
import com.dre.brewery.api.events.brew.BrewDrinkEvent;
import com.dre.brewery.api.events.brew.BrewModifyEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.activitytf.managers.ActivityManager;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class BreweryListener implements Listener {

    static final NamespacedKey DISTILLED = new NamespacedKey("activity", "brew_distilled");
    static final NamespacedKey AGED = new NamespacedKey("activity", "brew_aged");

    // Brews younger than this many BreweryX years have not been aged on purpose.
    static final float MIN_AGE_YEARS = 1.0f;

    // Clicks that take the clicked item out of its slot.
    static final Set<InventoryAction> TAKES = EnumSet.of(
        InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_SOME, InventoryAction.PICKUP_HALF,
        InventoryAction.PICKUP_ONE, InventoryAction.MOVE_TO_OTHER_INVENTORY, InventoryAction.HOTBAR_SWAP,
        InventoryAction.SWAP_WITH_CURSOR, InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT,
        InventoryAction.PICKUP_ALL_INTO_BUNDLE, InventoryAction.PICKUP_SOME_INTO_BUNDLE);

    private final ActivityManager manager;

    // Nonce of every tag written by a take that has not been confirmed yet, and who made it.
    private final Map<Long, UUID> pending = new HashMap<>();

    public BreweryListener(ActivityManager manager) {
        this.manager = manager;
    }

    // BreweryX only fires this for an item a cauldron recipe accepts, once per item added.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIngredientAdd(IngedientAddEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        manager.recordAction(event.getPlayer().getUniqueId(), "brew_ingredient", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrewModify(BrewModifyEvent event) {
        if (event.getType() == BrewModifyEvent.Type.FILL) {
            recordBottle(event.getPlayer(), cookedMinutes(event.getBrew()));
        }
    }

    // FILL fires once per bottle scooped from a cauldron. A bottle taken before the
    // ingredients have cooked for a minute is BreweryX's "thick brew" and earns nothing.
    void recordBottle(Player player, int cookedMinutes) {
        if (player == null || cookedMinutes < 1) {
            return;
        }
        manager.recordAction(player.getUniqueId(), "brew_bottle", 1);
    }

    private static int cookedMinutes(Brew brew) {
        BIngredients ingredients = brew == null ? null : brew.getIngredients();
        return ingredients == null ? 0 : ingredients.getCookedTime();
    }

    // BreweryX distils and ages with no player attached, so the credit goes to whoever
    // takes a distilled brew out of a brewing stand's bottle slots, or a brew aged for at
    // least a year out of a barrel. The tag written onto the brew keeps it from counting
    // again when it is put back and taken out.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onContainerTake(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !TAKES.contains(event.getAction())) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        ItemStack item = event.getCurrentItem();
        if (clicked == null || clicked != event.getView().getTopInventory()
                || item == null || item.getType() != Material.POTION) {
            return;
        }
        if (clicked.getType() == InventoryType.BREWING && event.getSlot() < 3) {
            Brew brew = Brew.get(item);
            if (brew != null && brew.getDistillRuns() > 0) {
                creditOnce(player, clicked, event.getSlot(), item, DISTILLED, "brew_distill");
            }
        } else if (clicked.getHolder(false) instanceof Barrel) {
            Brew brew = Brew.get(item);
            if (brew != null && brew.getAgeTime() >= MIN_AGE_YEARS) {
                creditOnce(player, clicked, event.getSlot(), item, AGED, "brew_age");
            }
        }
    }

    // Brews that match no recipe (failed or unfinished ones) have quality 0.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrink(BrewDrinkEvent event) {
        recordDrink(event.getPlayer(), event.getQuality());
    }

    void recordDrink(Player player, int quality) {
        if (player == null || quality < 1) {
            return;
        }
        manager.recordAction(player.getUniqueId(), "brew_drink", 1);
    }

    private void creditOnce(Player player, Inventory inventory, int slot, ItemStack item,
                            NamespacedKey key, String activityId) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        Long previous = data.has(key, PersistentDataType.LONG) ? data.get(key, PersistentDataType.LONG) : null;
        if (previous != null && pending.remove(previous) != null) {
            // Someone else's take of this brew is still unconfirmed: this click replaces it.
            data.remove(key);
        }
        long nonce = ThreadLocalRandom.current().nextLong();
        if (!markOnce(data, key, nonce)) {
            return;
        }
        // The clicked item mirrors the slot, so the tag travels with the brew.
        item.setItemMeta(meta);
        pending.put(nonce, player.getUniqueId());
        // The click fires before the move happens, and a quick-move into a full inventory
        // leaves the brew where it was. Credit only once it has left the slot; otherwise
        // take the tag back off so a later take can still count.
        Bukkit.getScheduler().runTask(JavaPlugin.getProvidingPlugin(BreweryListener.class), () -> {
            UUID taker = pending.remove(nonce);
            if (taker == null) {
                return; // replaced by a later take
            }
            ItemStack left = inventory.getItem(slot);
            ItemMeta leftMeta = left == null ? null : left.getItemMeta();
            if (leftMeta != null && unmarkIfStill(leftMeta.getPersistentDataContainer(), key, nonce)) {
                left.setItemMeta(leftMeta);
                inventory.setItem(slot, left);
                return;
            }
            manager.recordAction(taker, activityId, 1);
        });
    }

    static boolean markOnce(PersistentDataContainer data, NamespacedKey key, long nonce) {
        if (data.has(key)) {
            return false;
        }
        data.set(key, PersistentDataType.LONG, nonce);
        return true;
    }

    // True, and the tag removed, when this is still the brew marked with this nonce.
    static boolean unmarkIfStill(PersistentDataContainer data, NamespacedKey key, long nonce) {
        if (!data.has(key, PersistentDataType.LONG) || data.get(key, PersistentDataType.LONG) != nonce) {
            return false;
        }
        data.remove(key);
        return true;
    }
}
