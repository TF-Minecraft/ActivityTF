package net.tfminecraft.activitytf.listeners;

import net.tfminecraft.cooking.events.DishCookedEvent;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DishCookedListenerTest {

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
                DishCookedListenerTest.class.getClassLoader(), new Class<?>[]{Player.class}, handler);
    }

    private static DishCookedEvent dish(Player player) {
        try {
            DishCookedEvent event = new ObjenesisStd().newInstance(DishCookedEvent.class);

            Field playerField = DishCookedEvent.class.getDeclaredField("player");
            playerField.setAccessible(true);
            playerField.set(event, player);

            Field methodField = DishCookedEvent.class.getDeclaredField("method");
            methodField.setAccessible(true);
            methodField.set(event, "furnace");

            return event;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void aNullPlayerIsIgnored() {
        DishCookedListener listener = new DishCookedListener(null);
        assertDoesNotThrow(() -> listener.onDishCooked(dish(null)));
    }

    @Test
    void aCookedDishForARealPlayerReachesTheManager() {
        DishCookedListener listener = new DishCookedListener(null);
        Player player = stubPlayer(UUID.randomUUID());

        assertThrows(NullPointerException.class, () -> listener.onDishCooked(dish(player)));
    }
    @Test
    void troughCompletionCreditsFeedOnly() throws Exception {
        var feed = new net.tfminecraft.activitytf.models.ActivityDef("animal_universal_feed", "Feed",
            org.bukkit.Material.WHEAT, null, 2, 1, 1);
        var food = new net.tfminecraft.activitytf.models.ActivityDef("cook_dish", "Food",
            org.bukkit.Material.WHEAT, null, 5, 1, 1);
        var manager = net.tfminecraft.activitytf.managers.TestManagers.manager(feed, food);
        net.tfminecraft.activitytf.managers.TestManagers.guarantee(manager, feed.id(), food.id());
        net.tfminecraft.activitytf.managers.TestManagers.storeLoaded(manager);
        UUID uuid = UUID.randomUUID();
        for (int slot = 0; slot < manager.tasks(uuid).tasks().size(); slot++) {
            manager.reveal(uuid, slot);
        }
        DishCookedEvent event = dish(stubPlayer(uuid));
        Field method = DishCookedEvent.class.getDeclaredField("method");
        method.setAccessible(true);
        method.set(event, "trough");
        new DishCookedListener(manager).onDishCooked(event);
        org.junit.jupiter.api.Assertions.assertEquals(1, manager.tasks(uuid).count(feed.id()));
        org.junit.jupiter.api.Assertions.assertEquals(0, manager.tasks(uuid).count(food.id()));
        method.set(event, "furnace");
        new DishCookedListener(manager).onDishCooked(event);
        org.junit.jupiter.api.Assertions.assertEquals(1, manager.tasks(uuid).count(feed.id()));
        org.junit.jupiter.api.Assertions.assertEquals(1, manager.tasks(uuid).count(food.id()));
    }

}
