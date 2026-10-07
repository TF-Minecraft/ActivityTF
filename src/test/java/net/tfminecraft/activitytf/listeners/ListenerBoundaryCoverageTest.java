package net.tfminecraft.activitytf.listeners;

import com.bencodez.votingplugin.events.PlayerVoteEvent;
import com.bencodez.votingplugin.user.VotingPluginUser;
import net.Indyuce.mmocore.api.event.PlayerExperienceGainEvent;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.experience.EXPSource;
import net.Indyuce.mmocore.experience.Profession;
import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.models.Recorded;
import net.tfminecraft.advancedcrafting.lifecycle.AlloyCraftedEvent;
import net.tfminecraft.advancedcrafting.lifecycle.ItemCraftedEvent;
import net.tfminecraft.geigercounters.events.GeigerSourceCollectEvent;
import net.tfminecraft.interactiblefurniture.events.FurniturePlaceEvent;
import net.tfminecraft.musicalinstruments.events.InstrumentPlayEvent;
import net.tfminecraft.rpcharacters.chat.CharacterChatEvent;
import net.tfminecraft.rpcharacters.injuries.CharacterInjuredEvent;
import net.tfminecraft.rpcharacters.professions.ProfessionUpgradePurchasedEvent;
import net.tfminecraft.simplefactions.war.battle.enums.BattleType;
import net.tfminecraft.simplefactions.war.battle.events.BattleEndedEvent;
import net.tfminecraft.vfbuilders.events.VehicleConstructEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(ListenerBoundaryCoverageTest.FailUnimplemented.class)
class ListenerBoundaryCoverageTest {
    record Credit(UUID player, String activity, int amount) {}

    Server previousServer;
    ServerMock server;
    JavaPlugin plugin;
    PlayerMock player;
    ActivityManager manager;
    ActivityConfiguration configuration;
    final List<Credit> credits = new ArrayList<>();
    final List<Player> joined = new ArrayList<>();
    final List<String> logs = new ArrayList<>();
    final Map<Material, String> crafts = new HashMap<>();
    final Map<String, String> professions = new HashMap<>();
    final Set<String> untracked = new HashSet<>();
    ActivityConfiguration.Keys keys = new ActivityConfiguration.Keys("2026-10-05", "2026-10-07");

