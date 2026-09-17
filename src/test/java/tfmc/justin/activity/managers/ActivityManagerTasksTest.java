package tfmc.justin.activity.managers;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        assertTrue(manager.reveal(uuid, 0));
        assertTrue(manager.isTracked(uuid, task));
        manager.recordAction(uuid, task, 1);

        assertEquals(1, manager.tasks(uuid).count(task));
    }

    @Test
    void revealingOneSlotLeavesTheOthersHidden() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();
        PlayerData data = manager.tasks(uuid);

        assertTrue(manager.reveal(uuid, 2));

        for (int slot = 0; slot < data.tasks().size(); slot++) {
            assertEquals(slot == 2, data.isRevealed(data.tasks().get(slot)), "slot " + slot);
        }
    }

    @Test
    void revealingTheSameSlotTwiceChangesNothing() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();

        assertTrue(manager.reveal(uuid, 0));
        assertFalse(manager.reveal(uuid, 0));
    }

    @Test
    void anEmptySlotCannotBeRevealed() {
        ActivityManager manager = TestManagers.manager(defs(3));
        UUID uuid = UUID.randomUUID();

        assertFalse(manager.reveal(uuid, 5));
        assertFalse(manager.reveal(uuid, -1));
    }

    // /activity add is the admin's testing aid and is not gated
    @Test
    void theAdminAddBypassesTheGate() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();
        String hidden = manager.tasks(uuid).tasks().get(0);
        String other = undrawn(manager, uuid, 20);

        assertTrue(manager.recordActionUngated(uuid, hidden, 1));
        assertTrue(manager.recordActionUngated(uuid, other, 1));

        assertEquals(1, manager.tasks(uuid).count(hidden));
        assertEquals(1, manager.tasks(uuid).count(other));
    }

    @Test
    void anUnknownActivityIsStillRejected() {
        ActivityManager manager = TestManagers.manager(defs(20));
        UUID uuid = UUID.randomUUID();

        assertFalse(manager.recordAction(uuid, "nope", 1));
        assertFalse(manager.recordActionUngated(uuid, "nope", 1));
        assertFalse(manager.isTracked(uuid, "nope"));
    }
}
