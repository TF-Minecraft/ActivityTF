package tfmc.justin.activity.listeners;

import net.tfminecraft.games.events.PlayerWonMoneyEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    // Sanity check that the NPE-as-witness approach above actually detects a
    // credit: two 0.5 denar wins for the same player DO earn a whole point,
    // which requires the null manager and blows up - proving the guard tests
    // above are not vacuously passing
    @Test
    void twoHalfDenarWinsDoReachTheManager() {
        CasinoWinListener listener = new CasinoWinListener(null);
        Player player = stubPlayer(UUID.randomUUID());

        listener.onPlayerWonMoney(win(player, 0.5));
        assertThrows(NullPointerException.class, () -> listener.onPlayerWonMoney(win(player, 0.5)));
    }

    // The carry is per-player: a leftover 0.5 from a quitting player must not
    // still be sitting there once they are gone
    @Test
    void quittingClearsTheCarrySoALaterHalfWinCreditsNothing() {
        CasinoWinListener listener = new CasinoWinListener(null);
        UUID uuid = UUID.randomUUID();
        Player player = stubPlayer(uuid);

        listener.onPlayerWonMoney(win(player, 0.5));
        listener.onQuit(new PlayerQuitEvent(player, (String) null));

        // If the carry had survived, this second 0.5 would complete a whole
        // point and try to record it on the null manager
        assertDoesNotThrow(() -> listener.onPlayerWonMoney(win(player, 0.5)));
    }
}
