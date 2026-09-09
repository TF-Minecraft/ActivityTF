package tfmc.justin.activity.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import tfmc.justin.activity.managers.ActivityManager;

// ====================================
// Join is both the rollover trigger for returning players and the moment a
// reward earned while they were offline can finally be handed over
// ====================================
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
