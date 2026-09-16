package tfmc.justin.activity.listeners;

import net.tfminecraft.cooking.events.DishCookedEvent;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import sun.reflect.ReflectionFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

// ====================================
// DishCookedListener needs a live server for a real ActivityManager, so it is
// built with a null manager here, same as MarketSaleListenerTest - a
// NullPointerException from the null manager is used as a witness that the
// listener tried to record a dish.
//
// DishCookedEvent's own constructor unconditionally clones its ItemStack
// argument (result.clone()), and building any real ItemStack headless blows
// up on the Bukkit Material registry (see TLibsItemsTest) - and passing null
// would NPE inside the constructor itself, before this listener ever sees the
// event. The listener under test never reads getResult(), so the event is
// built here by bypassing its constructor via the same
// newConstructorForSerialization trick common serialization libraries use:
// it allocates the object and runs only Object's constructor, then the
// player/method fields are set directly by reflection. That sidesteps the
// clone() call entirely instead of needing a live server.
// ====================================
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
            ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
            Constructor<Object> objectCtor = Object.class.getDeclaredConstructor();
            Constructor<?> bypass = rf.newConstructorForSerialization(DishCookedEvent.class, objectCtor);
            DishCookedEvent event = (DishCookedEvent) bypass.newInstance();

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

    // Sanity check that the NPE-as-witness approach above actually detects a
    // credit: a real player cooking a dish DOES reach the manager, which
    // requires the null manager and blows up - proving the null-player test
    // above is not vacuously passing
    @Test
    void aCookedDishForARealPlayerReachesTheManager() {
        DishCookedListener listener = new DishCookedListener(null);
        Player player = stubPlayer(UUID.randomUUID());

        assertThrows(NullPointerException.class, () -> listener.onDishCooked(dish(player)));
    }
}
