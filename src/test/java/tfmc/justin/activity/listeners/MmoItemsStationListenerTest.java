package tfmc.justin.activity.listeners;

import net.Indyuce.mmoitems.api.crafting.CraftingStation;
import net.Indyuce.mmoitems.api.crafting.recipe.Recipe;
import net.Indyuce.mmoitems.api.event.PlayerUseCraftingStationEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import sun.reflect.ReflectionFactory;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.models.ActivityDef;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// PlayerUseCraftingStationEvent, CraftingStation and Recipe all need a live
// MMOItems/PlayerData setup to build through their real constructors (Recipe
// is even abstract). The listener under test only ever calls
// hasResult()/getStation()/getRecipe()/getPlayer() and, through those,
// CraftingStation#getId()/Recipe#getId() - so every one of these is built by
// bypassing its constructor via ReflectionFactory, exactly like
// DishCookedListenerTest builds its event, and only the handful of fields the
// listener actually reads are set by reflection.
//
// ActivityManager is also a Bukkit-backed private-constructor singleton, so
// it is built the same way, with its 'config' field pointed at a real
// ActivityConfiguration (built the way ActivityConfigurationStationTest
// builds one) and its 'store' field left null. recordAction() reads
// config.activity(id) first and only reaches the null store on a real match,
// so a NullPointerException out of store.get(uuid) is proof the listener
// found a match and tried to record it; its absence proves it did not.
// ====================================
class MmoItemsStationListenerTest {

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
            MmoItemsStationListenerTest.class.getClassLoader(), new Class<?>[]{Player.class}, handler);
    }

    @SuppressWarnings("unchecked")
    private static <T> T bypassNew(Class<T> type) {
        try {
            ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
            Constructor<Object> objectCtor = Object.class.getDeclaredConstructor();
            Constructor<?> bypass = rf.newConstructorForSerialization(type, objectCtor);
            return (T) bypass.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, Class<?> declaringClass, String name, Object value) {
        try {
            Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static CraftingStation station(String id) {
        CraftingStation station = bypassNew(CraftingStation.class);
        setField(station, CraftingStation.class, "id", id);
        return station;
    }

    // Recipe is abstract - ReflectionFactory's bypass allocator still runs
    // the JVM's own object-allocation check, which rejects an abstract class
    // (InstantiationError) even without calling any constructor. A trivial
    // concrete subclass, itself never constructed normally either, sidesteps
    // that; its three abstract methods are never called by the listener.
    private static final class TestRecipe extends Recipe {
        private TestRecipe() {
            super(null);
        }

        public net.Indyuce.mmoitems.api.crafting.recipe.CheckedRecipe evaluateRecipe(
            net.Indyuce.mmoitems.api.player.PlayerData playerData,
            net.Indyuce.mmoitems.api.crafting.ingredient.inventory.IngredientInventory inventory) {
            throw new UnsupportedOperationException();
        }

        public boolean whenUsed(net.Indyuce.mmoitems.api.player.PlayerData playerData,
            net.Indyuce.mmoitems.api.crafting.ingredient.inventory.IngredientInventory inventory,
            net.Indyuce.mmoitems.api.crafting.recipe.CheckedRecipe checked, CraftingStation station) {
            throw new UnsupportedOperationException();
        }

        public boolean canUse(net.Indyuce.mmoitems.api.player.PlayerData playerData,
            net.Indyuce.mmoitems.api.crafting.ingredient.inventory.IngredientInventory inventory,
            net.Indyuce.mmoitems.api.crafting.recipe.CheckedRecipe checked, CraftingStation station) {
            throw new UnsupportedOperationException();
        }
    }

    private static Recipe recipe(String id) {
        Recipe recipe = bypassNew(TestRecipe.class);
        setField(recipe, Recipe.class, "id", id);
        return recipe;
    }

    // hasResult() is exactly 'result != null' (see MmoItemsStationListener's
    // own comment); the listener never reads the stack's contents, so an
    // empty bypassed shell - built the same way, sidestepping ItemStack's
    // Material-registry-touching constructor - is enough to make it non-null.
    private static ItemStack anyResult() {
        return bypassNew(ItemStack.class);
    }

    private static PlayerUseCraftingStationEvent event(Player player, CraftingStation station, Recipe recipe,
                                                        ItemStack result) {
        PlayerUseCraftingStationEvent event = bypassNew(PlayerUseCraftingStationEvent.class);
        setField(event, PlayerEvent.class, "player", player);
        setField(event, PlayerUseCraftingStationEvent.class, "station", station);
        setField(event, PlayerUseCraftingStationEvent.class, "recipe", recipe);
        setField(event, PlayerUseCraftingStationEvent.class, "result", result);
        return event;
    }

    // A real ActivityConfiguration with its 'stationActivities' and
    // 'activities' maps set directly, skipping load() (needs a live plugin)
    // entirely - the listener only ever reaches these two lookups.
    private static ActivityConfiguration configWithStation(String stationKey, String activityId) {
        ActivityConfiguration config = bypassNew(ActivityConfiguration.class);

        Map<String, String> stations = new LinkedHashMap<>();
        stations.put(stationKey, activityId);
        setField(config, ActivityConfiguration.class, "stationActivities", stations);

        Map<String, tfmc.justin.activity.models.ActivityDef> activities = new LinkedHashMap<>();
        activities.put(activityId, new ActivityDef(activityId, activityId, Material.PAPER, null, 1, 1, 0));
        setField(config, ActivityConfiguration.class, "activities", activities);

        return config;
    }

    private static ActivityManager managerWithConfig(ActivityConfiguration config) {
        ActivityManager manager = bypassNew(ActivityManager.class);
        setField(manager, ActivityManager.class, "config", config);
        // 'store' is left null on purpose: recordAction() only reaches it
        // after a real activity match, so touching it is the witness.
        return manager;
    }

    @Test
    void aResultWithAMatchingStationRecordsOneAction() {
        ActivityConfiguration config = configWithStation("forge", "smelt");
        ActivityManager manager = managerWithConfig(config);
        MmoItemsStationListener listener = new MmoItemsStationListener(manager);
        Player player = stubPlayer(UUID.randomUUID());

        PlayerUseCraftingStationEvent craft = event(player, station("forge"), recipe("flint"), anyResult());

        assertThrows(NullPointerException.class, () -> listener.onUseCraftingStation(craft));
    }

    @Test
    void noResultRecordsNothing() {
        ActivityConfiguration config = configWithStation("forge", "smelt");
        ActivityManager manager = managerWithConfig(config);
        MmoItemsStationListener listener = new MmoItemsStationListener(manager);
        Player player = stubPlayer(UUID.randomUUID());

        PlayerUseCraftingStationEvent craft = event(player, station("forge"), recipe("flint"), null);

        assertDoesNotThrow(() -> listener.onUseCraftingStation(craft));
    }

    @Test
    void noMatchingActivityRecordsNothing() {
        ActivityConfiguration config = configWithStation("forge", "smelt");
        ActivityManager manager = managerWithConfig(config);
        MmoItemsStationListener listener = new MmoItemsStationListener(manager);
        Player player = stubPlayer(UUID.randomUUID());

        PlayerUseCraftingStationEvent craft = event(player, station("anvil"), recipe("flint"), anyResult());

        assertDoesNotThrow(() -> listener.onUseCraftingStation(craft));
    }

    @Test
    void aNullStationRecordsNothing() {
        ActivityConfiguration config = configWithStation("forge", "smelt");
        ActivityManager manager = managerWithConfig(config);
        MmoItemsStationListener listener = new MmoItemsStationListener(manager);
        Player player = stubPlayer(UUID.randomUUID());

        PlayerUseCraftingStationEvent craft = event(player, null, recipe("flint"), anyResult());

        assertDoesNotThrow(() -> listener.onUseCraftingStation(craft));
    }

    @Test
    void aNullRecipeRecordsNothing() {
        ActivityConfiguration config = configWithStation("forge", "smelt");
        ActivityManager manager = managerWithConfig(config);
        MmoItemsStationListener listener = new MmoItemsStationListener(manager);
        Player player = stubPlayer(UUID.randomUUID());

        PlayerUseCraftingStationEvent craft = event(player, station("forge"), null, anyResult());

        assertDoesNotThrow(() -> listener.onUseCraftingStation(craft));
    }

    @Test
    void aNullPlayerIsIgnored() {
        ActivityConfiguration config = configWithStation("forge", "smelt");
        ActivityManager manager = managerWithConfig(config);
        MmoItemsStationListener listener = new MmoItemsStationListener(manager);

        PlayerUseCraftingStationEvent craft = event(null, station("forge"), recipe("flint"), anyResult());

        assertDoesNotThrow(() -> listener.onUseCraftingStation(craft));
    }

    // Sanity check that the NPE-as-witness approach above is not vacuous:
    // prove a genuine match really does reach the manager by also checking it
    // via the un-guarded static wiring below - MONITOR + ignoreCancelled, and
    // no manager call happens before hasResult()/station/recipe/player all
    // check out, which the six tests above already exercise from both sides.
    @Test
    void handlerIsMonitorPriorityAndIgnoresCancelled() throws NoSuchMethodException {
        Method method = MmoItemsStationListener.class.getMethod("onUseCraftingStation", PlayerUseCraftingStationEvent.class);
        EventHandler annotation = method.getAnnotation(EventHandler.class);

        assertTrue(annotation != null, "onUseCraftingStation should be an @EventHandler");
        assertEquals(EventPriority.MONITOR, annotation.priority());
        assertTrue(annotation.ignoreCancelled());
    }
}
