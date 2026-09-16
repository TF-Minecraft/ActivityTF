package tfmc.justin.activity.listeners;

import net.Indyuce.mmoitems.api.crafting.CraftingStation;
import net.Indyuce.mmoitems.api.crafting.recipe.Recipe;
import net.Indyuce.mmoitems.api.event.PlayerUseCraftingStationEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

// ====================================
// Feeds MMOItems crafting-station crafts into whichever activity declares
// 'station: <station>' or 'station: <station>/<recipe>' in config.yml. Only
// constructed when MMOItems is enabled - see ActivityPlugin.
//
// MONITOR + ignoreCancelled: the craft only counts once every other plugin
// has had its say and it is actually going through.
//
// Only a craft with a result counts. Verified with javap -c on
// CraftingRecipe#whenUsed and EditableCraftingStationView$CraftingQueueItem
// in MMOItems 6.10.1: both the instant craft and the claim of a finished
// queue item construct the event through the constructor that takes the
// output ItemStack, while queueing a recipe and cancelling a queued one use
// the constructor without one. hasResult() is exactly 'result != null', so
// it is true only at the moment MMOItems hands the item over - queueing and
// cancelling count nothing.
//
// One event is one action regardless of the output stack size, like the
// other one-per-action activities.
// ====================================
public class MmoItemsStationListener implements Listener {

    private final ActivityManager manager;

    public MmoItemsStationListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUseCraftingStation(PlayerUseCraftingStationEvent event) {
        if (!event.hasResult()) {
            return;
        }

        CraftingStation station = event.getStation();
        // getRecipe() is null for the event forms carrying no recipe at all
        Recipe recipe = event.getRecipe();
        if (station == null || recipe == null) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        manager.getConfiguration().stationActivity(station.getId(), recipe.getId())
            .ifPresent(activityId -> manager.recordAction(player.getUniqueId(), activityId, 1));
    }
}
