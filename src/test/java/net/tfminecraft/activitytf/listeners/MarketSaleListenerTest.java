package net.tfminecraft.activitytf.listeners;

import net.tfminecraft.marketblock.events.MarketSaleEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.managers.TestManagers;
import net.tfminecraft.activitytf.models.ActivityDef;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MarketSaleListenerTest {

    private static Player stubPlayer(UUID uuid) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "toString" -> "stub-player";
            case "hashCode" -> uuid.hashCode();
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(
                    "unexpected call to Player#" + method.getName() + " - this test double only answers getUniqueId()");
        };
        return (Player) Proxy.newProxyInstance(
                MarketSaleListenerTest.class.getClassLoader(), new Class<?>[]{Player.class}, handler);
    }

    private static MarketSaleEvent sale(Player player, double price) {
        return new MarketSaleEvent(player, null, price, 1.0);
    }

    @Test
    void aNullPlayerIsIgnored() {
        MarketSaleListener listener = new MarketSaleListener(null);
        assertDoesNotThrow(() -> listener.onMarketSale(sale(null, 5.0)));
    }

    @Test
    void nonPositivePricesAreIgnoredWithoutTouchingThePlayerOrManager() {
        MarketSaleListener listener = new MarketSaleListener(null);
        Player player = stubPlayer(UUID.randomUUID());

        assertDoesNotThrow(() -> listener.onMarketSale(sale(player, 0.0)));
        assertDoesNotThrow(() -> listener.onMarketSale(sale(player, -3.5)));
        assertDoesNotThrow(() -> listener.onMarketSale(sale(player, Double.NaN)));
    }

    private static ActivityManager manager() {
        return TestManagers.manager(new ActivityDef("market_sale", "market_sale", Material.EMERALD, null, 2, 1, 0));
    }

    @Test
    void twoHalfDenarSalesCreditAWholeDenarForARevealedTask() {
        ActivityManager manager = manager();
        UUID uuid = UUID.randomUUID();
        assertNotNull(manager.reveal(uuid, 0).revealedId());
        MarketSaleListener listener = new MarketSaleListener(manager);
        Player player = stubPlayer(uuid);

        listener.onMarketSale(sale(player, 0.5));
        listener.onMarketSale(sale(player, 0.5));

        assertEquals(1, manager.tasks(uuid).count("market_sale"));
    }

    @Test
    void aHiddenTaskBanksNoFractionAtAll() {
        ActivityManager manager = manager();
        UUID uuid = UUID.randomUUID();
        MarketSaleListener listener = new MarketSaleListener(manager);
        Player player = stubPlayer(uuid);

        listener.onMarketSale(sale(player, 0.5));
        listener.onMarketSale(sale(player, 0.5));
        assertEquals(0, manager.tasks(uuid).count("market_sale"));

        assertNotNull(manager.reveal(uuid, 0).revealedId());
        listener.onMarketSale(sale(player, 0.5));

        assertEquals(0, manager.tasks(uuid).count("market_sale"));
    }

    @Test
    void quittingClearsTheCarrySoALaterHalfSaleCreditsNothing() {
        ActivityManager manager = manager();
        UUID uuid = UUID.randomUUID();
        assertNotNull(manager.reveal(uuid, 0).revealedId());
        MarketSaleListener listener = new MarketSaleListener(manager);
        Player player = stubPlayer(uuid);

        listener.onMarketSale(sale(player, 0.5));
        listener.onQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
        listener.onMarketSale(sale(player, 0.5));

        assertEquals(0, manager.tasks(uuid).count("market_sale"));
    }
}
