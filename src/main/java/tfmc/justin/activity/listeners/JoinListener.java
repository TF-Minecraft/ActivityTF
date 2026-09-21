package tfmc.justin.activity.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import tfmc.justin.activity.managers.ActivityManager;

public class JoinListener implements Listener {

    private final ActivityManager manager;

    public JoinListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.onJoin(event.getPlayer());
    }
}
