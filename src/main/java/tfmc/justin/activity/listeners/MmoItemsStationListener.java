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
