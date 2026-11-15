package net.tfminecraft.activitytf.managers;

import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.models.ActivityDef;
import net.tfminecraft.activitytf.models.PlayerData;
import net.tfminecraft.activitytf.models.RecordResult;
import net.tfminecraft.activitytf.models.Recorded;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tasks dealt by profession, auto-revealed, held votes and the progress-keeping reroll. */
class DailyTasksTest {

    private final List<UUID> online = new ArrayList<>();

    private static ActivityDef def(String id, List<String> requires, boolean reveal) {
        return new ActivityDef(id, id, Material.PAPER, null, 1, 1, 2, List.of(), List.of(), requires, reveal);
    }

    private static ActivityDef general(String id) {
        return def(id, List.of(), false);
    }

    private static void config(ActivityManager manager, String field, Object value) {
        try {
            Field f = ActivityConfiguration.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(manager.getConfiguration(), value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private ActivityManager manager(ActivityDef... defs) {
        TestManagers.bukkit();
        ActivityManager manager = TestManagers.manager(defs);
        TestManagers.storeLoaded(manager);
        TestManagers.messages(manager);
        TestManagers.guarantee(manager, "vote");
        config(manager, "autoReveal", true);
        return manager;
    }

    private Player join(Set<String> permissions, List<String> chat) {
        UUID uuid = UUID.randomUUID();
        Player player = TestManagers.player(uuid, permissions, chat);
        TestManagers.online(player);
        online.add(uuid);
        return player;
    }

    @BeforeEach
    void clear() {
        online.clear();
    }

    @AfterEach
    void leave() {
        online.forEach(TestManagers::offline);
    }

    @Test
    void joiningDealsOnlyTasksThePlayerQualifiesForAndRevealsAllButVoting() {
        ActivityManager manager = manager(def("vote", List.of(), true), general("cook"), general("chat"),
            def("splint", List.of("professions.physician"), false),
            def("hook", List.of("other.node", "professions.fisher"), false));
        Set<String> permissions = new HashSet<>();
        List<String> chat = new ArrayList<>();
        Player player = join(permissions, chat);

        manager.onJoin(player);

        PlayerData data = manager.getStore().get(player.getUniqueId());
        assertEquals(Set.of("vote", "cook", "chat"), Set.copyOf(data.tasks()));
        assertEquals(Set.of("cook", "chat"), data.revealed());

        permissions.add("professions.fisher");
        manager.tasks(player.getUniqueId());
        assertTrue(data.tasks().contains("hook"), "a newly earned profession task was not dealt");
        assertTrue(data.isRevealed("hook"));
        assertFalse(data.tasks().contains("splint"));

        manager.recordAction(player.getUniqueId(), "hook", 1);
        permissions.remove("professions.fisher");
        manager.tasks(player.getUniqueId());
        assertTrue(data.tasks().contains("hook"), "a started task was taken away");
    }

    @Test
    void aTaskWithNoProgressGoesWhenThePlayerStopsQualifying() {
        ActivityManager manager = manager(def("vote", List.of(), true), general("cook"),
            def("splint", List.of("professions.physician"), false));
        Set<String> permissions = new HashSet<>(Set.of("professions.physician"));
        Player player = join(permissions, new ArrayList<>());
        PlayerData data = manager.tasks(player.getUniqueId());
        assertTrue(data.tasks().contains("splint"));

        permissions.clear();
        manager.tasks(player.getUniqueId());

        assertFalse(data.tasks().contains("splint"));
        assertFalse(data.isRevealed("splint"));
    }

    @Test
    void votesBeforeTheClickAreHeldThenCreditedWithTheFirstPointsHint() {
        ActivityManager manager = manager(def("vote", List.of(), true), general("cook"));
        List<String> chat = new ArrayList<>();
        Player player = join(new HashSet<>(), chat);
        UUID uuid = player.getUniqueId();

        assertEquals(Recorded.HELD, manager.recordAction(uuid, "vote", 2));
        PlayerData data = manager.getStore().get(uuid);
        assertEquals(0, data.points());
        assertEquals(2, data.pending().get("vote"));

        manager.reveal(uuid, data.tasks().indexOf("vote"));

        assertEquals(2, data.points());
        assertTrue(data.pending().isEmpty());
        String hint = manager.getConfiguration().messages().get("first-points-today");
        assertEquals(1, chat.stream().filter(hint::equals).count());

        manager.recordAction(uuid, "cook", 1);
        assertEquals(1, chat.stream().filter(hint::equals).count(), "the hint came again the same day");
        assertEquals(Recorded.ACTIVITY_CAP, manager.recordAction(uuid, "vote", 1));
    }

    @Test
    void revealingVotingWithNothingHeldCreditsNothing() {
        ActivityManager manager = manager(def("vote", List.of(), true), general("cook"));
        Player player = join(new HashSet<>(), new ArrayList<>());
        PlayerData data = manager.tasks(player.getUniqueId());

        assertEquals("vote", manager.reveal(player.getUniqueId(), data.tasks().indexOf("vote")).revealedId());

        assertEquals(0, data.points());
    }

    @Test
    void offlineCreditOnlyCountsForWhatWasDealtWhileOnline() {
        ActivityManager manager = manager(def("vote", List.of(), true), general("cook"),
            def("secret", List.of(), true));
        UUID stranger = UUID.randomUUID();
        assertEquals(Recorded.NOT_A_TASK, manager.recordAction(stranger, "cook", 1));
        assertNull(manager.getStore().peek(stranger));
        assertEquals(Recorded.HELD, manager.recordAction(stranger, "vote", 1), "a first-ever vote was lost");
        assertEquals(1, manager.getStore().peek(stranger).pending().get("vote"));

        UUID away = UUID.randomUUID();
        PlayerData data = manager.getStore().get(away);
        assertEquals(Recorded.HELD, manager.recordAction(away, "vote", 1), "a vote before today's deal was lost");
        data.reroll(List.of("cook"), 50);
        assertEquals(Recorded.NOT_A_TASK, manager.recordAction(away, "secret", 1));
        assertEquals(Recorded.NOT_A_TASK, manager.recordAction(away, "cook", 1));
        assertFalse(manager.isTracked(away, "cook"));
        data.markRevealed("cook");
        assertTrue(manager.isTracked(away, "cook"));
        assertFalse(manager.isTracked(away, "missing"));
    }

    @Test
    void withoutAutoRevealAHiddenVoteIsNotHeld() {
        ActivityManager manager = manager(def("vote", List.of(), true), general("cook"));
        config(manager, "autoReveal", false);
        Player player = join(new HashSet<>(), new ArrayList<>());

        assertEquals(Recorded.NOT_A_TASK, manager.recordAction(player.getUniqueId(), "vote", 1));
        PlayerData data = manager.getStore().get(player.getUniqueId());
        assertTrue(data.revealed().isEmpty());
        assertTrue(data.pending().isEmpty());
        assertTrue(manager.isTracked(player.getUniqueId(), "cook") == data.isRevealed("cook"));
    }

    @Test
    void professionSlotsDealProfessionTasksFirst() {
        List<ActivityDef> defs = new ArrayList<>(List.of(def("vote", List.of(), true)));
        for (int i = 0; i < 10; i++) {
            defs.add(general("g" + i));
        }
        defs.add(def("p1", List.of("professions.x"), false));
        defs.add(def("p2", List.of("professions.x"), false));
        defs.add(def("p3", List.of("professions.x"), false));
        ActivityManager manager = manager(defs.toArray(ActivityDef[]::new));
        config(manager, "professionSlots", 2);
        Player player = join(new HashSet<>(Set.of("professions.x")), new ArrayList<>());

        PlayerData data = manager.tasks(player.getUniqueId());

        assertEquals(PlayerData.TASKS_PER_DAY, data.tasks().size());
        assertEquals(2, data.tasks().stream().filter(id -> id.startsWith("p")).count());
    }

    @Test
    void professionTasksAreSwappedInWhenThePermissionArrivesAfterTheDeal() {
        List<ActivityDef> defs = new ArrayList<>(List.of(def("vote", List.of(), true)));
        for (int i = 0; i < 8; i++) {
            defs.add(general("g" + i));
        }
        defs.add(def("p1", List.of("professions.x"), false));
        defs.add(def("p2", List.of("professions.x"), false));
        defs.add(def("p3", List.of("professions.x"), false));
        ActivityManager manager = manager(defs.toArray(ActivityDef[]::new));
        config(manager, "professionSlots", 2);
        Set<String> permissions = new HashSet<>();
        Player player = join(permissions, new ArrayList<>());
        UUID uuid = player.getUniqueId();
        PlayerData data = manager.tasks(uuid);
        assertEquals(0, data.tasks().stream().filter(id -> id.startsWith("p")).count());
        String started = data.tasks().stream().filter(id -> id.startsWith("g")).findFirst().orElseThrow();
        manager.recordAction(uuid, started, 1);

        permissions.add("professions.x");
        manager.tasks(uuid);

        assertEquals(PlayerData.TASKS_PER_DAY, data.tasks().size());
        assertEquals(2, data.tasks().stream().filter(id -> id.startsWith("p")).count());
        assertTrue(data.tasks().contains(started), "a started task was swapped out");
        assertTrue(data.tasks().contains("vote"));
        assertTrue(data.tasks().stream().filter(id -> !id.equals("vote")).allMatch(data::isRevealed));
    }

    @Test
    void aRerollKeepsTheProfessionSlotsFilled() {
        List<ActivityDef> defs = new ArrayList<>(List.of(def("vote", List.of(), true)));
        for (int i = 0; i < 12; i++) {
            defs.add(general("g" + i));
        }
        for (int i = 0; i < 6; i++) {
            defs.add(def("p" + i, List.of("professions.x"), false));
        }
        ActivityManager manager = manager(defs.toArray(ActivityDef[]::new));
        config(manager, "professionSlots", 2);
        TestManagers.rerollsPerDay(manager, 1);
        Player player = join(new HashSet<>(Set.of("professions.x")), new ArrayList<>());
        UUID uuid = player.getUniqueId();
        PlayerData data = manager.tasks(uuid);

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));

        assertEquals(2, data.tasks().stream().filter(id -> id.startsWith("p")).count());
    }

    @Test
    void eligibilityNeedsADefinitionAndAnyListedPermission() {
        ActivityManager manager = manager(general("cook"), def("hook", List.of("a", "b"), false));
        Player player = join(new HashSet<>(Set.of("b")), new ArrayList<>());

        assertFalse(manager.eligible(player, "gone"));
        assertTrue(manager.eligible(null, "cook"));
        assertFalse(manager.eligible(null, "hook"));
        assertTrue(manager.eligible(player, "hook"));
        assertFalse(manager.eligible(join(new HashSet<>(), new ArrayList<>()), "hook"));
    }

    @Test
    void aRerollSwapsOnlyUnstartedTasksAndKeepsEveryPoint() {
        List<ActivityDef> defs = new ArrayList<>(List.of(def("vote", List.of(), true)));
        for (int i = 0; i < 12; i++) {
            defs.add(general("g" + i));
        }
        ActivityManager manager = manager(defs.toArray(ActivityDef[]::new));
        TestManagers.rerollsPerDay(manager, 2);
        TestManagers.rerollMaxPoints(manager, 1);
        Player player = join(new HashSet<>(), new ArrayList<>());
        UUID uuid = player.getUniqueId();
        PlayerData data = manager.tasks(uuid);
        manager.recordAction(uuid, "vote", 1);
        manager.reveal(uuid, data.tasks().indexOf("vote"));
        String started = data.tasks().stream().filter(id -> !id.equals("vote")).findFirst().orElseThrow();
        manager.recordAction(uuid, started, 1);
        List<String> before = List.copyOf(data.tasks());
        int points = data.points();

        assertEquals(1, manager.rerollPaidPoints(data));
        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));

