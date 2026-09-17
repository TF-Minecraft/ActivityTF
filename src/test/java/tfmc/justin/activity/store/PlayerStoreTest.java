package tfmc.justin.activity.store;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// PlayerStore's parsing/snapshot rules are pure logic over a
// ConfigurationSection / PlayerData, but PlayerStore itself needs a live
// JavaPlugin + ActivityConfiguration to construct, which this suite cannot
// build. Both rules are therefore exercised through the package-private
// statics the instance methods delegate to:
//
//   - readEntry(ConfigurationSection root, String key, int barMax, int dailyMax, Predicate), returning
//     null for a missing section or a key that is not a UUID
//   - snapshot(Map<UUID, PlayerData>)
// ====================================
class PlayerStoreTest {

    private static final int BAR_MAX = 20;
    private static final int DAILY_MAX = 10;
    // Stands in for "this id is a loaded activity"; the unknown-task test
    // below hands in a narrower one
    private static final Predicate<String> KNOWN = id -> true;

    @Test
    void pointsAreFlooredAtZeroAndCappedAtBarMax() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("points", 999);
        entry.set("claimed-points", 0);

        PlayerData data = PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX, KNOWN);
        assertEquals(BAR_MAX, data.points());

        UUID negativeId = UUID.randomUUID();
        ConfigurationSection negative = root.createSection(negativeId.toString());
        negative.set("points", -5);
        negative.set("claimed-points", 0);
        PlayerData negativeData = PlayerStore.readEntry(root, negativeId.toString(), BAR_MAX, DAILY_MAX, KNOWN);
        assertEquals(0, negativeData.points());
    }

    @Test
    void claimedPointsIsFlooredAtZeroAndCappedAtPoints() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("points", 10);
        entry.set("claimed-points", 999);

        PlayerData data = PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX, KNOWN);
        assertEquals(10, data.claimedPoints());

        UUID negId = UUID.randomUUID();
        ConfigurationSection negEntry = root.createSection(negId.toString());
        negEntry.set("points", 10);
        negEntry.set("claimed-points", -5);
        PlayerData negData = PlayerStore.readEntry(root, negId.toString(), BAR_MAX, DAILY_MAX, KNOWN);
        assertEquals(0, negData.claimedPoints());
    }

    @Test
    void everyDailyValueIsFlooredAtZero() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("points", 5);
        entry.set("claimed-points", 0);
        entry.set("daily.vote", -3);
        entry.set("daily.quest", 2);

        PlayerData data = PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX, KNOWN);
        assertEquals(0, data.count("vote"));
        assertEquals(2, data.count("quest"));
    }

    @Test
    void malformedUuidKeyIsSkippedNotFatal() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        ConfigurationSection entry = root.createSection("not-a-uuid");
        entry.set("points", 5);

        PlayerData data = PlayerStore.readEntry(root, "not-a-uuid", BAR_MAX, DAILY_MAX, KNOWN);
        assertNull(data);
    }

    @Test
    void aMissingSectionIsSkipped() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");

        assertNull(PlayerStore.readEntry(root, UUID.randomUUID().toString(), BAR_MAX, DAILY_MAX, KNOWN));
    }

    @Test
    void emptyEntryIsOmittedFromTheSnapshot() {
        UUID id = UUID.randomUUID();
        PlayerData empty = new PlayerData("2026-09-07", "2026-09-09");

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, empty));
        assertFalse(yaml.contains("players." + id));
    }

    @Test
    void dailyPointsRoundTripsThroughSnapshotAndReadEntry() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(5, 7, "2026-09-07", "2026-09-09", 0, Map.of());

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, data));
        ConfigurationSection players = yaml.getConfigurationSection("players");
        PlayerData parsed = PlayerStore.readEntry(players, id.toString(), BAR_MAX, DAILY_MAX, KNOWN);

        assertEquals(7, parsed.dailyPoints());
    }

    // ====================================
    // /activity add --force awards past both daily limits, so what it leaves
    // behind has to survive a save and a load unchanged: parse() clamps points
    // to bar.max and daily-points to bar.daily-max, and a forced add that
    // pushed either past its clamp would be silently cut back on the next
    // restart - memory and disk disagreeing about a live player.
    // ====================================
    @Test
    void aForcedAwardAgreesBetweenMemoryAndDisk() throws InvalidConfigurationException {
        UUID id = UUID.randomUUID();
        PlayerData inMemory = new PlayerData(0, 0, "2026-09-07", "2026-09-09", 0, Map.of());
        // every: 1, points: 1, daily-cap: 5 - the shipped vote activity, with
        // BAR_MAX (20) well under what 50 actions are worth uncapped
        ActivityDef vote = new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5);
        inMemory.recordForced(50, vote, BAR_MAX, List.of());

        // Through the file's own text, not just the in-memory snapshot: the
        // daily counts go in as a plain Map and only become a section once
        // YAML has been written and read back
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.loadFromString(PlayerStore.snapshot(Map.of(id, inMemory)).saveToString());
        PlayerData fromDisk = PlayerStore.readEntry(onDisk.getConfigurationSection("players"),
            id.toString(), BAR_MAX, DAILY_MAX, KNOWN);

        assertEquals(inMemory.points(), fromDisk.points());
        assertEquals(inMemory.dailyPoints(), fromDisk.dailyPoints());
        assertEquals(inMemory.claimedPoints(), fromDisk.claimedPoints());
        assertEquals(inMemory.count("vote"), fromDisk.count("vote"));
        // and neither clamp had anything to cut
        assertEquals(BAR_MAX, fromDisk.points());
        assertEquals(0, fromDisk.dailyPoints());
        assertEquals(50, fromDisk.count("vote"));
    }

    @Test
    void dailyPointsIsFlooredAtZeroAndCappedAtDailyMax() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("points", 5);
        entry.set("claimed-points", 0);
        entry.set("daily-points", 999);

        PlayerData data = PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX, KNOWN);
        assertEquals(DAILY_MAX, data.dailyPoints());

        UUID negId = UUID.randomUUID();
        ConfigurationSection negEntry = root.createSection(negId.toString());
        negEntry.set("points", 5);
        negEntry.set("claimed-points", 0);
        negEntry.set("daily-points", -3);
        PlayerData negData = PlayerStore.readEntry(root, negId.toString(), BAR_MAX, DAILY_MAX, KNOWN);
        assertEquals(0, negData.dailyPoints());
    }

    @Test
    void missingOrNonIntegerDailyPointsDefaultsToZero() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");

        UUID missingId = UUID.randomUUID();
        ConfigurationSection missingEntry = root.createSection(missingId.toString());
        missingEntry.set("points", 5);
        PlayerData missingData = PlayerStore.readEntry(root, missingId.toString(), BAR_MAX, DAILY_MAX, KNOWN);
        assertEquals(0, missingData.dailyPoints());

        UUID badId = UUID.randomUUID();
        ConfigurationSection badEntry = root.createSection(badId.toString());
        badEntry.set("points", 5);
        badEntry.set("daily-points", "not-a-number");
        PlayerData badData = PlayerStore.readEntry(root, badId.toString(), BAR_MAX, DAILY_MAX, KNOWN);
        assertEquals(0, badData.dailyPoints());
    }

    @Test
    void aPlayerWithOnlyDailyPointsSetIsStillWritten() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(0, 5, "2026-09-07", "2026-09-09", 0, Map.of());

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, data));

        assertTrue(yaml.contains("players." + id));
        assertEquals(5, yaml.getInt("players." + id + ".daily-points"));
    }

    @Test
    void nonZeroEntryIsWrittenWithAllExpectedKeys() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(5, 0, "2026-09-07", "2026-09-09", 0, Map.of("vote", 1));

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, data));
        String path = "players." + id;
        assertTrue(yaml.isSet(path + ".points"));
        assertTrue(yaml.isSet(path + ".week"));
        assertTrue(yaml.isSet(path + ".day"));
        assertTrue(yaml.isSet(path + ".claimed-points"));
        assertTrue(yaml.isSet(path + ".daily-points"));
        assertTrue(yaml.isSet(path + ".daily"));
    }

    // ====================================
    // Today's draw and which of its slots have been revealed survive a
    // restart, or a player would be handed a fresh set of hidden tasks every
    // time the server came back up.
    // ====================================
    @Test
    void theDrawAndRevealedFlagsRoundTripThroughSnapshotAndReadEntry() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(0, 0, "2026-09-07", "2026-09-09", 0, Map.of(),
            List.of("vote", "quest", "mine"), Set.of("quest"));

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, data));
        ConfigurationSection players = yaml.getConfigurationSection("players");
        PlayerData parsed = PlayerStore.readEntry(players, id.toString(), BAR_MAX, DAILY_MAX, KNOWN);

        assertEquals(List.of("vote", "quest", "mine"), parsed.tasks());
        assertTrue(parsed.isRevealed("quest"));
        assertFalse(parsed.isRevealed("vote"));
        assertFalse(parsed.isRevealed("mine"));
    }

    // A player whose only state is today's draw is still worth writing, or
    // the draw would be lost on the first save
    @Test
    void aPlayerWithOnlyADrawIsStillWritten() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(0, 0, "2026-09-07", "2026-09-09", 0, Map.of(),
            List.of("vote"), Set.of());

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, data));

        assertTrue(yaml.contains("players." + id));
        assertEquals(List.of("vote"), yaml.getStringList("players." + id + ".tasks"));
    }

    // An activity dropped from config.yml is not a task any more
    @Test
    void aStoredTaskWhoseActivityIsGoneIsIgnored() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("points", 1);
        entry.set("tasks", List.of("vote", "removed", "quest"));
        entry.set("revealed", List.of("removed", "quest"));

        PlayerData data = PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX,
            candidate -> !candidate.equals("removed"));

        assertEquals(List.of("vote", "quest"), data.tasks());
        assertFalse(data.isRevealed("removed"));
        assertTrue(data.isRevealed("quest"));
    }

    // ====================================
    // A draw is all a row needs to be worth keeping: the player has been
    // handed today's tasks and a restart must not re-roll them.
    // ====================================
    @Test
    void aDrawOnlyEntryIsStillWritten() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(0, 0, "2026-09-07", "2026-09-09", 0, Map.of(),
            List.of("vote", "quest"), List.of());

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, data));

        assertEquals(List.of("vote", "quest"), yaml.getStringList("players." + id + ".tasks"));
    }

    // ====================================
    // revealed() is a hash set, so its iteration order is not the order the
    // ids went in - written as-is it would churn the file between saves.
    // ====================================
    @Test
    void revealedIsWrittenSortedSoTheFileDoesNotChurn() {
        UUID id = UUID.randomUUID();
        List<String> tasks = List.of("vote", "quest", "mine", "fish", "cook", "craft", "sell");
        PlayerData one = new PlayerData(0, 0, "2026-09-07", "2026-09-09", 0, Map.of(), tasks, tasks);
        PlayerData two = new PlayerData(0, 0, "2026-09-07", "2026-09-09", 0, Map.of(), tasks,
            List.of("sell", "craft", "cook", "fish", "mine", "quest", "vote"));

        List<String> first = PlayerStore.snapshot(Map.of(id, one)).getStringList("players." + id + ".revealed");
        List<String> second = PlayerStore.snapshot(Map.of(id, two)).getStringList("players." + id + ".revealed");

        assertEquals(List.of("cook", "craft", "fish", "mine", "quest", "sell", "vote"), first);
        assertEquals(first, second);
    }

    // ====================================
    // rerolls: same round-trip and clamping rules as the other counters on
    // this entry - a missing key defaults to zero, a negative or garbled
    // stored value is clamped rather than trusted, and the count survives a
    // save/load cycle.
    // ====================================
    @Test
    void rerollsRoundTripsThroughSnapshotAndReadEntry() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(0, 0, "w", "d", 0, Map.of(), List.of(), Set.of(), 2);

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, data));
        ConfigurationSection players = yaml.getConfigurationSection("players");
        PlayerData parsed = PlayerStore.readEntry(players, id.toString(), BAR_MAX, DAILY_MAX, KNOWN);

        assertEquals(2, parsed.rerolls());
    }

    @Test
    void aMissingRerollsKeyLoadsAsZero() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("points", 1);

        PlayerData data = PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX, KNOWN);

        assertEquals(0, data.rerolls());
    }

    @Test
    void aNegativeStoredRerollsIsClampedToZero() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("points", 1);
        entry.set("rerolls", -4);

        PlayerData data = PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX, KNOWN);

        assertEquals(0, data.rerolls());
    }

    // A non-numeric value is exactly what getInt() already falls back to 0
    // for, the same as every other counter parsed off this entry
    @Test
    void aNonNumericStoredRerollsDoesNotCrashAndLoadsAsZero() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("points", 1);
        entry.set("rerolls", "not-a-number");

        PlayerData data = PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX, KNOWN);

        assertEquals(0, data.rerolls());
    }

    // A player whose only non-default state is a reroll count is still worth
    // writing, or a restart would hand their budget straight back to them
    @Test
    void aPlayerWithOnlyARerollCountIsStillWritten() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(0, 0, "w", "d", 0, Map.of(), List.of(), Set.of(), 1);

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, data));

        assertTrue(yaml.contains("players." + id));
        assertEquals(1, yaml.getInt("players." + id + ".rerolls"));
    }

    // ====================================
    // Disk and memory must end up with the same draw when an activity is
    // removed: the read path drops the dead id, the in-memory path drops it
    // too, and both are topped back up to TASKS_PER_DAY on next use.
    // ====================================
    @Test
    void aDrawWithARemovedActivityAgreesBetweenDiskAndMemory() {
        List<String> stored = List.of("a0", "gone", "a1", "a2", "a3", "a4", "a5");
        List<String> loadedIds = List.of("a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7");

        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("tasks", stored);
        PlayerData fromDisk = PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX,
            loadedIds::contains);

        PlayerData inMemory = new PlayerData(0, 0, "2026-09-07", "2026-09-09", 0, Map.of(), stored, List.of());

        fromDisk.ensureTasks(loadedIds, List.of(), new java.util.Random(1));
        inMemory.ensureTasks(loadedIds, List.of(), new java.util.Random(1));

        assertEquals(PlayerData.TASKS_PER_DAY, fromDisk.tasks().size());
        assertEquals(fromDisk.tasks(), inMemory.tasks());
        assertFalse(fromDisk.tasks().contains("gone"));
    }
}
