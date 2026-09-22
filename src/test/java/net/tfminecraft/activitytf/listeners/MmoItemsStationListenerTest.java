package net.tfminecraft.activitytf.listeners;

import net.Indyuce.mmoitems.api.crafting.CraftingStation;
import net.Indyuce.mmoitems.api.crafting.recipe.Recipe;
import net.Indyuce.mmoitems.api.event.PlayerUseCraftingStationEvent;
import net.Indyuce.mmoitems.api.event.PlayerUseCraftingStationEvent.StationAction;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.objenesis.ObjenesisStd;
import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.models.ActivityDef;

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

    private static <T> T bypassNew(Class<T> type) {
        return new ObjenesisStd().newInstance(type);
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

    private static ItemStack anyResult() {
        return bypassNew(ItemStack.class);
    }

    private static PlayerUseCraftingStationEvent event(Player player, CraftingStation station, Recipe recipe,
                                                        ItemStack result) {
        return event(player, station, recipe, result, StationAction.INSTANT_RECIPE);
    }

    private static PlayerUseCraftingStationEvent event(Player player, CraftingStation station, Recipe recipe,
                                                        ItemStack result, StationAction action) {
        PlayerUseCraftingStationEvent event = bypassNew(PlayerUseCraftingStationEvent.class);
        setField(event, PlayerEvent.class, "player", player);
        setField(event, PlayerUseCraftingStationEvent.class, "station", station);
        setField(event, PlayerUseCraftingStationEvent.class, "recipe", recipe);
        setField(event, PlayerUseCraftingStationEvent.class, "result", result);
        setField(event, PlayerUseCraftingStationEvent.class, "action", action);
        return event;
    }

    private static ActivityConfiguration configWithStation(String stationKey, String activityId) {
        ActivityConfiguration config = bypassNew(ActivityConfiguration.class);

        Map<String, String> stations = new LinkedHashMap<>();
        stations.put(stationKey, activityId);
        setField(config, ActivityConfiguration.class, "stationActivities", stations);

        Map<String, net.tfminecraft.activitytf.models.ActivityDef> activities = new LinkedHashMap<>();
        activities.put(activityId, new ActivityDef(activityId, activityId, Material.PAPER, null, 1, 1, 0));
        setField(config, ActivityConfiguration.class, "activities", activities);

        return config;
    }

    private static ActivityManager managerWithConfig(ActivityConfiguration config) {
        ActivityManager manager = bypassNew(ActivityManager.class);
        setField(manager, ActivityManager.class, "config", config);
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

    @ParameterizedTest
    @EnumSource(value = StationAction.class, names = {"INSTANT_RECIPE", "CRAFTING_QUEUE"})
    void aCompletedCraftWithoutAnOutputItemStillRecordsOneAction(StationAction action) {
        ActivityConfiguration config = configWithStation("forge", "smelt");
        ActivityManager manager = managerWithConfig(config);
        MmoItemsStationListener listener = new MmoItemsStationListener(manager);
        Player player = stubPlayer(UUID.randomUUID());

        PlayerUseCraftingStationEvent craft = event(player, station("forge"), recipe("flint"), null, action);

        assertThrows(NullPointerException.class, () -> listener.onUseCraftingStation(craft));
    }

    @ParameterizedTest
    @EnumSource(value = StationAction.class, names = {"INTERACT_WITH_RECIPE", "CANCEL_QUEUE", "UPGRADE_RECIPE"})
    void nonCraftInteractionsRecordNothing(StationAction action) {
        ActivityConfiguration config = configWithStation("forge", "smelt");
        ActivityManager manager = managerWithConfig(config);
        MmoItemsStationListener listener = new MmoItemsStationListener(manager);
        Player player = stubPlayer(UUID.randomUUID());

        PlayerUseCraftingStationEvent craft = event(player, station("forge"), recipe("flint"), anyResult(), action);

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

    @Test
    void handlerIsMonitorPriorityAndIgnoresCancelled() throws NoSuchMethodException {
        Method method = MmoItemsStationListener.class.getMethod("onUseCraftingStation", PlayerUseCraftingStationEvent.class);
        EventHandler annotation = method.getAnnotation(EventHandler.class);

        assertTrue(annotation != null, "onUseCraftingStation should be an @EventHandler");
        assertEquals(EventPriority.MONITOR, annotation.priority());
        assertTrue(annotation.ignoreCancelled());
    }
    @Test
    void collectingCommandRewardFishingRodUpdatesTheRevealedTask() {
        var def = new ActivityDef("fishing_rod", "Fishing", Material.FISHING_ROD, null, 1, 1, 1);
        var manager = net.tfminecraft.activitytf.managers.TestManagers.manager(def);
        net.tfminecraft.activitytf.managers.TestManagers.bukkit();
        net.tfminecraft.activitytf.managers.TestManagers.storeLoaded(manager);
        net.tfminecraft.activitytf.managers.TestManagers.guarantee(manager, def.id());
        setField(manager.getConfiguration(), ActivityConfiguration.class, "stationActivities",
            Map.of("fishing-station/fishing-rod", "fishing_rod"));
        UUID uuid = UUID.randomUUID();
        manager.reveal(uuid, 0);
        var listener = new MmoItemsStationListener(manager);
        var player = stubPlayer(uuid);
        listener.onUseCraftingStation(event(player, station("fishing-station"), recipe("fishing-rod"),
            null, StationAction.INTERACT_WITH_RECIPE));
        org.junit.jupiter.api.Assertions.assertEquals(0, manager.tasks(uuid).count(def.id()));
        listener.onUseCraftingStation(event(player, station("fishing-station"), recipe("fishing-rod"),
            null, StationAction.CRAFTING_QUEUE));
        org.junit.jupiter.api.Assertions.assertEquals(1, manager.tasks(uuid).count(def.id()));
        org.junit.jupiter.api.Assertions.assertEquals(1, manager.tasks(uuid).points());
    }

}