    @BeforeEach
    void setUp() throws Exception {
        previousServer = Bukkit.getServer();
        installServer(null);
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("ListenerBoundaryTest");
        plugin.getLogger().addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        player = server.addPlayer("Crafter");
        manager = mock(ActivityManager.class);
        configuration = mock(ActivityConfiguration.class);
        when(manager.getConfiguration()).thenReturn(configuration);
        when(configuration.craftActivity(any(ItemStack.class))).thenAnswer(call ->
            crafts.get(((ItemStack) call.getArgument(0)).getType()));
        when(configuration.professionActivity(anyString())).thenAnswer(call -> professions.get(call.getArgument(0)));
        when(configuration.currentKeys()).thenAnswer(call -> keys);
        when(manager.isTracked(any(UUID.class), anyString())).thenAnswer(call ->
            !untracked.contains(call.getArgument(1)));
        doAnswer(call -> {
            credits.add(new Credit(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            return Recorded.RECORDED;
        }).when(manager).recordAction(any(UUID.class), anyString(), anyInt());
        doAnswer(call -> { joined.add(call.getArgument(0)); return null; }).when(manager).onJoin(any(Player.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            MockBukkit.unmock();
        } finally {
            installServer(previousServer);
        }
    }

    private static void installServer(Server server) throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, server);
    }

    void listen(Listener listener) {
        server.getPluginManager().registerEvents(listener, plugin);
    }

    void fire(Event event) {
        server.getPluginManager().callEvent(event);
    }

    Credit credit(String activity, int amount) {
        return new Credit(player.getUniqueId(), activity, amount);
    }

    @Test
    void furniturePlacementCreditsThePlacerOnlyAfterSuccessfulPlacement() {
        listen(new FurnitureListener(manager));
        FurniturePlaceEvent cancelled = new FurniturePlaceEvent(null, player);
        cancelled.setCancelled(true);
        fire(cancelled);
        fire(new FurniturePlaceEvent(null, null));
        assertTrue(credits.isEmpty());

        fire(new FurniturePlaceEvent(null, player));
        assertEquals(List.of(credit("furniture_place", 1)), credits);
    }

    @Test
    void endedBattleCreditsEveryParticipantOnceIncludingOfflinePlayers() {
        listen(new BattleListener(manager));
        UUID offline = UUID.randomUUID();
        fire(new BattleEndedEvent("empty", BattleType.FIELD, null, null, Map.of(), null));
        assertTrue(credits.isEmpty());
        fire(new BattleEndedEvent("siege", BattleType.SIEGE, 4, "defenders", Map.of("attackers", 3),
            Set.of(player.getUniqueId(), offline)));
        assertEquals(Set.of(credit("battle_joined", 1), new Credit(offline, "battle_joined", 1)),
            Set.copyOf(credits));
        assertEquals(2, credits.size());
    }

    @Test
    void advancedCraftAndAlloyCreditTheirRecordedOwnerWithoutAnOnlinePlayer() {
        listen(new AdvancedCraftListener(manager));
        UUID offline = UUID.randomUUID();
        fire(new ItemCraftedEvent(player, null, "test", "metal"));
        fire(new AlloyCraftedEvent(null, null, "steel"));
        assertTrue(credits.isEmpty());
        fire(new ItemCraftedEvent(null, offline, "sword", "metal"));
        fire(new AlloyCraftedEvent(player, player.getUniqueId(), "steel"));
        assertEquals(List.of(new Credit(offline, "advcraft_item", 1), credit("advcraft_item", 1)), credits);
    }

    @Test
    void vehicleConstructionCreditsTheRecordedConstructorEvenAfterLogout() {
        listen(new VehicleBuildListener(manager));
        UUID offline = UUID.randomUUID();
        fire(new VehicleConstructEvent(null, null, null, null, null));
        assertTrue(credits.isEmpty());
        fire(new VehicleConstructEvent(offline, null, null, player.getLocation(), null));
        assertEquals(List.of(new Credit(offline, "vehicle_build", 1)), credits);
    }

    @Test
    void instrumentProfessionAndGeigerEventsCreditOneActionEach() {
        listen(new InstrumentListener(manager));
        listen(new ProfessionUpgradeListener(manager));
        listen(new GeigerListener(manager));
        fire(new InstrumentPlayEvent(null, "flute", "block.note_block.flute"));
        fire(new ProfessionUpgradePurchasedEvent(null, null, "smithing", 25));
        fire(new GeigerSourceCollectEvent(null, player.getLocation()));
        assertTrue(credits.isEmpty());
        fire(new InstrumentPlayEvent(player, "flute", "block.note_block.flute"));
        fire(new ProfessionUpgradePurchasedEvent(player, null, "smithing", 25));
        fire(new GeigerSourceCollectEvent(player, player.getLocation()));
        assertEquals(List.of(credit("instrument", 1), credit("profession_upgrade", 1), credit("geiger", 1)), credits);
    }

    @Test
    void injuryCreditsTheVictimAndJoinPassesTheJoiningPlayer() {
        listen(new InjuryListener(manager));
        listen(new JoinListener(manager));
        PlayerMock attacker = server.addPlayer("Attacker");
        joined.clear();
        fire(new CharacterInjuredEvent(null, attacker, null, "broken_arm"));
        assertTrue(credits.isEmpty());
        fire(new CharacterInjuredEvent(player, attacker, null, "broken_arm"));
        fire(new PlayerJoinEvent(player, "welcome"));
        assertEquals(List.of(credit("injured", 1)), credits);
        assertEquals(List.of(player), joined);
    }

    @Test
    void cancelledAndMalformedChatCannotPolluteTheValidRoleplayHistory() {
        CharacterChatListener listener = new CharacterChatListener(manager);
        listen(listener);
        String text = "I carefully open the weathered wooden door";
        CharacterChatEvent cancelled = chat(player, "rp", text);
        cancelled.setCancelled(true);
        fire(cancelled);
        fire(chat(null, "rp", text));
        fire(chat(player, "rp", null));
        fire(chat(player, "ooc", text));
        assertEquals(0, listener.historySize(player.getUniqueId()));
        assertTrue(credits.isEmpty());
        fire(chat(player, "rp", text));
        fire(chat(player, "rp", text));
        assertEquals(List.of(credit("ic_chat", 1)), credits);
        assertEquals(2, listener.historySize(player.getUniqueId()));
    }

    CharacterChatEvent chat(Player sender, String channel, String text) {
        return new CharacterChatEvent(sender, null, channel, text, "Character", Set.of(player), false, false);
    }

    @Test
    void realVotesCreditOnTheMainTickUsingTheUsersUuidBeforeTheDisplayName() {
        VoteListener listener = new VoteListener(plugin, manager);
        PlayerVoteEvent event = vote("not the username", true);
        VotingPluginUser user = mock(VotingPluginUser.class);
        when(user.getJavaUUID()).thenReturn(player.getUniqueId());
        event.setVotingPluginUser(user);

        CompletableFuture.runAsync(() -> listener.onVote(event)).join();

        assertTrue(credits.isEmpty());
        server.getScheduler().performOneTick();
        assertEquals(List.of(credit("vote", 1)), credits);
        server.getScheduler().performOneTick();
        assertEquals(1, credits.size());
    }

    @Test
    void votesWithoutUuidResolveOnlineAndPreviouslySeenOfflinePlayers() {
        VoteListener listener = new VoteListener(plugin, manager);
        PlayerMock offline = server.addPlayer("OfflineVoter");
        assertTrue(offline.disconnect());
        listener.onVote(vote(player.getName(), true));
        PlayerVoteEvent cached = vote(offline.getName(), true);
        cached.setVotingPluginUser(mock(VotingPluginUser.class));
        listener.onVote(cached);
        assertTrue(credits.isEmpty());

        server.getScheduler().performOneTick();

        assertEquals(List.of(credit("vote", 1), new Credit(offline.getUniqueId(), "vote", 1)), credits);
    }

    @Test
    void cancelledSyntheticAndDisabledPluginVotesDoNotScheduleCredits() {
        VoteListener listener = new VoteListener(plugin, manager);
        PlayerVoteEvent cancelled = vote(player.getName(), true);
        cancelled.setCancelled(true);
        listener.onVote(cancelled);
        listener.onVote(vote(player.getName(), false));
        server.getPluginManager().disablePlugin(plugin);
        listener.onVote(vote(player.getName(), true));
        server.getScheduler().performOneTick();
        assertTrue(credits.isEmpty());
    }

    @Test
    void unknownOrMissingVoterNamesAreRejectedWithoutCreatingOfflineProfiles() {
        VoteListener listener = new VoteListener(plugin, manager);
        OfflinePlayer neverJoined = mock(OfflinePlayer.class);
        when(neverJoined.getName()).thenReturn("NeverJoined");
        server.getPlayerList().addOfflinePlayer(neverJoined);
        int cachedPlayers = server.getOfflinePlayers().length;
        listener.onVote(vote(null, true));
        listener.onVote(vote("  ", true));
        listener.onVote(vote("Ghost\nName", true));
        listener.onVote(vote("NeverJoined", true));
        server.getScheduler().performOneTick();
        assertTrue(credits.isEmpty());
        assertEquals(cachedPlayers, server.getOfflinePlayers().length);
        assertTrue(logs.stream().anyMatch(line -> line.contains("neither a user nor a player name")), logs.toString());
        assertTrue(logs.stream().anyMatch(line -> line.contains("Ghost?Name")), logs.toString());
    }

    @Test
    void schedulerRejectionDuringShutdownDoesNotCreditOrEscapeTheVoteHandler() {
        VoteListener listener = new VoteListener(plugin, manager);
        for (RuntimeException failure : List.of(new IllegalPluginAccessException("disabled while scheduling"),
                new IllegalStateException("scheduler shutting down"))) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            when(scheduler.runTask(any(JavaPlugin.class), any(Runnable.class))).thenThrow(failure);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                assertDoesNotThrow(() -> listener.onVote(vote(player.getName(), true)));
            }
        }
        server.getScheduler().performOneTick();
        assertTrue(credits.isEmpty());
    }

    PlayerVoteEvent vote(String name, boolean real) {
        return new PlayerVoteEvent(null, name, "TestVoteSite", real);
    }

    @Test
    void professionXpCombinesFractionsButNeverCreditsCancelledOrUnmappedXp() {
        listen(new ProfessionXpListener(manager));
        professions.put("mining", "profession_mining");
        fire(xp(player, null, 10));
        fire(xp(player, "unmapped", 10));
        fire(xp(null, "mining", 10));
        PlayerExperienceGainEvent cancelled = xp(player, "mining", 10);
        cancelled.setCancelled(true);
        fire(cancelled);
        assertTrue(credits.isEmpty());
        fire(xp(player, "mining", 0.6));
        assertTrue(credits.isEmpty());
        fire(xp(player, "mining", 0.6));
        fire(xp(player, "mining", 1.8));
        assertEquals(List.of(credit("profession_mining", 1), credit("profession_mining", 2)), credits);
    }

    @Test
    void professionFractionCarryIsForgottenWhenUntrackedOnQuitAndAcrossDays() {
        listen(new ProfessionXpListener(manager));
        professions.put("mining", "profession_mining");
        fire(xp(player, "mining", 0.8));
        untracked.add("profession_mining");
        fire(xp(player, "mining", 1));
        untracked.clear();
        fire(xp(player, "mining", 0.3));
        assertTrue(credits.isEmpty());
        fire(new PlayerQuitEvent(player, "bye"));
        fire(xp(player, "mining", 0.8));
        assertTrue(credits.isEmpty());
        keys = new ActivityConfiguration.Keys("2026-10-05", "2026-10-08");
        fire(xp(player, "mining", 0.3));
        assertTrue(credits.isEmpty());
        fire(xp(player, "mining", 0.7));
        assertEquals(List.of(credit("profession_mining", 1)), credits);
    }

    PlayerExperienceGainEvent xp(Player owner, String professionId, double amount) {
        PlayerData data = mock(PlayerData.class);
        when(data.getPlayer()).thenReturn(owner);
        Profession profession = null;
        if (professionId != null) {
            profession = mock(Profession.class);
            when(profession.getId()).thenReturn(professionId);
        }
        return new PlayerExperienceGainEvent(data, profession, amount, EXPSource.SOURCE);
    }

    @Test
    void normalCraftCreditsOneBatchAndShiftCraftUsesTheShortestIngredient() {
        listen(new CraftListener(manager));
        crafts.put(Material.TORCH, "craft_torch");
        ItemStack result = new ItemStack(Material.TORCH, 4);
        ItemStack[] matrix = {null, new ItemStack(Material.AIR), new ItemStack(Material.COAL, 7),
            new ItemStack(Material.STICK, 3)};
        fire(craft(player, result, matrix, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL, -1));
        fire(craft(player, result, matrix, 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, -1));
        assertEquals(List.of(credit("craft_torch", 4), credit("craft_torch", 12)), credits);
    }

    @Test
    void shiftCraftIsBoundedByMatchingStackSpaceAndNeedsACompleteBatch() {
        listen(new CraftListener(manager));
        crafts.put(Material.TORCH, "craft_torch");
        ItemStack[] full = new ItemStack[36];
        Arrays.setAll(full, index -> new ItemStack(Material.STONE, 64));
        full[0] = new ItemStack(Material.TORCH, 58);
        player.getInventory().setStorageContents(full);
        ItemStack result = new ItemStack(Material.TORCH, 4);
        ItemStack[] ingredients = {new ItemStack(Material.COAL, 16), new ItemStack(Material.STICK, 16)};
        fire(craft(player, result, ingredients, 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, -1));
        assertEquals(List.of(credit("craft_torch", 4)), credits);
        player.getInventory().setItem(0, new ItemStack(Material.TORCH, 63));
        fire(craft(player, result, ingredients, 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, -1));
        assertEquals(1, credits.size());
        player.getInventory().setItem(0, new ItemStack(Material.TORCH, 64));
        fire(craft(player, result, ingredients, 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, -1));
        assertEquals(1, credits.size());
    }

    @Test
    void absentCancelledUnmappedAndNonTakingCraftEventsCannotCredit() {
        listen(new CraftListener(manager));
        crafts.put(Material.TORCH, "craft_torch");
        ItemStack result = new ItemStack(Material.TORCH, 4);
        ItemStack[] ingredients = {new ItemStack(Material.STICK)};
        CraftItemEvent cancelled = craft(player, result, ingredients, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL, -1);
        cancelled.setCancelled(true);
        fire(cancelled);
        fire(craft(player, null, ingredients, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL, -1));
        fire(craft(player, new ItemStack(Material.AIR), ingredients, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL, -1));
        fire(craft(player, result, ingredients, 1, ClickType.LEFT, InventoryAction.PICKUP_ALL, -1));
        fire(craft(player, result, ingredients, 0, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR, -1));
        fire(craft(player, result, ingredients, 0, ClickType.LEFT, InventoryAction.NOTHING, -1));
        fire(craft(player, new ItemStack(Material.PAPER), ingredients, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL, -1));
        fire(craft(player, result, new ItemStack[4], 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, -1));
        assertTrue(credits.isEmpty());
    }

    @Test
    void craftEventsRequireAPlayerInventoryActor() {
        listen(new CraftListener(manager));
        crafts.put(Material.TORCH, "craft_torch");
        HumanEntity otherActor = mock(HumanEntity.class);
        fire(craft(otherActor, new ItemStack(Material.TORCH, 4), new ItemStack[] {new ItemStack(Material.STICK)},
            0, ClickType.LEFT, InventoryAction.PICKUP_ALL, -1));
        assertTrue(credits.isEmpty());
    }

    @Test
    void hotbarAndOffhandCraftSwapsOnlyCreditAnEmptyDestination() {
        listen(new CraftListener(manager));
        crafts.put(Material.TORCH, "craft_torch");
        ItemStack result = new ItemStack(Material.TORCH, 4);
        ItemStack[] ingredients = {new ItemStack(Material.STICK)};
        player.getInventory().setItem(2, new ItemStack(Material.STONE));
        player.getInventory().setItemInOffHand(new ItemStack(Material.STONE));
        fire(craft(player, result, ingredients, 0, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 2));
        fire(craft(player, result, ingredients, 0, ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP, -1));
        assertTrue(credits.isEmpty());
        player.getInventory().setItem(2, null);
        player.getInventory().setItemInOffHand(null);
        fire(craft(player, result, ingredients, 0, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 2));
        fire(craft(player, result, ingredients, 0, ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP, -1));
        assertEquals(List.of(credit("craft_torch", 4), credit("craft_torch", 4)), credits);
    }

    CraftItemEvent craft(HumanEntity actor, ItemStack result, ItemStack[] matrix, int slot,
                         ClickType click, InventoryAction action, int hotbar) {
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getMatrix()).thenReturn(matrix);
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(actor);
        when(view.getTopInventory()).thenReturn(inventory);
        when(view.getItem(slot)).thenReturn(result);
        ItemStack recipeResult = result == null || result.getType().isAir() ? new ItemStack(Material.TORCH) : result;
        ShapelessRecipe recipe = new ShapelessRecipe(NamespacedKey.minecraft("activity_test"), recipeResult);
        return new CraftItemEvent(recipe, view, InventoryType.SlotType.RESULT, slot, click, action, hotbar);
    }

    static final class FailUnimplemented implements TestExecutionExceptionHandler,
            LifecycleMethodExecutionExceptionHandler {
        @Override
        public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
            throw checked(failure);
        }

        @Override
        public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
            throw checked(failure);
        }

        private static Throwable checked(Throwable failure) {
            return failure instanceof UnimplementedOperationException
                ? new AssertionError("Listener fixture requires an unsupported MockBukkit operation", failure) : failure;
        }
    }
}
