package net.tfminecraft.activitytf.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.config.Messages;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.managers.TestManagers;
import net.tfminecraft.activitytf.models.ActivityDef;
import net.tfminecraft.activitytf.models.PlayerData;
import net.tfminecraft.activitytf.models.RewardEntry;
import net.tfminecraft.activitytf.utils.Bar;
import net.tfminecraft.activitytf.utils.Utils;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
