package net.tfminecraft.activitytf.gui;

import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockito.MockedStatic;
import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.config.Messages;
import net.tfminecraft.activitytf.hooks.ItemsAdderItems;
import net.tfminecraft.activitytf.hooks.TLibsItems;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.managers.TestManagers;
import net.tfminecraft.activitytf.models.ActivityDef;
import net.tfminecraft.activitytf.models.PlayerData;
import net.tfminecraft.activitytf.models.RewardEntry;
import net.tfminecraft.activitytf.utils.Bar;
import net.tfminecraft.activitytf.utils.Utils;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ActivityGuiTest {

    private static int[] taskSlots() throws ReflectiveOperationException {
        Field field = ActivityGui.class.getDeclaredField("TASK_SLOTS");
        field.setAccessible(true);
        return (int[]) field.get(null);
    }

    private static int slot(String name) throws ReflectiveOperationException {
        Field field = ActivityGui.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    @Test
    void theWindowIsASingleChest() throws ReflectiveOperationException {
        assertEquals(27, slot("SIZE"));
    }

    @Test
    void thereIsOneTaskSlotPerDailyTask() throws ReflectiveOperationException {
        assertEquals(PlayerData.TASKS_PER_DAY, taskSlots().length);
    }

    @Test
    void theTaskSlotsAreTheMiddleRowsSevenInnerSlots() throws ReflectiveOperationException {
        assertArrayEqualsInts(new int[] {10, 11, 12, 13, 14, 15, 16}, taskSlots());
    }

    @Test
    void dailyBarAndWeeklyBarSlotsAreFixed() throws ReflectiveOperationException {
        assertEquals(3, slot("DAILY_BAR_SLOT"));
        assertEquals(5, slot("BAR_SLOT"));
    }

    @Test
    void theRerollSlotIsBetweenTheTwoBars() throws ReflectiveOperationException {
        assertEquals(4, slot("REROLL_SLOT"));
    }

    @Test
    void theTaskSlotsAvoidTheBarsAndStayInsideTheWindow() throws ReflectiveOperationException {
        int size = slot("SIZE");
        for (int slot : taskSlots()) {
            assertTrue(slot >= 0 && slot < size, "slot " + slot + " is outside the inventory (" + size + ")");
            assertTrue(slot != slot("DAILY_BAR_SLOT") && slot != slot("BAR_SLOT")
                    && slot != slot("REROLL_SLOT"),
                "slot " + slot + " is also a control slot");
        }
        assertEquals(taskSlots().length, Arrays.stream(taskSlots()).distinct().count(),
            "TASK_SLOTS lists the same slot twice");
    }

    @Test
    void theRerollSlotIsNotAlsoABarSlot() throws ReflectiveOperationException {
        assertTrue(slot("REROLL_SLOT") != slot("DAILY_BAR_SLOT"));
        assertTrue(slot("REROLL_SLOT") != slot("BAR_SLOT"));
    }

    private static void assertArrayEqualsInts(int[] expected, int[] actual) {
        assertEquals(Arrays.toString(expected), Arrays.toString(actual));
    }

    private static ActivityDef activity(String id, int every, int points, int dailyCap) {
        return new ActivityDef(id, id, Material.PAPER, null, every, points, dailyCap);
    }

    @Test
    void progressBarIsPartialMidCycle() {
        ActivityDef def = activity("a", 25, 1, 2);
        assertEquals(Bar.render(22, 25, 20), ActivityGui.progressBar(def, 22));
    }

    @Test
    void progressBarIsEmptyExactlyAtAPointBoundary() {
        ActivityDef def = activity("a", 25, 1, 2);
        assertEquals(Bar.render(0, 25, 20), ActivityGui.progressBar(def, 25));
    }

    @Test
    void progressBarIsFullOnceTheDailyCapIsReached() {
        ActivityDef def = activity("a", 25, 1, 2);
        assertEquals(Bar.render(1, 1, 20), ActivityGui.progressBar(def, 50));
    }

    @Test
    void progressBarStaysFullPastTheDailyCap() {
        ActivityDef def = activity("a", 25, 1, 2);
        assertEquals(Bar.render(1, 1, 20), ActivityGui.progressBar(def, 73));
    }

    @Test
    void progressBarIsHiddenForEveryOneUncapped() {
        ActivityDef def = activity("a", 1, 1, 0);
        assertEquals(null, ActivityGui.progressBar(def, 3));
    }

    @Test
    void progressBarIsFullForEveryOneCapped() {
        ActivityDef def = activity("a", 1, 1, 2);
        assertEquals(Bar.render(1, 1, 20), ActivityGui.progressBar(def, 2));
    }

    @Test
    void theTaskRowRoutesToTaskZeroThroughSix() {
        for (int raw = 10; raw <= 16; raw++) {
            assertEquals(raw - 10, ActivityGui.taskSlot(raw), "raw slot " + raw);
        }
    }

    @Test
    void everyOtherSlotRoutesToNothing() {
        for (int raw = 0; raw <= 9; raw++) {
            assertEquals(-1, ActivityGui.taskSlot(raw), "raw slot " + raw);
        }
        for (int raw = 17; raw <= 26; raw++) {
            assertEquals(-1, ActivityGui.taskSlot(raw), "raw slot " + raw);
        }
        assertEquals(-1, ActivityGui.taskSlot(-999));
    }

    private static ActivityManager threeLoaded() {
        return TestManagers.manager(
            new ActivityDef("a", "A", Material.PAPER, null, 1, 1, 0),
            new ActivityDef("b", "B", Material.PAPER, null, 1, 1, 0),
            new ActivityDef("c", "C", Material.PAPER, null, 1, 1, 0));
    }

    private static PlayerData drawnWith(List<String> tasks) {
        return new PlayerData(0, 0, "2026-W38", "2026-09-17", 0, java.util.Map.of(), tasks, Set.of());
    }

    @Test
    void everySlotInTheDrawPaintsItsOwnTask() {
        ActivityConfiguration config = threeLoaded().getConfiguration();
        PlayerData data = drawnWith(List.of("a", "b", "c"));

        assertEquals("a", ActivityGui.taskIdAt(config, data, 0));
        assertEquals("b", ActivityGui.taskIdAt(config, data, 1));
        assertEquals("c", ActivityGui.taskIdAt(config, data, 2));
    }

    @Test
    void aSlotPastTheEndOfTheDrawPaintsFiller() {
        ActivityConfiguration config = threeLoaded().getConfiguration();
        PlayerData data = drawnWith(List.of("a", "b", "c"));

        for (int slot = 3; slot < PlayerData.TASKS_PER_DAY; slot++) {
            assertNull(ActivityGui.taskIdAt(config, data, slot), "slot " + slot);
        }
        assertNull(ActivityGui.taskIdAt(config, data, -1));
    }

    @Test
    void aSlotHoldingAnActivityNobodyLoadedPaintsFiller() {
        ActivityManager manager = threeLoaded();
        TestManagers.unload(manager, "b");
        PlayerData data = drawnWith(List.of("a", "b", "c"));

        assertEquals("a", ActivityGui.taskIdAt(manager.getConfiguration(), data, 0));
        assertNull(ActivityGui.taskIdAt(manager.getConfiguration(), data, 1));
        assertEquals("c", ActivityGui.taskIdAt(manager.getConfiguration(), data, 2));
    }

    @Test
    void theDailyBarAndFillerRouteToNoTask() throws ReflectiveOperationException {
        assertEquals(-1, ActivityGui.taskSlot(slot("DAILY_BAR_SLOT")));
        assertEquals(-1, ActivityGui.taskSlot(0));
        assertEquals(-1, ActivityGui.taskSlot(26));
    }

    @Test
    void theRerollSlotRoutesToNoTask() throws ReflectiveOperationException {
        assertEquals(-1, ActivityGui.taskSlot(slot("REROLL_SLOT")));
    }

    private static Messages shippedMessages() {
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("a", "&aA", Material.PAPER, null, 1, 1, 0));
        TestManagers.messages(manager);
        return manager.getConfiguration().messages();
    }

    private static ActivityDef described(List<String> description) {
        return new ActivityDef("a", "&aA", Material.PAPER, null, 25, 1, 5, List.of(), description);
    }

    @Test
    void descriptionAmountsFollowTheCurrentActivityDefinition() {
        List<String> description = List.of(
            "&7Earn %points% points per %every% actions; %daily-cap% awards/day.",
            "%every%/%every% %unknown%");
        ActivityDef original = new ActivityDef("a", "A", Material.PAPER, null,
            25, 1, 2, List.of(), description);
        ActivityDef changed = new ActivityDef("a", "A", Material.PAPER, null,
            60, 5, 3, List.of(), description);
        assertEquals(Utils.colorize("&7Earn 1 points per 25 actions; 2 awards/day."),
            ActivityGui.activityLore(shippedMessages(), original, 0).getFirst());
        assertEquals(Utils.colorize("&7Earn 5 points per 60 actions; 3 awards/day."),
            ActivityGui.activityLore(shippedMessages(), changed, 0).getFirst());
        assertEquals("60/60 %unknown%",
            ActivityGui.activityLore(shippedMessages(), changed, 0).get(1));
        assertEquals(description, changed.description());
    }

    @Test
    void uncappedDescriptionsShowTheConfiguredZeroCap() {
        ActivityDef def = new ActivityDef("a", "A", Material.PAPER, null,
            1, 1, 0, List.of(), List.of("%every% %points% %daily-cap%"));
        assertEquals("1 1 0", ActivityGui.activityLore(shippedMessages(), def, 0).getFirst());
    }

    @Test
    void theDescriptionSitsAboveTheProgressAndTodayLines() {
        Messages messages = shippedMessages();
        ActivityDef def = described(List.of("&7first", "&7second"));

        List<String> lore = ActivityGui.activityLore(messages, def, 22);

        assertEquals(List.of(
            Utils.colorize("&7first"),
            Utils.colorize("&7second"),
            messages.get("gui.activity-lore-progress", "%bar%", Utils.colorize(Bar.render(22, 25, 20))),
            messages.get("gui.activity-lore-today-capped", "%today%", 0, "%cap%", 5)), lore);
    }

    @Test
    void theTodayLineShowsCompletionsNotPoints() {
        Messages messages = shippedMessages();
        ActivityDef def = activity("injured", 1, 5, 1);

        assertEquals(List.of(
            messages.get("gui.activity-lore-today-capped", "%today%", 0, "%cap%", 1)),
            ActivityGui.activityLore(messages, def, 0));

        assertEquals(List.of(
            messages.get("gui.activity-lore-progress", "%bar%", Utils.colorize(Bar.render(1, 1, 20))),
            messages.get("gui.activity-lore-today-capped", "%today%", 1, "%cap%", 1)),
            ActivityGui.activityLore(messages, def, 1));

        assertEquals(List.of(
            messages.get("gui.activity-lore-progress", "%bar%", Utils.colorize(Bar.render(1, 1, 20))),
            messages.get("gui.activity-lore-today-capped", "%today%", 1, "%cap%", 1)),
            ActivityGui.activityLore(messages, def, 3));
    }

    @Test
    void anActivityWithoutADescriptionRendersTheSameTwoLines() {
        Messages messages = shippedMessages();
        ActivityDef def = described(List.of());

        List<String> lore = ActivityGui.activityLore(messages, def, 22);

        assertEquals(List.of(
            messages.get("gui.activity-lore-progress", "%bar%", Utils.colorize(Bar.render(22, 25, 20))),
            messages.get("gui.activity-lore-today-capped", "%today%", 0, "%cap%", 5)), lore);
    }

    @Test
    void theWeeklyIconNamesTheNextReward() {
        Messages messages = shippedMessages();
        PlayerData data = new PlayerData(5, 0, "2026-W38", "2026-09-17", 0, java.util.Map.of());
        RewardEntry steel = new RewardEntry(1, "Steel", List.of(),
            List.of(new RewardEntry.Item("m.material.steel", 3)));

        List<String> lore = ActivityGui.weeklyRewardLore(messages, data, List.of(10, 20),
            Map.of(10, steel), 1, Set.of(), pool -> List.of());

        assertEquals(List.of(messages.get("gui.bar-lore-reward", "%points%", 10, "%reward%",
            Utils.colorize("#50d990x3 #b8906eSteel"))), lore);
        assertTrue(String.join(" ", lore).contains("Steel"), lore.toString());
    }

    @Test
    void theWeeklyIconSummarizesTheNextPoolAsOneRandomRange() {
        Messages messages = shippedMessages();
        PlayerData data = new PlayerData(12, 0, "2026-W38", "2026-09-17", 10, java.util.Map.of());
        List<RewardEntry> pool = List.of(
            material(65, 2, "ignitium", "#7f7d80Ignitium"),
            material(3, 8, "ignitium", "#7f7d80Ignitium"));

        List<String> lore = ActivityGui.weeklyRewardLore(messages, data, List.of(10, 20),
            Map.of(20, ActivityConfiguration.poolRef("pool_prologue")), 1, Set.of(), name -> pool);

        assertEquals(messages.get("gui.bar-lore-next-options", "%points%", 20), lore.get(0));
        assertEquals(messages.get("gui.reward-preview-one"), lore.get(1));
        assertEquals(Utils.colorize("#50d990x2–8 &r#7f7d80Ignitium"), lore.get(2));
        assertEquals(3, lore.size());
    }

    @Test
    void theWeeklyIconSaysThereAreNoMoreRewardsThisWeek() {
        Messages messages = shippedMessages();
        PlayerData data = new PlayerData(40, 0, "2026-W38", "2026-09-17", 40, java.util.Map.of());

        List<String> lore = ActivityGui.weeklyRewardLore(messages, data, List.of(10, 20, 40),
            Map.of(), 1, Set.of(), pool -> List.of());

        assertEquals(List.of(messages.get("gui.bar-lore-none")), lore);
        assertTrue(lore.get(0).toLowerCase(java.util.Locale.ROOT).contains("no more rewards this week"),
            lore.toString());
    }

    @Test
    void aReadyRewardStillSaysWhatItIsAndThatItCanBeClaimed() {
        Messages messages = shippedMessages();
        PlayerData data = new PlayerData(10, 0, "2026-W38", "2026-09-17", 0, java.util.Map.of());
        RewardEntry steel = new RewardEntry(1, "Steel", List.of(),
            List.of(new RewardEntry.Item("DIAMOND", 1)));

        List<String> lore = ActivityGui.weeklyRewardLore(messages, data, List.of(10),
            Map.of(10, steel), 2, Set.of(), pool -> List.of());

        assertTrue(lore.get(0).contains("x2"), lore.toString());
        assertEquals(messages.get("gui.reward-click", "%count%", 1), lore.get(1));
    }

    private static RewardEntry material(int weight, int amount, String id, String label) {
        return new RewardEntry(weight, "#50d990x" + amount + " " + label, List.of(),
            List.of(new RewardEntry.Item("m.materials." + id, amount)));
    }

    @Test
    void actOneShowsThreeMaterialRangesPerIndependentDraw() {
        Messages messages = shippedMessages();
        List<RewardEntry> pool = new ArrayList<>();
        int[][] weights = {{35, 15, 15}, {13, 6, 6}, {3, 2, 2}, {1, 1, 1}};
        for (int tier = 0; tier < 4; tier++) {
            pool.add(material(weights[tier][0], (tier + 1) * 20, "ignitium", "#7f7d80Ignitium"));
            pool.add(material(weights[tier][1], (tier + 1) * 2, "raw_tin", "#7f7d80Raw Tin"));
            pool.add(material(weights[tier][2], (tier + 1) * 2, "abyssalite_fragment", "#3b4e60Abyssalite Fragment"));
        }
        PlayerData data = new PlayerData(10, 0, "2026-W38", "2026-09-17", 0, Map.of());
        List<String> lore = ActivityGui.weeklyRewardLore(messages, data, List.of(10),
            Map.of(10, ActivityConfiguration.poolRef("pool_act1")), 2, Set.of("pool_act1"), name -> pool);

        assertEquals(messages.get("gui.reward-preview-many", "%count%", 2), lore.get(1));
        assertEquals(Utils.colorize("#50d990x20–80 &r#7f7d80Ignitium"), lore.get(2));
        assertEquals(Utils.colorize("#50d990x2–8 &r#7f7d80Raw Tin"), lore.get(3));
        assertEquals(Utils.colorize("#50d990x2–8 &r#3b4e60Abyssalite Fragment"), lore.get(4));
        assertEquals(messages.get("gui.reward-click", "%count%", 1), lore.get(5));
    }

    @Test
    void unlistedSkinPoolStillPreviewsOnlyOneDraw() {
        Messages messages = shippedMessages();
        PlayerData data = new PlayerData(0, 0, "2026-W38", "2026-09-17", 0, Map.of());
        List<String> lore = ActivityGui.weeklyRewardLore(messages, data, List.of(10),
            Map.of(10, ActivityConfiguration.poolRef("pool_skin")), 2, Set.of("pool_act1"),
            name -> List.of(material(65, 1, "common_item_skin_scroll", "&7Common Item Skin Scroll"),
                material(3, 1, "legendary_item_skin_scroll", "&7Legendary Item Skin Scroll")));

        assertEquals(messages.get("gui.reward-preview-one"), lore.get(1));
        assertTrue(lore.get(2).contains("x1"));
        assertTrue(lore.get(3).contains("x1"));
    }

    @Test
    void previewBoundsUseItemsAndNeverMergeDifferentItemsWithTheSameName() {
        List<String> options = RewardPreview.summarize(List.of(
            new RewardEntry(65, "#50d990x999 &7Metal", List.of(),
                List.of(new RewardEntry.Item("m.materials.ignitium", 2))),
            material(3, 8, "ignitium", "&7Metal"),
            material(25, 4, "raw_tin", "&7Metal"),
            material(0, 1000, "ignitium", "&7Metal")));
        assertEquals(List.of("#50d990x2–8 &r&7Metal", "#50d990x4 &r&7Metal"), options);
    }

    @Test
    void commandAndBundleOutcomesKeepTheirDescriptions() {
        RewardEntry command = new RewardEntry(1, "&7A title", List.of("title %player%"), List.of());
        RewardEntry bundle = new RewardEntry(1, "&7Material bundle", List.of(),
            List.of(new RewardEntry.Item("DIAMOND", 2), new RewardEntry.Item("IRON_INGOT", 3)));
        assertEquals(List.of("&7A title", "&7Material bundle"),
            RewardPreview.summarize(List.of(command, bundle, command)));
    }

    @Test
    void largePoolsAreBoundedAndMentionTheRemainingPossibilities() {
        Messages messages = shippedMessages();
        PlayerData data = new PlayerData(0, 0, "2026-W38", "2026-09-17", 0, Map.of());
        List<RewardEntry> pool = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            pool.add(material(1, 1, "item_" + i, "&7Item " + i));
        }
        List<String> lore = ActivityGui.weeklyRewardLore(messages, data, List.of(10), Map.of(),
            1, Set.of(), name -> pool);
        assertEquals(7, lore.size());
        assertEquals(messages.get("gui.reward-preview-more", "%count%", 8), lore.get(6));
    }

    @Test
    void anEmptyPoolReportsAnUnavailablePreview() {
        Messages messages = shippedMessages();
        PlayerData data = new PlayerData(0, 0, "2026-W38", "2026-09-17", 0, Map.of());
        List<String> lore = ActivityGui.weeklyRewardLore(messages, data, List.of(10), Map.of(),
            1, Set.of(), name -> List.of());
        assertEquals(messages.get("gui.reward-preview-unavailable"), lore.get(1));
    }

    @Test
    void descriptionLinesAreColorized() {
        List<String> lore = ActivityGui.activityLore(shippedMessages(), described(List.of("#e6ca40&lX")), 0);

        assertFalse(lore.get(0).contains("#e6ca40"), lore.toString());
        assertFalse(lore.get(0).contains("&l"), lore.toString());
    }

    private static final class TestStack extends ItemStack {
        private final Material type;

        TestStack(Material type) {
            this.type = type;
        }

        @Override
        public Material getType() {
            return type;
        }
    }

    private static final Function<String, ItemStack> TLIBS_ITEMS = path -> new TestStack(Material.IRON_INGOT);

    private static final Function<String, ItemStack> IA_ITEMS = id -> new TestStack(Material.DIAMOND);

    private static ItemStack fromPath(String iconPath, boolean tlibs, boolean ia) {
        return ActivityGui.fromPath(iconPath, tlibs, ia, TLIBS_ITEMS, IA_ITEMS);
    }

    @Test
    void anItemsAdderIconNeedsOnlyTheItemsAdderGate() {
        assertEquals(Material.DIAMOND, fromPath("ia.tfmc:saucepan", false, true).getType());
        assertEquals(Material.DIAMOND, fromPath("ia.tfmc:saucepan", true, true).getType());
        assertNull(fromPath("ia.tfmc:saucepan", true, false));
        assertNull(fromPath("ia.tfmc:saucepan", false, false));
    }

    @Test
    void aTLibsIconNeedsOnlyTheItemPathsGate() {
        assertEquals(Material.IRON_INGOT, fromPath("m.material.steel", true, false).getType());
        assertEquals(Material.IRON_INGOT, fromPath("m.material.steel", true, true).getType());
        assertNull(fromPath("m.material.steel", false, true));
        assertNull(fromPath("m.material.steel", false, false));
    }

    @Test
    void noPathAndAMalformedItemsAdderPathBuildNothing() {
        assertNull(fromPath(null, true, true));
        assertNull(fromPath("ia.saucepan", true, true));
    }

    @Test
    void revealingTheLastTaskAsksForTheDailyReward() {
        ActivityManager manager = TestManagers.manager(
            new ActivityDef("a", "a", Material.PAPER, null, 1, 1, 0),
            new ActivityDef("b", "b", Material.PAPER, null, 1, 1, 0));
        TestManagers.messages(manager);
        TestManagers.dailyRewards(manager, Map.of("vip", new RewardEntry(1, "Steel", List.of(),
            List.of(new RewardEntry.Item("m.material.steel", 1)))));
        UUID uuid = UUID.randomUUID();
        List<String> chat = new ArrayList<>();
        Player player = TestManagers.player(uuid, Set.of("group.vip"), chat);
        ActivityGui gui = new ActivityGui(manager);
        manager.tasks(uuid);

        assertEquals(manager.tasks(uuid).tasks().get(0), gui.revealTask(player, 0).revealedId());
        assertTrue(chat.isEmpty(), chat.toString());

        gui.revealTask(player, 1);
        assertTrue(chat.stream().anyMatch(line -> line.contains("could not be handed over")), chat.toString());
    }

    @Test
    void rewardPreviewsNameOutcomesWhoseLabelsAreMissing() {
        assertEquals(List.of("#50d990x2 &r&7DIAMOND", "&7Other reward"), RewardPreview.summarize(List.of(
            new RewardEntry(1, null, List.of(), List.of(new RewardEntry.Item("DIAMOND", 2))),
            new RewardEntry(1, " ", List.of("reward %uuid%"), List.of()))));
    }

    @Nested
    @ExtendWith(FailUnimplemented.class)
    class MenuInteraction {
        @TempDir
        File directory;

        Server previousServer;
        ServerMock server;
        JavaPlugin plugin;
        PlayerMock player;
        PermissionAttachment permissions;
        ActivityManager manager;
        ActivityGui gui;

        @BeforeEach
        void startServer() throws ReflectiveOperationException {
            previousServer = Bukkit.getServer();
            set(Bukkit.class, null, "server", null);
            server = MockBukkit.mock();
            plugin = MockBukkit.createMockPlugin("ActivityGuiTest");
            player = server.addPlayer("Steve");
            permissions = player.addAttachment(plugin);
            permissions.setPermission("activity.use", true);
            permissions.setPermission("activity.reroll", true);
            configure(activity("a", 1, 1, 0), activity("b", 1, 1, 0), activity("c", 1, 1, 0));
        }

        @AfterEach
        void stopServer() throws ReflectiveOperationException {
            try {
                MockBukkit.unmock();
            } finally {
                set(Bukkit.class, null, "server", previousServer);
            }
        }

        void configure(ActivityDef... definitions) throws ReflectiveOperationException {
            manager = TestManagers.manager(definitions);
            TestManagers.messages(manager);
            TestManagers.storeLoaded(manager);
            TestManagers.storeFile(manager, new File(directory, "players.yml"));
            TestManagers.rerollsPerDay(manager, 1);
            set(ActivityManager.class, manager, "plugin", plugin);
            set(net.tfminecraft.activitytf.store.PlayerStore.class, manager.getStore(), "plugin", plugin);
            set(ActivityConfiguration.class, manager.getConfiguration(), "guiTitle", "&8Activities");
            set(ActivityConfiguration.class, manager.getConfiguration(), "barLength", 10);
            gui = new ActivityGui(manager);
        }

        Inventory open() {
            Inventory inventory = gui.build(player);
            player.openInventory(inventory);
            return inventory;
        }

        PlayerData data() {
            return manager.tasks(player.getUniqueId());
        }

        InventoryClickEvent click(int rawSlot) {
            InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, rawSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            gui.onClick(event);
            return event;
        }

        String message(String key) {
            var legacy = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
            return legacy.serialize(legacy.deserialize(manager.getConfiguration().messages().get(key)));
        }

        String rerollLore() {
            return gui.build(player).getItem(4).getItemMeta().getLore().get(0);
        }

        @Test
        void rendersACompleteMenuWithHiddenTasksAndMetadata() {
            Inventory inventory = open();
            assertEquals(27, inventory.getSize());
            assertSame(inventory, inventory.getHolder().getInventory());
            assertEquals("\u00a78Activities", player.getOpenInventory().getTitle());
            for (int index = 0; index < inventory.getSize(); index++) {
                assertNotNull(inventory.getItem(index), "Empty slot " + index);
            }
            assertEquals(Material.EXPERIENCE_BOTTLE, inventory.getItem(3).getType());
            assertEquals(Material.NETHER_STAR, inventory.getItem(4).getType());
            assertEquals(Material.EXPERIENCE_BOTTLE, inventory.getItem(5).getType());
            assertEquals(Material.GRAY_DYE, inventory.getItem(10).getType());
            assertEquals(message("gui.hidden-task-name"), inventory.getItem(10).getItemMeta().getDisplayName());
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(13).getType());
            assertEquals(message("gui.filler-name"), inventory.getItem(0).getItemMeta().getDisplayName());
            assertTrue(inventory.getItem(10).getItemMeta().getItemFlags().containsAll(Set.of(
                ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_STORED_ENCHANTS)));
            var attributes = inventory.getItem(10).getItemMeta().getAttributeModifiers();
            assertTrue(attributes == null || attributes.isEmpty());
        }

        @Test
        void revealReplacesTheHiddenItemAndPlaysFeedbackOnlyOnce() {
            Inventory inventory = open();
            String id = data().tasks().get(0);
            assertTrue(click(10).isCancelled());
            assertTrue(data().isRevealed(id));
            assertEquals(Material.PAPER, inventory.getItem(10).getType());
            assertEquals(id, inventory.getItem(10).getItemMeta().getDisplayName());
            assertFalse(inventory.getItem(10).getItemMeta().getLore().isEmpty());
            assertEquals(2, player.getHeardSounds().size());
            assertTrue(click(10).isCancelled());
            assertEquals(2, player.getHeardSounds().size());
            assertSame(inventory, player.getOpenInventory().getTopInventory());
        }

        @Test
        void revealingEveryTaskPaysTheDailyRewardOnce() {
            permissions.setPermission("group.vip", true);
            TestManagers.dailyRewards(manager, Map.of("vip", new RewardEntry(1, "Diamond", List.of(),
                List.of(new RewardEntry.Item("DIAMOND", 3)))));
            open();
            click(10);
            click(11);
            assertFalse(data().dailyRewardClaimed());
            assertFalse(player.getInventory().contains(Material.DIAMOND));

            click(12);
            assertTrue(data().dailyRewardClaimed());
            assertTrue(player.getInventory().contains(Material.DIAMOND, 3));
            assertNotNull(player.nextMessage());
            click(12);
            assertEquals(3, player.getInventory().all(Material.DIAMOND).values().stream()
                .mapToInt(ItemStack::getAmount).sum());
        }

        @Test
        void staleDailyDrawRepaintsBarsAndEveryTask() {
            open();
            click(10);
            String revealed = data().tasks().get(0);
            manager.recordAction(player.getUniqueId(), revealed, 1);
            Inventory inventory = open();
            assertEquals(1, data().dailyPoints());
            data().reset(manager.getConfiguration().currentKeys().week(), "2000-01-01");

            click(11);

            assertEquals(Material.EXPERIENCE_BOTTLE, inventory.getItem(3).getType());
            assertEquals(manager.getConfiguration().messages().get("gui.daily-bar-name", "%points%", 0, "%max%", 10),
                inventory.getItem(3).getItemMeta().getDisplayName());
            assertEquals(Material.NETHER_STAR, inventory.getItem(4).getType());
            assertEquals(Material.EXPERIENCE_BOTTLE, inventory.getItem(5).getType());
            assertEquals(Material.GRAY_DYE, inventory.getItem(10).getType());
            assertEquals(Material.PAPER, inventory.getItem(11).getType());
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(13).getType());
            assertEquals(0, data().dailyPoints());
        }

        @Test
        void removedClickedTaskRepaintsTheDrawWithoutRevealingAnotherTask() {
            Inventory inventory = open();
            String removed = data().tasks().get(0);
            TestManagers.unload(manager, removed);

            assertTrue(click(10).isCancelled());

            assertFalse(data().tasks().contains(removed));
            assertTrue(data().revealed().isEmpty());
            assertEquals(Material.GRAY_DYE, inventory.getItem(10).getType());
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(12).getType());
            assertTrue(player.getHeardSounds().isEmpty());
        }

        @Test
        void cancelsBottomOutsideAndFillerClicksWithoutChangingTasks() {
            open();
            assertTrue(click(27).isCancelled());
            assertTrue(click(-999).isCancelled());
            assertTrue(click(0).isCancelled());
            assertTrue(data().revealed().isEmpty());
            assertTrue(player.getHeardSounds().isEmpty());
        }

        @Test
        void ignoresOtherInventoriesAndPlayersWithoutUsePermission() {
            player.openInventory(server.createInventory(null, 27));
            assertFalse(click(10).isCancelled());
            open();
            permissions.setPermission("activity.use", false);
            assertTrue(click(10).isCancelled());
            assertTrue(data().revealed().isEmpty());
            assertTrue(player.getHeardSounds().isEmpty());
        }

        @Test
        void ignoresANonPlayerViewingTheMenu() {
            Inventory inventory = open();
            InventoryView view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(inventory);
            when(view.getInventory(10)).thenReturn(inventory);
            when(view.getPlayer()).thenReturn(mock(HumanEntity.class));
            InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                10, ClickType.LEFT, InventoryAction.PICKUP_ALL);

            gui.onClick(event);

            assertTrue(event.isCancelled());
            assertTrue(data().revealed().isEmpty());
        }

        @ParameterizedTest
        @CsvSource({"true,0,true", "true,30,false", "false,0,false"})
        void dragProtectsOnlyTheActivityTopInventory(boolean activityMenu, int rawSlot, boolean cancelled) {
            if (activityMenu) {
                open();
            } else {
                player.openInventory(server.createInventory(null, 27));
            }
            ItemStack item = new ItemStack(Material.STONE);
            InventoryDragEvent event = new InventoryDragEvent(player.getOpenInventory(), item, item, false,
                Map.of(rawSlot, item));

            gui.onDrag(event);

            assertEquals(cancelled, event.isCancelled());
        }

        @Test
        void rerollLoreExplainsDisabledLockedLateAndAvailableStates() {
            TestManagers.rerollsPerDay(manager, 0);
            assertEquals(message("gui.reroll-lore-disabled"), rerollLore());
            TestManagers.rerollsPerDay(manager, 1);
            permissions.setPermission("activity.reroll", false);
            assertEquals(message("gui.reroll-lore-locked"), rerollLore());
            permissions.setPermission("activity.reroll", true);
            data().record(2, activity("a", 1, 1, 0), 50, 10, List.of());
            assertEquals(manager.getConfiguration().messages().get("gui.reroll-lore-too-late", "%points%", 1),
                rerollLore());
            data().reset(manager.getConfiguration().currentKeys().week(), manager.getConfiguration().currentKeys().day());
            assertEquals(manager.getConfiguration().messages().get("gui.reroll-lore-left", "%left%", 1, "%max%", 1),
                rerollLore());
        }

        @Test
        void rerollRejectsDisabledLockedUnavailableAndTooLateRequests() throws ReflectiveOperationException {
            open();
            TestManagers.rerollsPerDay(manager, 0);
            click(4);
            assertEquals(message("reroll-disabled"), player.nextMessage());
            TestManagers.rerollsPerDay(manager, 1);
            permissions.setPermission("activity.reroll", false);
            click(4);
            assertEquals(message("reroll-locked"), player.nextMessage());
            permissions.setPermission("activity.reroll", true);
            set(net.tfminecraft.activitytf.store.PlayerStore.class, manager.getStore(), "loaded", false);
            click(4);
            assertEquals(message("reroll-failed"), player.nextMessage());
            TestManagers.storeLoaded(manager);
            data().record(2, activity("a", 1, 1, 0), 50, 10, List.of());
            click(4);
            assertEquals(manager.getConfiguration().messages().get("reroll-too-late", "%points%", 1), player.nextMessage());
            assertEquals(0, data().rerolls());
        }

        @Test
        void successfulRerollHidesTasksAndRefreshesTheRemainingAllowance() {
            Inventory inventory = open();
            click(10);
            click(4);

            assertEquals(message("reroll-done"), player.nextMessage());
            assertEquals(1, data().rerolls());
            assertTrue(data().revealed().isEmpty());
            assertEquals(Material.GRAY_DYE, inventory.getItem(10).getType());
            assertEquals(manager.getConfiguration().messages().get("gui.reroll-lore-left", "%left%", 0, "%max%", 1),
                inventory.getItem(4).getItemMeta().getLore().get(0));

            click(4);
            assertEquals(message("reroll-none-left"), player.nextMessage());
            assertEquals(1, data().rerolls());
        }

        @Test
        void weeklyClaimHandsOverRewardsAndRefreshesTheBar() throws ReflectiveOperationException {
            set(ActivityConfiguration.class, manager.getConfiguration(), "milestoneDrops", Map.of(
                10, new RewardEntry(1, "Diamond", List.of(), List.of(new RewardEntry.Item("DIAMOND", 2))),
                20, new RewardEntry(1, "Iron", List.of(), List.of(new RewardEntry.Item("IRON_INGOT", 3)))));
            data().addPoints(20, 50);
            Inventory inventory = open();

            assertTrue(click(5).isCancelled());

            assertEquals(20, data().claimedPoints());
            assertTrue(player.getInventory().contains(Material.DIAMOND, 2));
            assertTrue(player.getInventory().contains(Material.IRON_INGOT, 3));
            assertTrue(inventory.getItem(5).getItemMeta().getLore().contains(message("gui.bar-lore-none")));
            assertNotNull(player.nextMessage());
            assertNotNull(player.nextMessage());
            click(5);
            assertNull(player.nextMessage());
            assertEquals(2, player.getInventory().all(Material.DIAMOND).values().stream()
                .mapToInt(ItemStack::getAmount).sum());
        }

        @Test
        void clickingAnEmptyWeeklyBarPaysNothing() {
            Inventory inventory = open();
            ItemStack before = inventory.getItem(5).clone();
            click(5);
            assertEquals(before, inventory.getItem(5));
            assertEquals(0, data().claimedPoints());
            assertNull(player.nextMessage());
        }

        @Test
        void revealedTaskCommandsCloseTheMenuAndQuitClearsTheirCooldown() throws ReflectiveOperationException {
            configure(new ActivityDef("a", "a", Material.PAPER, null, 1, 1, 0,
                List.of("taskclick %player%"), List.of("&eClick for an action")));
            List<String> dispatched = new ArrayList<>();
            server.getCommandMap().register("activitytest", new Command("taskclick") {
                @Override
                public boolean execute(CommandSender sender, String label, String[] args) {
                    Inventory top = player.getOpenInventory().getTopInventory();
                    assertTrue(top == null || !(top.getHolder() instanceof ActivityGui.Marker));
                    dispatched.add(String.join(" ", args));
                    return true;
                }
            });
            open();
            click(10);
            click(10);
            assertEquals(3, player.getHeardSounds().size());
            assertTrue(dispatched.isEmpty());
            server.getScheduler().performOneTick();
            assertEquals(List.of("Steve"), dispatched);

            gui.onQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null,
                PlayerQuitEvent.QuitReason.DISCONNECTED));
            open();
            click(10);
            server.getScheduler().performOneTick();
            assertEquals(List.of("Steve", "Steve"), dispatched);
        }

        @Test
        void customIconsResolveThroughTheirAvailableProvidersAndFallbackWhenMissing()
            throws ReflectiveOperationException {
            configure(new ActivityDef("a", "Custom", Material.PAPER, "m.material.icon", 1, 1, 0));
            manager.reveal(player.getUniqueId(), 0);
            set(ActivityConfiguration.class, manager.getConfiguration(), "itemPathsUsable", true);
            try (MockedStatic<TLibsItems> items = mockStatic(TLibsItems.class)) {
                items.when(() -> TLibsItems.item("m.material.icon")).thenReturn(new ItemStack(Material.EMERALD));
                assertEquals(Material.EMERALD, gui.build(player).getItem(10).getType());
                items.when(() -> TLibsItems.item("m.material.icon")).thenReturn(new ItemStack(Material.AIR));
                assertEquals(Material.PAPER, gui.build(player).getItem(10).getType());
            }

            configure(new ActivityDef("a", "Custom", Material.PAPER, "ia.test:icon", 1, 1, 0));
            manager.reveal(player.getUniqueId(), 0);
            set(ActivityConfiguration.class, manager.getConfiguration(), "itemsAdderUsable", true);
            try (MockedStatic<ItemsAdderItems> items = mockStatic(ItemsAdderItems.class)) {
                items.when(() -> ItemsAdderItems.item("test:icon")).thenReturn(new ItemStack(Material.DIAMOND));
                assertEquals(Material.DIAMOND, gui.build(player).getItem(10).getType());
                items.when(() -> ItemsAdderItems.item("test:icon")).thenReturn(null);
                assertEquals(Material.PAPER, gui.build(player).getItem(10).getType());
            }
        }
    }

    static final class FailUnimplemented implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {
        @Override
        public void handleTestExecutionException(ExtensionContext context, Throwable thrown) throws Throwable {
            throw failed(thrown);
        }

        @Override
        public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable thrown) throws Throwable {
            throw failed(thrown);
        }

        private static Throwable failed(Throwable thrown) {
            return thrown instanceof UnimplementedOperationException
                ? new AssertionError("MockBukkit does not implement a call this GUI test needs", thrown) : thrown;
        }
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
