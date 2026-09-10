package tfmc.justin.activity.listeners;

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
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.utils.Utils;

import java.util.UUID;

// ====================================
// VotingPlugin fires PlayerVoteEvent off the main thread for proxy-forwarded
// votes, so the UUID is resolved here and the scoring hops back onto a tick.
// Only constructed when VotingPlugin is enabled - see ActivityPlugin.
//
// MONITOR because the vote should only count once every other plugin has had
// its say. PlayerVoteEvent has isCancelled() but does not implement
// Cancellable, so ignoreCancelled does nothing - the check has to be manual.
// ====================================
public class VoteListener implements Listener {

    private final JavaPlugin plugin;
    // Held rather than looked up per vote, like the other listeners: the
    // static instance is nulled at shutdown, and this event can still be in
    // flight on the vote thread at that point
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

        // Test votes and reminders fire the same event
        if (!event.isRealVote()) {
            return;
        }

        // Only plain data is read off the vote thread; every lookup that
        // touches server state happens on the tick below
        VotingPluginUser user = event.getVotingPluginUser();
        UUID javaUuid = user == null ? null : user.getJavaUUID();
        String name = event.getPlayer();

        // The scheduler throws for a disabled plugin, and a vote arriving
        // during shutdown has nowhere to be scored anyway
        if (!plugin.isEnabled()) {
            return;
        }

        // isEnabled() was true a line ago, but a disable landing in between
        // would throw on VotingPlugin's thread rather than ours
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                UUID uuid = resolve(javaUuid, name);
                if (uuid != null) {
                    manager.recordAction(uuid, "vote", 1);
                }
            });
        } catch (IllegalPluginAccessException | IllegalStateException e) {
            // Plugin is being disabled, vote dropped
        }
    }

    // ====================================
    // Main thread only: Bukkit.getPlayerExact and the offline-player cache are
    // not safe off a tick. Bukkit.getOfflinePlayer(String) is not used at all:
    // it blocks on a Mojang lookup and invents a UUID for names that have
    // never played, which would quietly fill players.yml with junk.
    // ====================================
    private UUID resolve(UUID javaUuid, String name) {
        if (javaUuid != null) {
            return javaUuid;
        }

        // Proxy-forwarded votes can arrive with no user attached; the name is
        // always set
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
