package tfmc.justin.activity.gui;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.config.Messages;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.managers.TestManagers;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.utils.Bar;
import tfmc.justin.activity.utils.Utils;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// ActivityGui.build() needs a live Bukkit server (Bukkit.createInventory) and
// is not reachable here. What is checked instead is the slot geometry every
// build() call relies on: TASK_SLOTS, SIZE etc are private, read through
// reflection rather than widened just for this test.
// ====================================
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

    // Between the two bars, so a player is never in doubt which button
    // affects which of them
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

    // The reroll slot does not double as a bar slot either
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

    // ====================================
    // progressBar shows progress toward the NEXT point (count % every out of
    // every), not the whole daily budget - it wraps every time a point is
    // earned rather than filling once across the whole day.
    // ====================================
    @Test
    void progressBarIsPartialMidCycle() {
        // market_sale-like: every 25, cap 2. 22 of 25 into the next point.
        ActivityDef def = activity("a", 25, 1, 2);
        assertEquals(Bar.render(22, 25, 20), ActivityGui.progressBar(def, 22));
    }

    // count == every means a point was just earned and the next cycle starts
    // fresh at zero, not a full bar.
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

    // Past the cap (e.g. a stray extra count) must still read as full, not
    // reset or overflow.
    @Test
    void progressBarStaysFullPastTheDailyCap() {
        ActivityDef def = activity("a", 25, 1, 2);
        assertEquals(Bar.render(1, 1, 20), ActivityGui.progressBar(def, 73));
    }

    // every == 1 uncapped: each count is already a whole point, so there is
    // no meaningful partial progress to show.
    @Test
    void progressBarIsHiddenForEveryOneUncapped() {
        ActivityDef def = activity("a", 1, 1, 0);
        assertEquals(null, ActivityGui.progressBar(def, 3));
    }

    // every == 1 capped still shows full once the cap is hit, same as any
    // other capped activity.
    @Test
    void progressBarIsFullForEveryOneCapped() {
        ActivityDef def = activity("a", 1, 1, 2);
        assertEquals(Bar.render(1, 1, 20), ActivityGui.progressBar(def, 2));
    }

    // ====================================
    // Click routing. onClick itself needs a live InventoryClickEvent, so what
    // is checked here is the one decision it makes about a raw slot: which
    // task, if any, the slot stands for.
    // ====================================
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
        // A click outside any inventory
        assertEquals(-1, ActivityGui.taskSlot(-999));
    }

    // ====================================
    // What each of the seven task slots paints, which is what both build()
    // and the repaint after a reveal click walk. A slot with no task in it
    // has to come back null, so the caller can put filler there instead of
    // leaving whatever was in the slot before.
    // ====================================
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

    // A reload that dropped an activity leaves its id in a draw read off disk
    @Test
    void aSlotHoldingAnActivityNobodyLoadedPaintsFiller() {
        ActivityManager manager = threeLoaded();
        TestManagers.unload(manager, "b");
        PlayerData data = drawnWith(List.of("a", "b", "c"));

        assertEquals("a", ActivityGui.taskIdAt(manager.getConfiguration(), data, 0));
        assertNull(ActivityGui.taskIdAt(manager.getConfiguration(), data, 1));
        assertEquals("c", ActivityGui.taskIdAt(manager.getConfiguration(), data, 2));
    }

    // The two named controls: the daily bar is display-only, and filler is
    // never a task
    @Test
    void theDailyBarAndFillerRouteToNoTask() throws ReflectiveOperationException {
        assertEquals(-1, ActivityGui.taskSlot(slot("DAILY_BAR_SLOT")));
        // Slot 0 and slot 26 are always filler in a built view
        assertEquals(-1, ActivityGui.taskSlot(0));
        assertEquals(-1, ActivityGui.taskSlot(26));
    }

    // The reroll button is a control, not a task - a click there must not be
    // mistaken for a task-slot click
    @Test
    void theRerollSlotRoutesToNoTask() throws ReflectiveOperationException {
        assertEquals(-1, ActivityGui.taskSlot(slot("REROLL_SLOT")));
    }

    // ====================================
    // The revealed task's lore. activityItem() itself builds an ItemStack and
    // needs a live registry, so it is unreachable here; activityLore() is the
    // whole of what it assembles and is pure.
    // ====================================
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

    // No description configured - the lore is exactly what it has always been
    @Test
    void anActivityWithoutADescriptionRendersTheSameTwoLines() {
        Messages messages = shippedMessages();
        ActivityDef def = described(List.of());

        List<String> lore = ActivityGui.activityLore(messages, def, 22);

        assertEquals(List.of(
            messages.get("gui.activity-lore-progress", "%bar%", Utils.colorize(Bar.render(22, 25, 20))),
            messages.get("gui.activity-lore-today-capped", "%today%", 0, "%cap%", 5)), lore);
    }

    // A description line is operator text, so it goes through colorize the
    // way display() does rather than reaching the lore raw
    @Test
    void descriptionLinesAreColorized() {
        List<String> lore = ActivityGui.activityLore(shippedMessages(), described(List.of("#e6ca40&lX")), 0);

        assertFalse(lore.get(0).contains("#e6ca40"), lore.toString());
        assertFalse(lore.get(0).contains("&l"), lore.toString());
    }
}
