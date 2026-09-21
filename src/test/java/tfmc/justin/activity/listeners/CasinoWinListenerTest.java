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
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

        assertDoesNotThrow(() -> listener.onPlayerWonMoney(win(player, 0.0)));
        assertDoesNotThrow(() -> listener.onPlayerWonMoney(win(player, -3.5)));
        assertDoesNotThrow(() -> listener.onPlayerWonMoney(win(player, Double.NaN)));
    }

    private static ActivityManager manager() {
        return TestManagers.manager(new ActivityDef("casino_win", "casino_win", Material.EMERALD, null, 2, 1, 0));
    }

    @Test
    void twoHalfDenarWinsCreditAWholeDenarForARevealedTask() {
        ActivityManager manager = manager();
        UUID uuid = UUID.randomUUID();
        assertNotNull(manager.reveal(uuid, 0).revealedId());
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

        assertNotNull(manager.reveal(uuid, 0).revealedId());
        listener.onPlayerWonMoney(win(player, 0.5));

        assertEquals(0, manager.tasks(uuid).count("casino_win"));
    }

    @Test
    void quittingClearsTheCarrySoALaterHalfWinCreditsNothing() {
        ActivityManager manager = manager();
        UUID uuid = UUID.randomUUID();
        assertNotNull(manager.reveal(uuid, 0).revealedId());
        CasinoWinListener listener = new CasinoWinListener(manager);
        Player player = stubPlayer(uuid);

        listener.onPlayerWonMoney(win(player, 0.5));
        listener.onQuit(new PlayerQuitEvent(player, (String) null));
        listener.onPlayerWonMoney(win(player, 0.5));

        assertEquals(0, manager.tasks(uuid).count("casino_win"));
    }
}
