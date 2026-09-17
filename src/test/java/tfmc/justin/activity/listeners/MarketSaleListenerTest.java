package tfmc.justin.activity.listeners;

import net.tfminecraft.events.MarketSaleEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.managers.TestManagers;
import tfmc.justin.activity.models.ActivityDef;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// MarketSaleListener needs a live server for a real ActivityManager (its
// constructor is a private Bukkit-backed singleton), so the listener is
// built with a null manager here. That works because the manager is only
// ever touched once a sale actually earns a whole point - every guard below
// (null player, non-positive price, an unfinished carry) returns before that
// point. A NullPointerException from the null manager is therefore used as a
// witness that the listener tried to credit; its absence proves it did not.
//
// Player is an interface, so a bare stub that only answers getUniqueId() (and
// fails loudly if anything else is called) stands in for a real one without a
// mocking library.
// ====================================
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

        // getUniqueId() would throw from the stub if any of these reached the
        // carry, and recordAction() would NPE on the null manager if any of
        // these earned a point - neither happens, so nothing is credited
        assertDoesNotThrow(() -> listener.onMarketSale(sale(player, 0.0)));
        assertDoesNotThrow(() -> listener.onMarketSale(sale(player, -3.5)));
        assertDoesNotThrow(() -> listener.onMarketSale(sale(player, Double.NaN)));
    }

    // ====================================
    // The daily-task gate, on a real manager (see TestManagers): a revealed
    // task banks its fractions and gets credited; a hidden one banks nothing
    // at all, so revealing it later does not hand over what was skipped.
    // ====================================
    // 'every: 2' on purpose: a single denar counts but awards no point, which
    // keeps the record path away from Bukkit.getPlayer(), unreachable headless
    private static ActivityManager manager() {
        return TestManagers.manager(new ActivityDef("market_sale", "market_sale", Material.EMERALD, null, 2, 1, 0));
    }

    @Test
    void twoHalfDenarSalesCreditAWholeDenarForARevealedTask() {
        ActivityManager manager = manager();
        UUID uuid = UUID.randomUUID();
        assertTrue(manager.reveal(uuid, 0));
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

        // Revealed only now: the two skipped halves must not still be waiting
        assertTrue(manager.reveal(uuid, 0));
        listener.onMarketSale(sale(player, 0.5));

        assertEquals(0, manager.tasks(uuid).count("market_sale"));
    }

    // The carry is per-player: a leftover 0.5 from a quitting player must not
    // still be sitting there once they are gone
    @Test
    void quittingClearsTheCarrySoALaterHalfSaleCreditsNothing() {
        ActivityManager manager = manager();
        UUID uuid = UUID.randomUUID();
        assertTrue(manager.reveal(uuid, 0));
        MarketSaleListener listener = new MarketSaleListener(manager);
        Player player = stubPlayer(uuid);

        listener.onMarketSale(sale(player, 0.5));
        listener.onQuit(new PlayerQuitEvent(player, (String) null));
        listener.onMarketSale(sale(player, 0.5));

        assertEquals(0, manager.tasks(uuid).count("market_sale"));
    }
}
