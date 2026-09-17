package tfmc.justin.activity.listeners;

import net.tfminecraft.events.MarketSaleEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tfmc.justin.activity.managers.ActivityManager;

// ====================================
// MarketBlock fires MarketSaleEvent synchronously on the main thread after a
// sale is paid out. Only constructed when MarketBlock is enabled - see
// ActivityPlugin.
//
// Prices are fractional and small (half a denar a sale is normal), so the
// leftover fraction is carried between sales rather than rounded away.
// ====================================
public class MarketSaleListener implements Listener {

    private final ActivityManager manager;
    private final FractionCarry carry = new FractionCarry();

    public MarketSaleListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMarketSale(MarketSaleEvent event) {
        // The source plugin builds the event itself; a null player would
        // only be a bug there, but it must not take this listener down
        if (event.getPlayer() == null) {
            return;
        }

        // A sale that paid nothing is not an earning
        if (!(event.getPrice() > 0)) {
            return;
        }

        // A hidden or undrawn task must not bank a fraction either
        if (!manager.isTracked(event.getPlayer().getUniqueId(), "market_sale")) {
            carry.forget(event.getPlayer().getUniqueId(), "market_sale");
            return;
        }

        int denar = carry.add(event.getPlayer().getUniqueId(), "market_sale", event.getPrice());
        if (denar > 0) {
            manager.recordAction(event.getPlayer().getUniqueId(), "market_sale", denar);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        carry.forget(event.getPlayer().getUniqueId());
    }
}
