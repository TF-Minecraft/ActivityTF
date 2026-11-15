package net.tfminecraft.activitytf.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.simplefactions.events.PlayerProvinceEnterEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Credits {@code travel_provinces} once for each different province a player walks into each day. Entering the
 * first province after joining (no previous province) does not count, and the list is kept in memory only.
 */
public class ProvinceTravelListener implements Listener {

    private final ActivityManager manager;

    private final Map<UUID, Set<Integer>> visited = new HashMap<>();

    private String day;

    public ProvinceTravelListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProvinceEnter(PlayerProvinceEnterEvent event) {
        if (event.getPlayer() == null || event.getPreviousProvinceId() == null) {
            return;
        }
        String today = manager.getConfiguration().currentKeys().day();
        if (!today.equals(day)) {
            visited.clear();
            day = today;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        if (visited.computeIfAbsent(uuid, key -> new HashSet<>()).add(event.getProvinceId())) {
            manager.recordAction(uuid, "travel_provinces", 1);
        }
    }

    int tracked() {
        return visited.size();
    }
}
