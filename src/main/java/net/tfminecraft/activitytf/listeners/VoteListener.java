package net.tfminecraft.activitytf.listeners;

import com.bencodez.votingplugin.events.PlayerVoteEvent;
import com.bencodez.votingplugin.user.VotingPluginUser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.utils.Utils;

import java.util.UUID;

public class VoteListener implements Listener {

    private final JavaPlugin plugin;
    private final ActivityManager manager;

    public VoteListener(JavaPlugin plugin, ActivityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVote(PlayerVoteEvent event) {
        if (event.isCancelled()) {
            return;
        }

        if (!event.isRealVote()) {
            return;
        }

        VotingPluginUser user = event.getVotingPluginUser();
        UUID javaUuid = user == null ? null : user.getJavaUUID();
        String name = event.getPlayer();

        if (!plugin.isEnabled()) {
            return;
        }

        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                UUID uuid = resolve(javaUuid, name);
                if (uuid != null) {
                    manager.recordAction(uuid, "vote", 1);
                }
            });
        } catch (IllegalPluginAccessException | IllegalStateException e) {
        }
    }

    private UUID resolve(UUID javaUuid, String name) {
        if (javaUuid != null) {
            return javaUuid;
        }

        if (name == null || name.isBlank()) {
            plugin.getLogger().warning("Ignoring a vote with neither a user nor a player name.");
            return null;
        }

        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.hasPlayedBefore()) {
            return cached.getUniqueId();
        }

        plugin.getLogger().info("Dropping a vote from '" + Utils.safeForLog(name)
            + "' - no cached player by that name.");
        return null;
    }
}
