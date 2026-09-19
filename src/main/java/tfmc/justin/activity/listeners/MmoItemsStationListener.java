package tfmc.justin.activity.listeners;

import net.Indyuce.mmoitems.api.crafting.CraftingStation;
import net.Indyuce.mmoitems.api.crafting.recipe.Recipe;
import net.Indyuce.mmoitems.api.event.PlayerUseCraftingStationEvent;
import net.Indyuce.mmoitems.api.event.PlayerUseCraftingStationEvent.StationAction;
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
// Only a finished craft counts, and that is decided by getInteraction(), not
// by hasResult(). Verified with javap -c on CraftingRecipe#whenUsed,
// UpgradingRecipe#whenUsed and EditableCraftingStationView$CraftingQueueItem
// in MMOItems 6.10.1 - the only three places that fire this event:
//   INSTANT_RECIPE       instant craft went through
//   CRAFTING_QUEUE       a finished queued craft was claimed
//   INTERACT_WITH_RECIPE a non-instant recipe was only added to the queue
//   CANCEL_QUEUE         a queued craft was cancelled
//   UPGRADE_RECIPE       an upgrading recipe was applied
// The first two are the completed craft; the rest are not. hasResult() is
// exactly 'result != null', and both completed-craft paths pass the output
// stack only when the recipe has the OUTPUT_ITEM option - with
// 'output-item: false' they pass null, so hasResult() misses those crafts
// entirely. Upgrade recipes never carry a result and are still not counted,
// same as before.
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
        StationAction action = event.getInteraction();
        if (action != StationAction.INSTANT_RECIPE && action != StationAction.CRAFTING_QUEUE) {
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
