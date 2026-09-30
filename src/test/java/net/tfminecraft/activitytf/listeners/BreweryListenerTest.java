package net.tfminecraft.activitytf.listeners;

import com.dre.brewery.api.events.IngedientAddEvent;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.managers.TestManagers;
import net.tfminecraft.activitytf.models.ActivityDef;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerEvent;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BreweryListenerTest {

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
                BreweryListenerTest.class.getClassLoader(), new Class<?>[]{Player.class}, handler);
    }

    static void set(Class<?> owner, Object target, String name, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static IngedientAddEvent ingredient(Player player) {
        IngedientAddEvent event = new ObjenesisStd().newInstance(IngedientAddEvent.class);
        set(PlayerEvent.class, event, "player", player);
        return event;
    }

    /** A manager with every given activity revealed for the returned player. */
    static UUID revealed(ActivityManager manager) {
        TestManagers.bukkit();
        TestManagers.storeLoaded(manager);
        UUID uuid = UUID.randomUUID();
        for (int slot = 0; slot < manager.tasks(uuid).tasks().size(); slot++) {
            manager.reveal(uuid, slot);
        }
        return uuid;
    }

    static ActivityManager managerWith(String... ids) {
        ActivityDef[] defs = new ActivityDef[ids.length];
        for (int i = 0; i < ids.length; i++) {
            defs[i] = new ActivityDef(ids[i], ids[i], Material.POTION, null, 1, 1, 0);
        }
        ActivityManager manager = TestManagers.manager(defs);
        TestManagers.guarantee(manager, ids);
        return manager;
    }

    @Test
    void anIngredientWithoutAPlayerIsIgnored() {
        BreweryListener listener = new BreweryListener(null);
        assertDoesNotThrow(() -> listener.onIngredientAdd(ingredient(null)));
    }

    @Test
    void eachIngredientAddedCreditsOne() {
        ActivityManager manager = managerWith("brew_ingredient");
        UUID uuid = revealed(manager);
        BreweryListener listener = new BreweryListener(manager);

        listener.onIngredientAdd(ingredient(stubPlayer(uuid)));
        listener.onIngredientAdd(ingredient(stubPlayer(uuid)));

        assertEquals(2, manager.tasks(uuid).count("brew_ingredient"));
    }

    @Test
    void aBottleWithoutAPlayerIsIgnored() {
        BreweryListener listener = new BreweryListener(null);
        assertDoesNotThrow(() -> listener.recordBottle(null, 5));
    }

    @Test
    void onlyCookedBottlesAreCredited() {
        ActivityManager manager = managerWith("brew_bottle");
        UUID uuid = revealed(manager);
        BreweryListener listener = new BreweryListener(manager);

        listener.recordBottle(stubPlayer(uuid), 0);
        assertEquals(0, manager.tasks(uuid).count("brew_bottle"));

        listener.recordBottle(stubPlayer(uuid), 1);
        listener.recordBottle(stubPlayer(uuid), 12);
        assertEquals(2, manager.tasks(uuid).count("brew_bottle"));
    }
}
