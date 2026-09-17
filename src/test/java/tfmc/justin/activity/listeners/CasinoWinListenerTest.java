package tfmc.justin.activity.listeners;

import net.tfminecraft.games.events.PlayerWonMoneyEvent;
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
// Same technique as MarketSaleListenerTest: CasinoWinListener's constructor
// takes a live-server-backed ActivityManager, so it is built here with a null
// one. Every guard below (null player, non-positive/NaN profit, an unfinished
// carry) returns before the manager is ever touched, so a NullPointerException
// from the null manager is proof the listener tried to credit; its absence
// proves it did not.
//
// Player is stubbed with a Proxy that only answers getUniqueId(), as in
// MarketSaleListenerTest - anything else called on it is a bug in the
// listener under test, not in this stub.
// ====================================
class CasinoWinListenerTest {

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
                CasinoWinListenerTest.class.getClassLoader(), new Class<?>[]{Player.class}, handler);
    }

    private static PlayerWonMoneyEvent win(Player player, double profit) {
        return new PlayerWonMoneyEvent(player, profit, "blackjack");
    }

    @Test
    void aNullPlayerIsIgnored() {
        CasinoWinListener listener = new CasinoWinListener(null);
        assertDoesNotThrow(() -> listener.onPlayerWonMoney(win(null, 5.0)));
    }

    @Test
    void nonPositiveOrNanProfitsAreIgnoredWithoutTouchingThePlayerOrManager() {
        CasinoWinListener listener = new CasinoWinListener(null);
        Player player = stubPlayer(UUID.randomUUID());

        // getUniqueId() would throw from the stub if any of these reached the
        // carry, and recordAction() would NPE on the null manager if any of
        // these earned a point - neither happens, so nothing is credited
        assertDoesNotThrow(() -> listener.onPlayerWonMoney(win(player, 0.0)));
        assertDoesNotThrow(() -> listener.onPlayerWonMoney(win(player, -3.5)));
        assertDoesNotThrow(() -> listener.onPlayerWonMoney(win(player, Double.NaN)));
    }

    // ====================================
    // The daily-task gate, on a real manager (see TestManagers): a revealed
    // task banks its fractions and gets credited; a hidden one banks nothing
    // at all, so revealing it later does not hand over what was skipped.
    // ====================================
    // 'every: 2' on purpose: a single denar counts but awards no point, which
    // keeps the record path away from Bukkit.getPlayer(), unreachable headless
    private static ActivityManager manager() {
        return TestManagers.manager(new ActivityDef("casino_win", "casino_win", Material.EMERALD, null, 2, 1, 0));
    }

    @Test
    void twoHalfDenarWinsCreditAWholeDenarForARevealedTask() {
        ActivityManager manager = manager();
        UUID uuid = UUID.randomUUID();
        assertTrue(manager.reveal(uuid, 0));
        CasinoWinListener listener = new CasinoWinListener(manager);
        Player player = stubPlayer(uuid);

        listener.onPlayerWonMoney(win(player, 0.5));
        listener.onPlayerWonMoney(win(player, 0.5));

        assertEquals(1, manager.tasks(uuid).count("casino_win"));
    }

    @Test
    void aHiddenTaskBanksNoFractionAtAll() {
        ActivityManager manager = manager();
        UUID uuid = UUID.randomUUID();
        CasinoWinListener listener = new CasinoWinListener(manager);
        Player player = stubPlayer(uuid);

        listener.onPlayerWonMoney(win(player, 0.5));
        listener.onPlayerWonMoney(win(player, 0.5));
        assertEquals(0, manager.tasks(uuid).count("casino_win"));

        // Revealed only now: the two skipped halves must not still be waiting
        assertTrue(manager.reveal(uuid, 0));
        listener.onPlayerWonMoney(win(player, 0.5));

        assertEquals(0, manager.tasks(uuid).count("casino_win"));
    }

    // The carry is per-player: a leftover 0.5 from a quitting player must not
    // still be sitting there once they are gone
    @Test
    void quittingClearsTheCarrySoALaterHalfWinCreditsNothing() {
        ActivityManager manager = manager();
        UUID uuid = UUID.randomUUID();
        assertTrue(manager.reveal(uuid, 0));
        CasinoWinListener listener = new CasinoWinListener(manager);
        Player player = stubPlayer(uuid);

        listener.onPlayerWonMoney(win(player, 0.5));
        listener.onQuit(new PlayerQuitEvent(player, (String) null));
        listener.onPlayerWonMoney(win(player, 0.5));

        assertEquals(0, manager.tasks(uuid).count("casino_win"));
    }
}
