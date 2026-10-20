package net.tfminecraft.activitytf.store;

import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import net.tfminecraft.activitytf.models.ReferralData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferralStoreTest {

    @TempDir
    Path directory;

    private JavaPlugin plugin;
    private final List<String> logged = new ArrayList<>();
    private File file;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        file = directory.resolve(ReferralStore.FILE).toFile();
    }

    private ReferralStore store(String season) {
        ReferralStore store = new ReferralStore(plugin);
        store.load(season);
        return store;
    }

    @Test
    void aRoundTripKeepsEveryField() {
        ReferralStore store = store("2026-10-01T00:00");
        UUID inviter = UUID.randomUUID();
        UUID invitee = UUID.randomUUID();
        store.get(inviter, "Inviter").setRareClaims("act_1", 2);
        ReferralData data = store.get(invitee, "Invitee");
        data.setMinutes(125);
        data.setFirstSeen(42);
        data.link(inviter, 99);
        data.claimed().add(1);
        data.inviterClaimed().add(3);
        data.addIp(store.ipHash("10.0.0.1"));
        data.setLastSeen(77);
        data.ipPartners().add(inviter);
        assertTrue(store.isDirty());
        assertTrue(store.saveNow());
        assertFalse(store.isDirty());

        ReferralStore again = store("2026-10-01T00:00");
        ReferralData read = again.peek(invitee);
        assertEquals("Invitee", read.name());
        assertEquals(125, read.minutes());
        assertEquals(42, read.firstSeen());
        assertEquals(inviter, read.invitedBy());
        assertEquals(99, read.linkedAt());
        assertEquals(List.of(1), new ArrayList<>(read.claimed()));
        assertEquals(List.of(3), new ArrayList<>(read.inviterClaimed()));
        assertEquals(store.ipHash("10.0.0.1"), again.ipHash("10.0.0.1"), "the salt is kept");
        assertTrue(read.ips().contains(again.ipHash("10.0.0.1")));
        assertEquals(2, again.peek(inviter).rareClaims("act_1"));
        assertEquals(77, read.lastSeen());
        assertEquals(java.util.Set.of(inviter), read.ipPartners());
        assertTrue(again.peek(inviter).ipPartners().isEmpty());
        assertNull(again.peek(inviter).invitedBy());
        assertEquals(inviter, again.byName("INVITER"));
        assertNull(again.byName("nobody"));
        assertEquals("2026-10-01T00:00", again.season());
        assertSame(again.all().get(invitee), read);
    }

    @Test
    void namesFollowTheLatestJoinAndHashesHideTheAddress() {
        ReferralStore store = store("");
        UUID uuid = UUID.randomUUID();
        store.get(uuid, "Old");
        store.saveNow();
        assertSame(store.peek(uuid), store.get(uuid, "Old"));
        assertFalse(store.isDirty());
        store.get(uuid, null);
        assertFalse(store.isDirty());
        store.get(uuid, "New");
        assertEquals("New", store.peek(uuid).name());
        assertTrue(store.isDirty());
        store.peek(uuid).setName(null);
        assertNull(store.byName("New"));
        assertFalse(store.ipHash("10.0.0.1").contains("10.0.0.1"));
        assertNotEquals(store.ipHash("10.0.0.1"), store.ipHash("10.0.0.2"));
    }

    @Test
    void malformedEntriesAreSkipped() throws IOException {
        Files.writeString(file.toPath(), """
            season: ""
            salt: abc
            players:
              not-a-uuid: {name: x}
              %s: 5
              %s: {name: Ok, invited-by: nonsense}
            """.formatted(UUID.randomUUID(), UUID.randomUUID()));
        ReferralStore store = store("");
        assertEquals(1, store.all().size());
        assertNull(store.all().values().iterator().next().invitedBy());
        assertEquals(2, logged.stream().filter(line -> line.contains("Skipping malformed entry")).count());
    }

    @Test
    void badPartnerIdsAreSkipped() throws IOException {
        UUID uuid = UUID.randomUUID();
        Files.writeString(file.toPath(), "players:\n  %s: {name: A, ip-partners: [nonsense]}\n".formatted(uuid));
        assertTrue(store("").peek(uuid).ipPartners().isEmpty());
    }

    @Test
    void theLastRealStartSurvivesABlankOne() throws IOException {
        ReferralStore first = store("2026-07-01T00:00");
        first.get(UUID.randomUUID(), "Old");
        first.saveNow();
        ReferralStore blank = store("");
        assertEquals(1, blank.all().size());
        blank.saveNow();
        assertEquals("2026-07-01T00:00", YamlConfiguration.loadConfiguration(file).getString("last-start"));
        assertEquals(1, store("2026-07-01T00:00").all().size(), "back to the same date keeps it");
        assertTrue(store("2026-10-01T00:00").all().isEmpty(), "a new date starts over");
    }

    @Test
    void aFileWithoutPlayersLoadsEmpty() throws IOException {
        Files.writeString(file.toPath(), "season: \"\"\nsalt: abc\n");
        ReferralStore store = store("");
        assertTrue(store.isLoaded());
        assertTrue(store.all().isEmpty());
    }

    @Test
    void anUnreadableFileTurnsSavingOff() throws IOException {
        Files.writeString(file.toPath(), "players: [unclosed");
        ReferralStore store = store("");
        assertFalse(store.isLoaded());
        assertFalse(store.saveNow());
        assertTrue(logged.stream().anyMatch(line -> line.contains("could not be parsed")));
        assertTrue(logged.stream().anyMatch(line -> line.contains("never loaded")));
        store.shutdown();
        assertEquals("players: [unclosed", Files.readString(file.toPath()), "an unread file is never overwritten");
    }

    @Test
    void anotherSeasonsFileIsMovedAside() throws IOException {
        ReferralStore first = store("2026-07-01T00:00");
        first.get(UUID.randomUUID(), "Old");
        first.saveNow();
        ReferralStore next = store("2026-10-01T00:00");
        assertTrue(next.isLoaded());
        assertTrue(next.all().isEmpty());
        File[] moved = directory.toFile().listFiles((dir, name) -> name.startsWith("referrals-2026-07-01T00-00-"));
        assertEquals(1, moved.length);
        assertTrue(Files.readString(moved[0].toPath()).contains("Old"));

        Files.writeString(file.toPath(), "players:\n  %s: {name: Kept}\n".formatted(UUID.randomUUID()));
        ReferralStore adopted = store("2026-10-01T00:00");
        assertEquals(1, adopted.all().size(), "a file with no season yet is kept, not moved aside");
        assertTrue(adopted.isDirty());
        assertEquals(1, directory.toFile().listFiles((dir, name) -> name.startsWith("referrals-")).length);
        adopted.saveNow();
        assertEquals(1, store("").all().size(), "clearing season.start keeps the data too");
    }

    @Test
    void aSeasonFileThatCannotBeMovedTurnsSavingOff() throws IOException {
        Files.writeString(file.toPath(), "season: old\n");
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(eq(file.toPath()), any(Path.class))).thenThrow(new IOException("locked"));
            ReferralStore store = store("new");
            assertFalse(store.isLoaded());
        }
        assertTrue(logged.stream().anyMatch(line -> line.contains("could not be moved aside (locked)")));
    }

    @Test
    void saveSoonWritesAsynchronouslyWhileEnabledAndOldSnapshotsNeverWin() {
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.isEnabled()).thenReturn(true);
        ReferralStore store = store("");
        UUID uuid = UUID.randomUUID();
        store.get(uuid, "Async").setMinutes(1);
        store.saveSoon();
        ArgumentCaptor<Runnable> write = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskAsynchronously(eq(plugin), write.capture());
        assertFalse(store.isDirty());
        store.peek(uuid).setMinutes(2);
        assertTrue(store.saveNow());
        write.getValue().run();
        assertEquals(2, YamlConfiguration.loadConfiguration(file).getInt("players." + uuid + ".minutes"));
    }

    @Test
    void saveSoonWritesAtOnceWhenDisabledAndNotAtAllAfterShutdown() {
        when(plugin.isEnabled()).thenReturn(false);
        ReferralStore store = store("");
        UUID uuid = UUID.randomUUID();
        store.get(uuid, "Sync").setMinutes(3);
        store.saveSoon();
        assertEquals(3, YamlConfiguration.loadConfiguration(file).getInt("players." + uuid + ".minutes"));
        store.shutdown();
        store.peek(uuid).setMinutes(4);
        store.saveSoon();
        assertEquals(3, YamlConfiguration.loadConfiguration(file).getInt("players." + uuid + ".minutes"));
    }

    @Test
    void aFailedWriteKeepsTheStoreDirty() throws IOException {
        ReferralStore store = store("");
        store.get(UUID.randomUUID(), "x");
        Files.createDirectories(directory.resolve(ReferralStore.FILE + ".tmp").resolve("keep"));
        assertFalse(store.saveNow());
        assertTrue(store.isDirty());
        assertTrue(logged.stream().anyMatch(line -> line.contains("Failed to write")));
        store.markDirty();
    }

    @Test
    void theAtomicMoveFallsBackToAPlainReplace() {
        ReferralStore store = store("");
        store.get(UUID.randomUUID(), "x");
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(any(Path.class), any(Path.class), any(), any()))
                .thenThrow(new java.nio.file.AtomicMoveNotSupportedException("a", "b", "no"));
            assertTrue(store.saveNow());
        }
        assertTrue(file.isFile());
    }
}
