package net.tfminecraft.activitytf.listeners;

import com.dre.brewery.BIngredients;
import com.dre.brewery.BreweryPlugin;
import com.dre.brewery.configuration.ConfigManager;
import com.dre.brewery.configuration.files.Config;
import com.dre.brewery.configuration.files.Lang;
import com.dre.brewery.utility.MinecraftVersion;
import com.dre.brewery.Barrel;
import com.dre.brewery.Brew;
import com.dre.brewery.api.events.IngedientAddEvent;
import com.dre.brewery.api.events.brew.BrewModifyEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockito.MockedStatic;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.managers.TestManagers;
import net.tfminecraft.activitytf.models.ActivityDef;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreweryListenerHandlersTest {

    // BreweryListenerTest calls the handlers directly, which skips Bukkit's ignoreCancelled filter.
    @Test
    void everyHandlerSkipsCancelledEventsAtMonitor() {
        int handlers = 0;
        for (Method method : BreweryListener.class.getDeclaredMethods()) {
            EventHandler handler = method.getAnnotation(EventHandler.class);
            if (handler == null) {
                continue;
            }
            handlers++;
            assertTrue(handler.ignoreCancelled(), method.getName() + " must ignore cancelled events");
            assertEquals(EventPriority.MONITOR, handler.priority(), method.getName());
        }
        assertTrue(handlers > 0);
    }

    @Nested
    @ExtendWith(FailUnimplemented.class)
    class RuntimeEvents {
        @TempDir Path directory;
        Server previousServer;
        ServerMock server;
        JavaPlugin plugin;
        PlayerMock player;
        ActivityManager manager;
        Brew brew;
        MockedStatic<Brew> brews;
        MockedStatic<BreweryPlugin> breweryPlugins;
        MockedStatic<ConfigManager> breweryConfigs;
        MockedStatic<JavaPlugin> providers;

        @BeforeEach
        void startServer() throws Exception {
            previousServer = Bukkit.getServer();
            serverField().set(null, null);
            server = MockBukkit.mock();
            plugin = MockBukkit.createMockPlugin("BreweryListenerTest");
            player = server.addPlayer("Steve");
            manager = TestManagers.manager(
                activity("brew_ingredient"), activity("brew_bottle"), activity("brew_distill"), activity("brew_age"));
            TestManagers.limits(manager, 100, 100);
            TestManagers.messages(manager);
            TestManagers.storeLoaded(manager);
            TestManagers.storeFile(manager, directory.resolve("players.yml").toFile());
            reveal(player);
            server.getPluginManager().registerEvents(new BreweryListener(manager), plugin);
            // Brewery caches its configuration while Brew initializes, before our API lookup stub exists.
            BreweryPlugin brewery = mock(BreweryPlugin.class);
            when(brewery.getDataFolder()).thenReturn(directory.toFile());
            breweryPlugins = mockStatic(BreweryPlugin.class);
            breweryPlugins.when(BreweryPlugin::getInstance).thenReturn(brewery);
            breweryPlugins.when(BreweryPlugin::getMCVersion).thenReturn(MinecraftVersion.V1_21_10);
            breweryConfigs = mockStatic(ConfigManager.class);
            Config config = new Config();
            Lang lang = new Lang();
            breweryConfigs.when(() -> ConfigManager.getConfig(Config.class)).thenReturn(config);
            breweryConfigs.when(() -> ConfigManager.getConfig(Lang.class)).thenReturn(lang);
            brew = mock(Brew.class);
            when(brew.getDistillRuns()).thenReturn((byte) 1);
            when(brew.getAgeTime()).thenReturn(1.0f);
            brews = mockStatic(Brew.class);
            brews.when(() -> Brew.get(any(ItemStack.class))).thenAnswer(call -> brew);
            // MockBukkit loads plugin classes through proxies; bridge the Paper classloader lookup only.
            providers = mockStatic(JavaPlugin.class, CALLS_REAL_METHODS);
            providers.when(() -> JavaPlugin.getProvidingPlugin(BreweryListener.class)).thenReturn(plugin);
        }

        @AfterEach
        void stopServer() throws Throwable {
            Throwable failure = null;
            for (AutoCloseable resource : new AutoCloseable[] {providers, brews, breweryConfigs,
                    breweryPlugins, MockBukkit::unmock, () -> serverField().set(null, previousServer)}) {
                try {
                    if (resource != null) resource.close();
                } catch (Throwable next) {
                    if (failure == null) failure = next;
                    else failure.addSuppressed(next);
                }
            }
            if (failure != null) throw failure;
        }

        void reveal(PlayerMock who) {
            for (int slot = 0; slot < manager.tasks(who.getUniqueId()).tasks().size(); slot++) {
                manager.reveal(who.getUniqueId(), slot);
            }
        }

        int count(PlayerMock who, String id) { return manager.getStore().peek(who.getUniqueId()).count(id); }

        Inventory brewing() { return new SnapshotInventory(null, InventoryType.BREWING); }
        Inventory barrel() { return new SnapshotInventory(mock(Barrel.class), InventoryType.CHEST); }

        InventoryClickEvent click(PlayerMock who, Inventory inventory, int rawSlot, InventoryAction action, boolean cancelled) {
            who.openInventory(inventory);
            InventoryClickEvent event = new InventoryClickEvent(who.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                rawSlot, ClickType.LEFT, action);
            event.setCancelled(cancelled);
            server.getPluginManager().callEvent(event);
            return event;
        }

        InventoryClickEvent take(PlayerMock who, Inventory inventory) {
            return click(who, inventory, 0, InventoryAction.PICKUP_ALL, false);
        }

        ItemStack remove(Inventory inventory) {
            ItemStack item = inventory.getItem(0).clone();
            inventory.setItem(0, null);
            return item;
        }

        Long tag(ItemStack item, NamespacedKey key) {
            return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.LONG);
        }

        @Test
        void ingredientEventsCreditOnlyUncancelledPlayerActions() {
            IngedientAddEvent cancelled = new IngedientAddEvent(player, null, null, new ItemStack(Material.WHEAT), null);
            cancelled.setCancelled(true);
            server.getPluginManager().callEvent(cancelled);
            assertEquals(0, count(player, "brew_ingredient"));
            server.getPluginManager().callEvent(new IngedientAddEvent(player, null, null, new ItemStack(Material.WHEAT), null));
            server.getPluginManager().callEvent(new IngedientAddEvent(null, null, null, new ItemStack(Material.WHEAT), null));
            assertEquals(1, count(player, "brew_ingredient"));
        }

        @Test
        void fillEventsRequireACookedBrewAndIgnoreCancelledAndOtherModifications() {
            BIngredients ingredients = mock(BIngredients.class);
            when(brew.getIngredients()).thenReturn(ingredients);
            var meta = new ItemStack(Material.POTION).getItemMeta();
            server.getPluginManager().callEvent(new BrewModifyEvent(brew, meta, BrewModifyEvent.Type.DISTILL, player));
            server.getPluginManager().callEvent(new BrewModifyEvent(null, meta, BrewModifyEvent.Type.FILL, player));
            server.getPluginManager().callEvent(new BrewModifyEvent(brew, meta, BrewModifyEvent.Type.FILL, player));
            when(brew.getIngredients()).thenReturn(null);
            server.getPluginManager().callEvent(new BrewModifyEvent(brew, meta, BrewModifyEvent.Type.FILL, player));
            when(brew.getIngredients()).thenReturn(ingredients);
            when(ingredients.getCookedTime()).thenReturn(1);
            BrewModifyEvent cancelled = new BrewModifyEvent(brew, meta, BrewModifyEvent.Type.FILL, player);
            cancelled.setCancelled(true);
            server.getPluginManager().callEvent(cancelled);
            server.getPluginManager().callEvent(new BrewModifyEvent(brew, meta, BrewModifyEvent.Type.FILL));
            assertEquals(0, count(player, "brew_bottle"));
            server.getPluginManager().callEvent(new BrewModifyEvent(brew, meta, BrewModifyEvent.Type.FILL, player));
            when(ingredients.getCookedTime()).thenReturn(12);
            server.getPluginManager().callEvent(new BrewModifyEvent(brew, meta, BrewModifyEvent.Type.FILL, player));
            assertEquals(2, count(player, "brew_bottle"));
        }

        @Test
        void aRemovedDistilledBottleIsCreditedOnTheNextTickOnlyOnce() {
            Inventory inventory = brewing();
            inventory.setItem(0, new ItemStack(Material.POTION));
            take(player, inventory);
            assertEquals(0, count(player, "brew_distill"));
            ItemStack taken = remove(inventory);
            assertNotNull(tag(taken, BreweryListener.DISTILLED));

            server.getScheduler().performOneTick();

            assertEquals(1, count(player, "brew_distill"));
            inventory.setItem(0, taken);
            take(player, inventory);
            remove(inventory);
            server.getScheduler().performOneTick();
            assertEquals(1, count(player, "brew_distill"));
        }

        @Test
        void aBottleThatDidNotMoveLosesItsPendingTagAndCanBeTakenLater() {
            Inventory inventory = brewing();
            inventory.setItem(0, new ItemStack(Material.POTION));
            click(player, inventory, 0, InventoryAction.MOVE_TO_OTHER_INVENTORY, false);
            assertNotNull(tag(inventory.getItem(0), BreweryListener.DISTILLED));

            server.getScheduler().performOneTick();

            assertEquals(0, count(player, "brew_distill"));
            assertNull(tag(inventory.getItem(0), BreweryListener.DISTILLED));
            take(player, inventory);
            remove(inventory);
            server.getScheduler().performOneTick();
            assertEquals(1, count(player, "brew_distill"));
        }

        @Test
        void aSecondTakeBeforeConfirmationCreditsOnlyThePlayerWhoActuallyRemovedIt() {
            PlayerMock other = server.addPlayer("Alex");
            reveal(other);
            Inventory inventory = brewing();
            inventory.setItem(0, new ItemStack(Material.POTION));
            take(player, inventory);
            Long first = tag(inventory.getItem(0), BreweryListener.DISTILLED);
            take(other, inventory);
            Long second = tag(inventory.getItem(0), BreweryListener.DISTILLED);
            assertFalse(first.equals(second));
            remove(inventory);

            server.getScheduler().performOneTick();

            assertEquals(0, count(player, "brew_distill"));
            assertEquals(1, count(other, "brew_distill"));
        }

        @Test
        void agedAndDistilledCreditUseSeparatePersistentTags() {
            Inventory aged = barrel();
            aged.setItem(0, new ItemStack(Material.POTION));
            take(player, aged);
            ItemStack bottle = remove(aged);
            server.getScheduler().performOneTick();
            assertEquals(1, count(player, "brew_age"));
            assertNotNull(tag(bottle, BreweryListener.AGED));
            Inventory distilled = brewing();
            distilled.setItem(0, bottle);
            take(player, distilled);
            bottle = remove(distilled);
            server.getScheduler().performOneTick();
            assertEquals(1, count(player, "brew_age"));
            assertEquals(1, count(player, "brew_distill"));
            assertNotNull(tag(bottle, BreweryListener.AGED));
            assertNotNull(tag(bottle, BreweryListener.DISTILLED));
        }

        @Test
        void replacingTheSourceSlotDoesNotEraseTheReplacementItemsMetadata() {
            Inventory inventory = brewing();
            inventory.setItem(0, new ItemStack(Material.POTION));
            take(player, inventory);
            ItemStack replacement = new ItemStack(Material.PAPER);
            var meta = replacement.getItemMeta();
            meta.setDisplayName("Replacement");
            replacement.setItemMeta(meta);
            inventory.setItem(0, replacement);

            server.getScheduler().performOneTick();

            assertEquals(1, count(player, "brew_distill"));
            assertEquals(replacement, inventory.getItem(0));
        }

        @Test
        void aPotionWithUnavailableMetadataIsIgnoredWithoutAWriteOrCredit() {
            Inventory inventory = brewing();
            inventory.setItem(0, new ItemStack(Material.POTION));
            player.openInventory(inventory);
            ItemStack incomplete = mock(ItemStack.class);
            when(incomplete.getType()).thenReturn(Material.POTION);
            when(incomplete.getItemMeta()).thenReturn(null);
            InventoryClickEvent event = spy(new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL));
            // getItemMeta is nullable at the Bukkit boundary, even though ordinary potions supply it.
            doReturn(incomplete).when(event).getCurrentItem();

            server.getPluginManager().callEvent(event);
            server.getScheduler().performOneTick();

            verify(incomplete, never()).setItemMeta(any());
            assertNull(tag(inventory.getItem(0), BreweryListener.DISTILLED));
            assertEquals(0, count(player, "brew_distill"));
        }

        @Test
        void cancelledNonTakingOutsideBottomAndNonBrewClicksNeverEarnCredit() {
            Inventory inventory = brewing();
            inventory.setItem(0, new ItemStack(Material.POTION));
            click(player, inventory, 0, InventoryAction.PICKUP_ALL, true);
            click(player, inventory, 0, InventoryAction.PLACE_ALL, false);
            click(player, inventory, -999, InventoryAction.PICKUP_ALL, false);
            click(player, inventory, inventory.getSize(), InventoryAction.PICKUP_ALL, false);
            inventory.setItem(0, null);
            take(player, inventory);
            inventory.setItem(0, new ItemStack(Material.PAPER));
            take(player, inventory);
            inventory.setItem(3, new ItemStack(Material.POTION));
            click(player, inventory, 3, InventoryAction.PICKUP_ALL, false);
            inventory.setItem(0, new ItemStack(Material.POTION));
            when(brew.getDistillRuns()).thenReturn((byte) 0);
            take(player, inventory);
            Brew known = brew;
            brew = null;
            take(player, inventory);
            Inventory aged = barrel();
            aged.setItem(0, new ItemStack(Material.POTION));
            take(player, aged);
            brew = known;
            when(brew.getAgeTime()).thenReturn(0.99f);
            take(player, aged);
            Inventory ordinary = new SnapshotInventory(null, InventoryType.CHEST);
            ordinary.setItem(0, new ItemStack(Material.POTION));
            take(player, ordinary);

            server.getScheduler().performOneTick();

            assertEquals(0, count(player, "brew_distill"));
            assertEquals(0, count(player, "brew_age"));
            assertNull(tag(inventory.getItem(0), BreweryListener.DISTILLED));
            assertNull(tag(aged.getItem(0), BreweryListener.AGED));
        }
    }

    private static ActivityDef activity(String id) { return new ActivityDef(id, id, Material.POTION, null, 1, 1, 0); }

    /** MockBukkit has no getHolder(boolean) implementation; these live inventories have no block snapshot. */
    private static final class SnapshotInventory extends InventoryMock {
        SnapshotInventory(InventoryHolder holder, InventoryType type) { super(holder, type); }
        @Override public InventoryHolder getHolder(boolean useSnapshot) { return getHolder(); }
    }

    private static Field serverField() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        return field;
    }

    static final class FailUnimplemented implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {
        @Override public void handleTestExecutionException(ExtensionContext context, Throwable thrown) throws Throwable { throw failed(thrown); }
        @Override public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable thrown) throws Throwable { throw failed(thrown); }
        private static Throwable failed(Throwable thrown) {
            return thrown instanceof UnimplementedOperationException
                ? new AssertionError("MockBukkit does not implement a required Brewery event operation", thrown) : thrown;
        }
    }
}
