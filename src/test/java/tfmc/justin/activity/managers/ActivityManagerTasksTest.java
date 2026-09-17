package tfmc.justin.activity.managers;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.models.Recorded;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// The daily-task gate, on a real manager built without a server (see
// TestManagers). Every activity here is 'every: 2', so a single recorded
// action never awards a point - which keeps recordAction away from
// Bukkit.getPlayer(), unreachable headless. The witness is the action count.
// ====================================
class ActivityManagerTasksTest {

    private static ActivityDef def(String id) {
        return new ActivityDef(id, id, Material.PAPER, null, 2, 1, 0);
    }

    private static ActivityDef[] defs(int count) {
        ActivityDef[] defs = new ActivityDef[count];
        for (int i = 0; i < count; i++) {
            defs[i] = def("a" + i);
        }
        return defs;
    }

    private static String undrawn(ActivityManager manager, UUID uuid, int loaded) {
        Set<String> drawn = new HashSet<>(manager.tasks(uuid).tasks());
        for (int i = 0; i < loaded; i++) {
            if (!drawn.contains("a" + i)) {
                return "a" + i;
            }
        }
        throw new IllegalStateException("every loaded activity was drawn");
    }

    @Test
    void theDrawIsSevenDistinctLoadedIds() {
        ActivityManager manager = TestManagers.manager(defs(20));

        List<String> tasks = manager.tasks(UUID.randomUUID()).tasks();

        assertEquals(PlayerData.TASKS_PER_DAY, tasks.size());
        assertEquals(tasks.size(), new HashSet<>(tasks).size(), "the draw repeats an id");
        for (String id : tasks) {
            assertTrue(id.startsWith("a"), "drawn id is not a loaded activity: " + id);
        }
    }

    @Test
    void fewerActivitiesThanTaskSlotsDrawsAllOfThem() {
        ActivityManager manager = TestManagers.manager(defs(3));

        List<String> tasks = manager.tasks(UUID.randomUUID()).tasks();

        assertEquals(3, tasks.size());
        assertEquals(Set.of("a0", "a1", "a2"), new HashSet<>(tasks));
    }

    @Test
    void theDrawIsStableForThePlayerOnceMade() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();

        List<String> first = new ArrayList<>(manager.tasks(uuid).tasks());

