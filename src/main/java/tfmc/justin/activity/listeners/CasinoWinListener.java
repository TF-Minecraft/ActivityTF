package tfmc.justin.activity.listeners;

import net.tfminecraft.games.events.PlayerWonMoneyEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tfmc.justin.activity.managers.ActivityManager;

// ====================================
// Games fires PlayerWonMoneyEvent synchronously on the main thread after a
// card-table payout. Only constructed when Games is enabled - see
// ActivityPlugin.
//
// Profit is fractional denar, so the leftover fraction is carried between
// wins rather than rounded away.
// ====================================
public class CasinoWinListener implements Listener {

    private final ActivityManager manager;
    private final FractionCarry carry = new FractionCarry();

    public CasinoWinListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerWonMoney(PlayerWonMoneyEvent event) {
        // The source plugin builds the event itself; a null player would
        // only be a bug there, but it must not take this listener down
        if (event.getPlayer() == null) {
            return;
        }

        // A hand that did not net a profit is not a win
        if (!(event.getProfit() > 0)) {
            return;
        }

        int denar = carry.add(event.getPlayer().getUniqueId(), "casino_win", event.getProfit());
        if (denar > 0) {
            manager.recordAction(event.getPlayer().getUniqueId(), "casino_win", denar);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        carry.forget(event.getPlayer().getUniqueId());
    }
}
