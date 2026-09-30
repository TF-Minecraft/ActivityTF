package net.tfminecraft.activitytf.listeners;

import com.dre.brewery.BIngredients;
import com.dre.brewery.Brew;
import com.dre.brewery.api.events.IngedientAddEvent;
import com.dre.brewery.api.events.brew.BrewModifyEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.tfminecraft.activitytf.managers.ActivityManager;

public class BreweryListener implements Listener {

    private final ActivityManager manager;

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
}