        assertEquals(first, manager.tasks(uuid).tasks());
    }

    @Test
    void aHiddenTaskRecordsNothing() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();
        String hidden = manager.tasks(uuid).tasks().get(0);

        assertFalse(manager.isTracked(uuid, hidden));
        manager.recordAction(uuid, hidden, 1);

        assertEquals(0, manager.tasks(uuid).count(hidden));
    }

    @Test
    void anUndrawnActivityRecordsNothingEvenThoughItIsLoaded() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();
        String other = undrawn(manager, uuid, 20);

        assertFalse(manager.isTracked(uuid, other));
        manager.recordAction(uuid, other, 1);

        assertEquals(0, manager.tasks(uuid).count(other));
    }

    @Test
    void aRevealedTaskRecordsNormally() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();
        String task = manager.tasks(uuid).tasks().get(0);

        assertNotNull(manager.reveal(uuid, 0).revealedId());
        assertTrue(manager.isTracked(uuid, task));
        manager.recordAction(uuid, task, 1);

        assertEquals(1, manager.tasks(uuid).count(task));
    }

    @Test
    void revealingOneSlotLeavesTheOthersHidden() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();
        PlayerData data = manager.tasks(uuid);

        assertNotNull(manager.reveal(uuid, 2).revealedId());

        for (int slot = 0; slot < data.tasks().size(); slot++) {
            assertEquals(slot == 2, data.isRevealed(data.tasks().get(slot)), "slot " + slot);
        }
    }

    @Test
    void revealingTheSameSlotTwiceChangesNothing() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();

        assertNotNull(manager.reveal(uuid, 0).revealedId());
        assertNull(manager.reveal(uuid, 0).revealedId());
    }

    @Test
    void anEmptySlotCannotBeRevealed() {
        ActivityManager manager = TestManagers.manager(defs(3));
        UUID uuid = UUID.randomUUID();

        assertNull(manager.reveal(uuid, 5).revealedId());
        assertNull(manager.reveal(uuid, -1).revealedId());
    }

    // /activity add is the admin's testing aid and is not gated
    @Test
    void theAdminAddBypassesTheGate() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();
        String hidden = manager.tasks(uuid).tasks().get(0);
        String other = undrawn(manager, uuid, 20);

        assertEquals(Recorded.RECORDED, manager.recordActionUngated(uuid, hidden, 1));
        assertEquals(Recorded.RECORDED, manager.recordActionUngated(uuid, other, 1));

        assertEquals(1, manager.tasks(uuid).count(hidden));
        assertEquals(1, manager.tasks(uuid).count(other));
    }

    @Test
    void anUnknownActivityIsStillRejected() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();

        assertEquals(Recorded.UNKNOWN, manager.recordAction(uuid, "nope", 1));
        assertEquals(Recorded.UNKNOWN, manager.recordActionUngated(uuid, "nope", 1));
        assertFalse(manager.isTracked(uuid, "nope"));
    }

    // ====================================
    // No draw is made off the record path: a listener event for a player who
    // has not opened the GUI today must leave no row behind in players.yml.
    // ====================================
    @Test
    void aListenerEventForAPlayerWithNoDrawCreatesNoRow() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();

        assertEquals(Recorded.NOT_A_TASK, manager.recordAction(uuid, "a0", 1));

        assertNull(manager.getStore().peek(uuid), "the record path created a players.yml row");
    }

    @Test
    void isTrackedIsFalseAndCreatesNoRowBeforeTheFirstDraw() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();

        assertFalse(manager.isTracked(uuid, "a0"));

        assertNull(manager.getStore().peek(uuid), "isTracked created a players.yml row");
    }

    // The GUI paths are the ones allowed to draw, and only they create the row
    @Test
    void openingTheGuiIsWhatDraws() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();

        assertEquals(PlayerData.TASKS_PER_DAY, manager.tasks(uuid).tasks().size());
        assertNotNull(manager.getStore().peek(uuid));
    }

    // ====================================
    // A reload that drops one of the drawn activities compacts the draw and
    // tops it back up, so the slot the player clicked is no longer the one
    // they saw. reveal() has to say so - the GUI repaints every task slot on
    // it rather than just the clicked one.
    // ====================================
    @Test
    void aDrawChangedMidClickIsReported() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();
        String dropped = manager.tasks(uuid).tasks().get(0);

        TestManagers.unload(manager, dropped);
        ActivityManager.Reveal reveal = manager.reveal(uuid, 0);

        assertTrue(reveal.drawChanged(), "a compacted draw was not reported");
        List<String> tasks = manager.tasks(uuid).tasks();
        assertEquals(PlayerData.TASKS_PER_DAY, tasks.size());
        assertFalse(tasks.contains(dropped));
        // The clicked id is gone, so nothing was revealed - the slot now holds
        // a task the player never asked about
        assertNull(reveal.revealedId());
        for (String task : tasks) {
            assertFalse(manager.getStore().get(uuid).isRevealed(task), task + " was revealed by proxy");
        }
    }

    // ====================================
    // The id is what is revealed, not the index: a reload that dropped an
    // earlier task compacts the draw, so the clicked id has moved down a slot
    // by the time the reveal lands.
    // ====================================
    @Test
    void aCompactedDrawStillRevealsTheClickedTask() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();
        List<String> before = List.copyOf(manager.tasks(uuid).tasks());
        String clicked = before.get(3);

        TestManagers.unload(manager, before.get(0));
        ActivityManager.Reveal reveal = manager.reveal(uuid, 3);

        assertTrue(reveal.drawChanged());
        assertEquals(clicked, reveal.revealedId());
        assertTrue(manager.getStore().get(uuid).isRevealed(clicked));
    }

    // ====================================
    // A 'daily-guaranteed' activity is in every player's draw, in a slot the
    // shuffle picks - the config warning, not the draw, is what deals with
    // more guaranteed activities than there are slots.
    // ====================================
    @Test
    void aGuaranteedActivityIsInEveryDrawAtAVaryingSlot() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.guarantee(manager, "a3");
        Set<Integer> slots = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            List<String> tasks = manager.tasks(UUID.randomUUID()).tasks();

            assertEquals(PlayerData.TASKS_PER_DAY, tasks.size());
            assertTrue(tasks.contains("a3"), "a guaranteed activity was left out: " + tasks);
            slots.add(tasks.indexOf("a3"));
        }

        assertTrue(slots.size() > 1, "the guaranteed activity always landed in slot " + slots);
    }

    // A reload that drops the guaranteed activity itself: it is simply not
    // drawable any more, and the draw is still full
    @Test
    void anUnloadedGuaranteedActivityIsNotDrawn() {
        ActivityManager manager = TestManagers.manager(defs(20));
        TestManagers.guarantee(manager, "a3");
        TestManagers.unload(manager, "a3");

        List<String> tasks = manager.tasks(UUID.randomUUID()).tasks();

        assertEquals(PlayerData.TASKS_PER_DAY, tasks.size());
        assertFalse(tasks.contains("a3"));
    }

    // ====================================
    // Marking an activity guaranteed after a player already has today's draw
    // must not leave them without it for the rest of the day
    // ====================================
    @Test
    void anExistingDrawGainsAGuaranteedActivityOnItsNextUse() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();
        List<String> before = List.copyOf(manager.tasks(uuid).tasks());
        String guaranteed = before.contains("a3") ? undrawn(manager, uuid, 20) : "a3";

        TestManagers.guarantee(manager, guaranteed);
        List<String> after = manager.tasks(uuid).tasks();

        assertTrue(after.contains(guaranteed));
        assertEquals(PlayerData.TASKS_PER_DAY, after.size());
        assertEquals(6, after.stream().filter(before::contains).count(), "the whole draw was re-rolled");
    }

    @Test
    void anUnchangedDrawIsNotReportedAsChanged() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();
        manager.tasks(uuid);

        assertFalse(manager.reveal(uuid, 0).drawChanged());
    }
}
