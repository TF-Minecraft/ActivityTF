package net.tfminecraft.activitytf.store;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.managers.TestManagers;
import net.tfminecraft.activitytf.models.ActivityDef;
import net.tfminecraft.activitytf.models.PlayerData;

import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStoreTest {

    private static final int BAR_MAX = 20;
    private static final int DAILY_MAX = 10;
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

    @Test
    void votePointsRoundTripThroughSnapshotAndReadEntry() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(5, 7, "2026-09-07", "2026-09-09", 0, Map.of());
        data.setVotePoints(3);

        ConfigurationSection players = PlayerStore.snapshot(Map.of(id, data)).getConfigurationSection("players");

        assertEquals(3, PlayerStore.readEntry(players, id.toString(), BAR_MAX, DAILY_MAX, KNOWN).votePoints());
    }

    @Test
    void aRowWithoutVotePointsFallsBackToWhatTodaysVotesAreWorth() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("points", 8);
        entry.set("daily-points", 8);
        entry.createSection("daily", Map.of("vote", 7, "free", 3));
        ActivityDef vote = new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5);

        assertEquals(5, PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX, KNOWN, vote).votePoints());
        assertEquals(0, PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX, KNOWN).votePoints());

        entry.set("daily-points", 2);
        assertEquals(2, PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX, KNOWN, vote).votePoints());
    }

    @Test
    void aForcedAwardAgreesBetweenMemoryAndDisk() throws InvalidConfigurationException {
        UUID id = UUID.randomUUID();
        PlayerData inMemory = new PlayerData(0, 0, "2026-09-07", "2026-09-09", 0, Map.of());
        ActivityDef vote = new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5);
        inMemory.recordForced(50, vote, BAR_MAX, List.of());

        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.loadFromString(PlayerStore.snapshot(Map.of(id, inMemory)).saveToString());
        PlayerData fromDisk = PlayerStore.readEntry(onDisk.getConfigurationSection("players"),
            id.toString(), BAR_MAX, DAILY_MAX, KNOWN);

        assertEquals(inMemory.points(), fromDisk.points());
        assertEquals(inMemory.dailyPoints(), fromDisk.dailyPoints());
        assertEquals(inMemory.claimedPoints(), fromDisk.claimedPoints());
        assertEquals(inMemory.count("vote"), fromDisk.count("vote"));
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

    @Test
    void aPlayerWithOnlyADrawIsStillWritten() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(0, 0, "2026-09-07", "2026-09-09", 0, Map.of(),
            List.of("vote"), Set.of());

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, data));

        assertTrue(yaml.contains("players." + id));
        assertEquals(List.of("vote"), yaml.getStringList("players." + id + ".tasks"));
    }

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

    @Test
    void aDrawOnlyEntryIsStillWritten() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(0, 0, "2026-09-07", "2026-09-09", 0, Map.of(),
            List.of("vote", "quest"), List.of());

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, data));

        assertEquals(List.of("vote", "quest"), yaml.getStringList("players." + id + ".tasks"));
    }

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

    @Test
    void aPlayerWithOnlyARerollCountIsStillWritten() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(0, 0, "w", "d", 0, Map.of(), List.of(), Set.of(), 1);

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, data));

        assertTrue(yaml.contains("players." + id));
        assertEquals(1, yaml.getInt("players." + id + ".rerolls"));
    }

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

    @Test
    void aRowWithoutTheDailyRewardKeyLoadsAsNotClaimed() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("points", 5);
        entry.set("tasks", List.of("a0"));

        PlayerData data = PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX, KNOWN);
        assertFalse(data.dailyRewardClaimed());
        assertEquals(5, data.points());
    }

    @Test
    void theDailyRewardFlagSurvivesASaveAndLoad() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(0, 0, "w", "d", 0, Map.of(), List.of("a0"), List.of("a0"));
        data.setDailyRewardClaimed(true);

        ConfigurationSection root = PlayerStore.snapshot(Map.of(id, data)).getConfigurationSection("players");
        assertTrue(PlayerStore.readEntry(root, id.toString(), BAR_MAX, DAILY_MAX, KNOWN).dailyRewardClaimed());
    }

    @TempDir
    Path directory;

    private static final ActivityDef VOTE = new ActivityDef("vote", "Vote", Material.PAPER, null, 1, 1, 5);

    @ParameterizedTest
    @ValueSource(strings = {"players: erased\n", "players: [alice, bob]\n", "players: 42\n"})
    void malformedPlayersValuesAreBackedUpBeforeAnEmptyStoreCanReplaceThem(String original) throws Exception {
        Fixture f = fixture();
        Files.writeString(f.file(), original);

        f.store.load();

        assertTrue(f.store.isLoaded());
        List<Path> backups = f.backups();
        assertEquals(1, backups.size(), "a non-section players value must never be silently discarded");
        assertEquals(original, Files.readString(backups.getFirst()));
        UUID id = UUID.randomUUID();
        f.store.get(id).recordForced(3, VOTE, BAR_MAX, List.of());
        assertTrue(f.store.saveNow());
        assertEquals(3, f.savedPoints(id));
        assertEquals(original, Files.readString(backups.getFirst()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"players: [\n", "unexpected: valuable-data\n"})
    void invalidYamlAndUnknownRootDataArePreservedInBackups(String original) throws Exception {
        Fixture f = fixture();
        Files.writeString(f.file(), original);

        f.store.load();

        assertTrue(f.store.isLoaded());
        assertEquals(1, f.backups().size());
        assertEquals(original, Files.readString(f.backups().getFirst()));
        assertEquals(original, Files.readString(f.file()));
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("kept a copy")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "players: {}\n"})
    void emptyAndExplicitlyEmptyStoresAreAcceptedWithoutQuarantine(String original) throws Exception {
        Fixture f = fixture();
        Files.writeString(f.file(), original);

        f.store.load();

        assertTrue(f.store.isLoaded());
        assertTrue(f.backups().isEmpty());
        assertNull(f.store.peek(UUID.randomUUID()));
    }

    @Test
    void loadReadsValidEntriesAndSkipsScalarEntriesAndMalformedUuids() throws Exception {
        Fixture f = fixture();
        UUID id = UUID.randomUUID();
        UUID scalarId = UUID.randomUUID();
        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id,
            new PlayerData(7, 2, "old-week", "old-day", 3, Map.of("vote", 2),
                List.of("vote", "gone"), List.of("vote", "gone"), 1)));
        yaml.set("players.not-a-uuid.points", 8);
        yaml.set("players." + scalarId, "not-an-entry");
        yaml.save(f.file().toFile());

        f.store.load();

        PlayerData data = f.store.peek(id);
        assertEquals(7, data.points());
        assertEquals(3, data.claimedPoints());
        assertEquals(2, data.count("vote"));
        assertEquals(List.of("vote"), data.tasks());
        assertTrue(data.isRevealed("vote"));
        assertFalse(data.isRevealed("gone"));
        assertEquals(1, data.rerolls());
        assertEquals("old-week", data.weekKey(), "peek must not roll stored data");
        assertNull(f.store.peek(scalarId));
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("Skipping malformed UUID 'not-a-uuid'")));
        assertTrue(f.backups().isEmpty());
    }

    @Test
    void failedQuarantineDisablesEveryWriteAndPreservesTheOriginalFile() throws Exception {
        Fixture f = fixture();
        String original = "players: lost-data\n";
        Files.writeString(f.file(), original);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(f.folder);
        try {
            Files.setPosixFilePermissions(f.folder, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE));
            f.store.load();
            assertFalse(f.store.isLoaded());
            assertTrue(f.backups().isEmpty());
        } finally {
            Files.setPosixFilePermissions(f.folder, permissions);
        }

        f.store.get(UUID.randomUUID()).recordForced(4, VOTE, BAR_MAX, List.of());
        assertFalse(f.store.saveNow());
        f.store.saveSoon();
        f.store.shutdown();
        assertEquals(original, Files.readString(f.file()));
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("copying it aside failed")));
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("Saving is disabled")));
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("Not writing")));
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("Not saving")));
    }

    @Test
    void aMissingStoreStartsEmptyAndDisabledPluginsSaveSynchronously() throws Exception {
        Fixture f = fixture();
        UUID id = UUID.randomUUID();
        assertFalse(f.store.isLoaded());
        assertNull(f.store.rolled(id));
        f.store.load();
        assertTrue(f.store.isLoaded());
        assertFalse(Files.exists(f.file()));
        PlayerData first = f.store.get(id);
        assertSame(first, f.store.get(id));
        assertEquals(f.config.currentKeys().week(), first.weekKey());
        first.recordForced(4, VOTE, BAR_MAX, List.of());

        f.store.saveSoon();

        assertEquals(4, f.savedPoints(id));
        assertTrue(f.pending.isEmpty());
        assertFalse(Files.exists(f.folder.resolve("players.yml.tmp")));
        PlayerStore reloaded = new PlayerStore(f.plugin, f.config);
        reloaded.load();
        assertEquals(4, reloaded.peek(id).points());
        assertEquals(first.daily(), reloaded.peek(id).daily());
    }

    @Test
    void autoSaveUsesTheConfiguredIntervalAndCapturesOnlyDirtySnapshots() throws Exception {
        Fixture f = fixture();
        when(f.plugin.isEnabled()).thenReturn(true);
        f.store.load();
        f.store.startAutoSave();
        verify(f.scheduler).runTaskTimer(eq(f.plugin), any(Runnable.class), eq(2400L), eq(2400L));
        f.tick.run();
        assertTrue(f.pending.isEmpty());
        UUID id = UUID.randomUUID();
        PlayerData data = f.store.get(id);
        data.recordForced(2, VOTE, BAR_MAX, List.of());
        f.tick.run();
        assertEquals(1, f.pending.size());
        data.recordForced(3, VOTE, BAR_MAX, List.of());
        assertFalse(Files.exists(f.file()));
        f.pending.getFirst().run();
        assertEquals(2, f.savedPoints(id), "queued save must retain its original snapshot");
        f.tick.run();
        assertEquals(1, f.pending.size());

        f.store.markDirty();
        f.tick.run();
        assertEquals(2, f.pending.size());
        f.pending.get(1).run();
        assertEquals(5, f.savedPoints(id));
    }

    @Test
    void olderAsyncSnapshotsCannotOverwriteANewerCompletedSave() throws Exception {
        Fixture f = fixture();
        when(f.plugin.isEnabled()).thenReturn(true);
        f.store.load();
        UUID id = UUID.randomUUID();
        PlayerData data = f.store.get(id);
        data.recordForced(2, VOTE, BAR_MAX, List.of());
        f.store.saveSoon();
        data.recordForced(3, VOTE, BAR_MAX, List.of());
        f.store.saveSoon();

        f.pending.get(1).run();
        f.pending.getFirst().run();

        assertEquals(5, f.savedPoints(id));
        data.recordForced(1, VOTE, BAR_MAX, List.of());
        f.store.saveSoon();
        data.recordForced(1, VOTE, BAR_MAX, List.of());
        assertTrue(f.store.saveNow());
        f.pending.get(2).run();
        assertEquals(7, f.savedPoints(id));
    }

    @Test
    void shutdownCancelsAutoSaveWritesLatestStateAndRejectsLateSaves() throws Exception {
        Fixture f = fixture();
        when(f.plugin.isEnabled()).thenReturn(true);
        f.store.load();
        f.store.startAutoSave();
        UUID id = UUID.randomUUID();
        PlayerData data = f.store.get(id);
        data.recordForced(2, VOTE, BAR_MAX, List.of());
        f.store.saveSoon();
        data.recordForced(3, VOTE, BAR_MAX, List.of());

        f.store.shutdown();

        verify(f.timer).cancel();
        assertEquals(5, f.savedPoints(id));
        f.pending.getFirst().run();
        data.recordForced(1, VOTE, BAR_MAX, List.of());
        f.store.saveSoon();
        assertEquals(1, f.pending.size());
        assertEquals(5, f.savedPoints(id));
    }

    @Test
    void failedWritesKeepTheLastGoodFileAndRetryOnTheNextAutoSave() throws Exception {
        Fixture f = fixture();
        when(f.plugin.isEnabled()).thenReturn(true);
        f.store.load();
        UUID id = UUID.randomUUID();
        PlayerData data = f.store.get(id);
        data.recordForced(3, VOTE, BAR_MAX, List.of());
        assertTrue(f.store.saveNow());
        String original = Files.readString(f.file());
        data.recordForced(2, VOTE, BAR_MAX, List.of());
        Path temp = Files.createDirectory(f.folder.resolve("players.yml.tmp"));
        Path blocker = Files.writeString(temp.resolve("keep"), "occupied");
        assertFalse(f.store.saveNow());
        f.store.saveSoon();
        f.pending.getFirst().run();
        assertEquals(original, Files.readString(f.file()));
        assertTrue(f.logs.stream().anyMatch(line -> line.contains("Failed to write players.yml")));
        f.store.startAutoSave();
        f.tick.run();
        assertEquals(2, f.pending.size(), "a failed async write must restore the dirty flag");

        Files.delete(blocker);
        Files.delete(temp);
        f.pending.get(1).run();

        assertEquals(5, f.savedPoints(id));
        f.tick.run();
        assertEquals(2, f.pending.size());
    }

    @Test
    void aBlockedDestinationIsPreservedAndCanBeSavedAfterItIsRepaired() throws Exception {
        Fixture f = fixture();
        f.store.load();
        UUID id = UUID.randomUUID();
        f.store.get(id).recordForced(3, VOTE, BAR_MAX, List.of());
        Files.createDirectory(f.file());
        Path blocker = Files.writeString(f.file().resolve("keep"), "preserved");

        assertFalse(f.store.saveNow());
        assertEquals("preserved", Files.readString(blocker));
        Files.delete(blocker);
        Files.delete(f.file());
        assertTrue(f.store.saveNow());
        assertEquals(3, f.savedPoints(id));
    }

    @Test
    void anUnsupportedAtomicMoveFallsBackToReplacingTheRealFile() throws Exception {
        Fixture f = fixture();
        f.store.load();
        UUID id = UUID.randomUUID();
        PlayerData data = f.store.get(id);
        data.recordForced(2, VOTE, BAR_MAX, List.of());
        assertTrue(f.store.saveNow());
        data.recordForced(3, VOTE, BAR_MAX, List.of());
        Path temp = f.folder.resolve("players.yml.tmp");
        Path destination = f.file();

        // This filesystem supports atomic moves. Simulate only that capability rejection;
        // serialization, the fallback move, and the reloaded file all use the real filesystem.
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(temp, destination,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE))
                .thenThrow(new AtomicMoveNotSupportedException(temp.toString(), destination.toString(),
                    "atomic rename unsupported"));

            assertTrue(f.store.saveNow());

            files.verify(() -> Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING));
        }
        assertEquals(5, f.savedPoints(id));
        assertFalse(Files.exists(temp));
        assertTrue(f.logs.isEmpty());
    }

    @Test
    void rollingAndClampingLoadedPlayersMakesAutoSavePersistTheUpdatedState() throws Exception {
        Fixture f = fixture();
        when(f.plugin.isEnabled()).thenReturn(true);
        UUID old = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        ActivityConfiguration.Keys keys = f.config.currentKeys();
        PlayerStore.snapshot(Map.of(
            old, new PlayerData(8, 2, "old-week", "old-day", 3, Map.of("vote", 2)),
            current, new PlayerData(8, 2, keys.week(), keys.day(), 3, Map.of("vote", 2))))
            .save(f.file().toFile());
        f.store.load();
        f.store.startAutoSave();
        f.tick.run();
        assertTrue(f.pending.isEmpty());
        assertEquals(8, f.store.peek(old).points());
        PlayerData rolled = f.store.rolled(old);
        assertEquals(0, rolled.points());
        assertEquals(keys.week(), rolled.weekKey());
        assertEquals(keys.day(), rolled.dayKey());
        TestManagers.limits(f.manager, 4, DAILY_MAX);
        assertEquals(4, f.store.rolled(current).points());

        f.tick.run();
        f.pending.getFirst().run();

        assertEquals(0, f.savedPoints(old));
        assertEquals(4, f.savedPoints(current));
        assertSame(rolled, f.store.rolled(old));
        f.tick.run();
        assertEquals(1, f.pending.size());
    }

    private Fixture fixture() throws Exception {
        return new Fixture(Files.createDirectories(directory.resolve("plugin")));
    }

    private static final class Fixture {
        final Path folder;
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final BukkitTask timer = mock(BukkitTask.class);
        final ActivityManager manager = TestManagers.manager(VOTE);
        final ActivityConfiguration config = manager.getConfiguration();
        final PlayerStore store;
        final List<Runnable> pending = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        Runnable tick;

        Fixture(Path folder) throws Exception {
            this.folder = folder;
            TestManagers.limits(manager, BAR_MAX, DAILY_MAX);
            var interval = ActivityConfiguration.class.getDeclaredField("saveIntervalMinutes");
            interval.setAccessible(true);
            interval.set(config, 2);
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) { logs.add(record.getMessage()); }
                @Override public void flush() { }
                @Override public void close() { }
            });
            Server server = mock(Server.class);
            when(plugin.getDataFolder()).thenReturn(folder.toFile());
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getServer()).thenReturn(server);
            when(server.getScheduler()).thenReturn(scheduler);
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(call -> {
                    tick = call.getArgument(1);
                    return timer;
                });
            when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class)))
                .thenAnswer(call -> {
                    pending.add(call.getArgument(1));
                    return mock(BukkitTask.class);
                });
            store = new PlayerStore(plugin, config);
        }

        Path file() { return folder.resolve(PlayerStore.FILE); }

        List<Path> backups() throws Exception {
            try (var paths = Files.list(folder)) {
                return paths.filter(path -> path.getFileName().toString().startsWith("players.yml.corrupt-"))
                    .toList();
            }
        }

        int savedPoints(UUID id) throws Exception {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file().toFile());
            return yaml.getInt("players." + id + ".points");
        }
    }
}
