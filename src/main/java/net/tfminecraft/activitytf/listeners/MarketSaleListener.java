package net.tfminecraft.activitytf.listeners;

import net.tfminecraft.marketblock.events.MarketSaleEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import net.tfminecraft.activitytf.managers.ActivityManager;

public class MarketSaleListener implements Listener {

    private final ActivityManager manager;
    private final FractionCarry carry = new FractionCarry();

    public MarketSaleListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMarketSale(MarketSaleEvent event) {
        if (event.getPlayer() == null) {
            return;
        }

        if (!(event.getPrice() > 0)) {
            return;
        }

        if (!manager.isTracked(event.getPlayer().getUniqueId(), "market_sale")) {
            carry.forget(event.getPlayer().getUniqueId(), "market_sale");
            return;
        }

        int denar = carry.add(event.getPlayer().getUniqueId(), "market_sale", event.getPrice(),
            manager.getConfiguration().currentKeys());
        if (denar > 0) {
            manager.recordAction(event.getPlayer().getUniqueId(), "market_sale", denar);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        carry.forget(event.getPlayer().getUniqueId());
    }
}