        assertEquals(points, data.points());
        assertEquals(before.indexOf("vote"), data.tasks().indexOf("vote"));
        assertEquals(before.indexOf(started), data.tasks().indexOf(started));
        assertTrue(data.isRevealed("vote"));
        assertEquals(5, before.stream().filter(id -> !data.tasks().contains(id)).count());
        assertTrue(data.tasks().stream().allMatch(data::isRevealed));
        assertEquals(1, data.rerolls());

        manager.recordAction(uuid, data.tasks().stream()
            .filter(id -> !id.equals("vote") && !id.equals(started)).findFirst().orElseThrow(), 1);
        assertEquals(ActivityManager.Rerolled.TOO_LATE, manager.reroll(uuid));
    }

    @Test
    void aRerollWithNothingToSwapIsRefused() {
        ActivityManager manager = manager(def("vote", List.of(), true), general("cook"));
        TestManagers.rerollsPerDay(manager, 1);
        Player player = join(new HashSet<>(), new ArrayList<>());

        assertEquals(ActivityManager.Rerolled.TOO_LATE, manager.reroll(player.getUniqueId()));
        assertEquals(0, manager.getStore().get(player.getUniqueId()).rerolls());
    }

    @Test
    void paidPointsSkipGuaranteedAndUnknownTasks() {
        ActivityManager manager = manager(def("vote", List.of(), true), general("cook"));
        Player player = join(new HashSet<>(), new ArrayList<>());
        PlayerData data = manager.tasks(player.getUniqueId());
        data.record(3, def("vote", List.of(), true), 50, 10, List.of());
        data.record(1, general("cook"), 50, 10, List.of());
        TestManagers.unload(manager, "cook");

        assertEquals(0, manager.rerollPaidPoints(data));
    }

    @Test
    void anAdminAddToAnUnrevealedVoteIsHeld() {
        ActivityManager manager = manager(def("vote", List.of(), true));
        Player player = join(new HashSet<>(), new ArrayList<>());

        RecordResult result = manager.recordAdmin(player.getUniqueId(), "vote", 2, false);

        assertEquals(Recorded.HELD, result.outcome());
    }
}
