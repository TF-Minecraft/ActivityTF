package net.tfminecraft.activitytf.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import net.tfminecraft.activitytf.managers.ReferralManager;

/** Feeds the referral playtime: joins, quits, and the actions that show someone is not AFK. */
public class ReferralListener implements Listener {

    private final ReferralManager referrals;

    public ReferralListener(ReferralManager referrals) {
        this.referrals = referrals;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        referrals.onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        referrals.onQuit(event.getPlayer().getUniqueId());
    }

    // Roleplay chat is often cancelled and re-sent by the character plugin; it still means someone typed.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        referrals.noteActive(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        referrals.noteActive(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        referrals.noteActive(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        referrals.noteActive(event.getPlayer().getUniqueId());
    }
}
