package tfmc.justin.activity.listeners;

import net.tfminecraft.games.events.PlayerWonMoneyEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tfmc.justin.activity.managers.ActivityManager;

public class CasinoWinListener implements Listener {

    private final ActivityManager manager;
    private final FractionCarry carry = new FractionCarry();

    public CasinoWinListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerWonMoney(PlayerWonMoneyEvent event) {
        if (event.getPlayer() == null) {
            return;
        }

        if (!(event.getProfit() > 0)) {
            return;
        }

        if (!manager.isTracked(event.getPlayer().getUniqueId(), "casino_win")) {
            carry.forget(event.getPlayer().getUniqueId(), "casino_win");
            return;
        }

        int denar = carry.add(event.getPlayer().getUniqueId(), "casino_win", event.getProfit(),
            manager.getConfiguration().currentKeys());
        if (denar > 0) {
            manager.recordAction(event.getPlayer().getUniqueId(), "casino_win", denar);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        carry.forget(event.getPlayer().getUniqueId());
    }
}
