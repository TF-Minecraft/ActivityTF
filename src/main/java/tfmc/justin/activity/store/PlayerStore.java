package tfmc.justin.activity.store;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.models.PlayerData;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// ====================================
// players.yml. Everything lives in memory and is mutated on the main thread:
// get() rolls a player forward and creates the entry, so only write paths may
// call it; readers that must not mutate anything (PlaceholderAPI) use peek().
//
// Snapshots are taken on a tick and written off one, but the autosave, the
// post-reward save and onDisable can all want the file at once, so every
// write goes through saveLock and lands via a temp file + atomic rename.
// Each snapshot carries a sequence number so a slow writer cannot land an
// older picture of the world on top of a newer one.
// ====================================
public class PlayerStore {

    private static final String FILE = "players.yml";

    private final JavaPlugin plugin;
    private final ActivityConfiguration config;
    private final File file;
    // Concurrent because PlaceholderAPI can ask for a player's bar off the
    // main thread; every mutation of the PlayerData itself stays on a tick
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    // One writer at a time, whichever thread it is on
    private final Object saveLock = new Object();

    // Set on a tick, read by the autosave task - and by the async writer
    private volatile boolean dirty;
    private BukkitTask autoSave;

    // Nothing may be written before load() has said what is already on disk,
    // or an enable that failed early would save an empty store over a real file
    private boolean loaded;

    // Set on the main thread by shutdown; volatile so a save path the
    // scheduler runs late still sees that the final write has been taken
    private volatile boolean shuttingDown;

    // ====================================
    // saveSeq is stamped on each snapshot in the order the snapshots were
    // taken - it's an AtomicLong so a snapshot can be sequenced without
    // holding saveLock, which is reserved for the write() section that
    // actually touches disk. lastWritten is the newest sequence that has
    // reached disk, guarded by saveLock alongside the write it orders against,
    // so a write that lost the race is dropped rather than rolling the file
    // back. The final save takes the highest number of all, which is what
    // makes every straggler behind it a no-op.
    // ====================================
    private final AtomicLong saveSeq = new AtomicLong();
    private long lastWritten;

    public PlayerStore(JavaPlugin plugin, ActivityConfiguration config) {
        this.plugin = plugin;
        this.config = config;
        this.file = new File(plugin.getDataFolder(), FILE);
    }

    public void load() {
        players.clear();
        if (file.exists()) {
            read();
        }
        // Only now may anything be written back over the file
        loaded = true;
    }

    private void read() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            quarantine("could not be parsed (" + e.getMessage() + ")");
            return;
        }

        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            // Other top-level keys but no 'players' means the file is somebody
            // else's, or ours with its one section lost. A comment-only file,
            // or one holding an empty 'players', is just a server nobody has
            // scored on yet - and overwriting that costs nothing.
            Set<String> stray = yaml.getKeys(false);
            stray.remove("players");
            if (!stray.isEmpty()) {
                quarantine("has no 'players' section");
            }
            return;
        }

        for (String key : root.getKeys(false)) {
            readEntry(root, key);
        }
    }

    private void readEntry(ConfigurationSection root, String key) {
        ConfigurationSection entry = root.getConfigurationSection(key);
        if (entry == null) {
            return;
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Skipping malformed UUID '" + key + "' in " + FILE);
            return;
        }

        Map<String, Integer> daily = new HashMap<>();
        ConfigurationSection dailySection = entry.getConfigurationSection("daily");
        if (dailySection != null) {
            for (String id : dailySection.getKeys(false)) {
                daily.put(id, dailySection.getInt(id));
            }
        }

        players.put(uuid, new PlayerData(
            entry.getInt("points"),
            entry.getString("week", ""),
            entry.getString("day", ""),
            entry.getInt("claimed"),
            daily
        ));
    }

    // ====================================
    // A players.yml that cannot be parsed, or that holds something other than
    // our data, is copied aside and reported loudly rather than being silently
    // overwritten by the next save.
    // ====================================
    private void quarantine(String reason) {
        File backup = new File(file.getParentFile(), FILE + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().severe(FILE + " " + reason + " - kept a copy as " + backup.getName()
                + " and started from an empty store.");
        } catch (IOException e) {
            plugin.getLogger().severe(FILE + " " + reason + ", and copying it aside failed: " + e.getMessage()
                + " - started from an empty store.");
        }
    }

    // ====================================
    // The entry point for write paths: whatever asks for it gets data that has
    // already been rolled forward to the current week and day, creating the
    // entry if this is the player's first action.
    // ====================================
    public PlayerData get(UUID uuid) {
        String week = config.currentWeekKey();
        String day = config.currentDayKey();

        PlayerData data = players.get(uuid);
        if (data == null) {
            data = new PlayerData(week, day);
            players.put(uuid, data);
            dirty = true;
            return data;
        }

        if (data.roll(week, day)) {
            dirty = true;
        }
        return data;
    }

    // ====================================
    // Read-only counterpart of get(): no entry is created and nothing is
    // rolled, so a placeholder query on an arbitrary (possibly off-thread)
    // player cannot grow the file or mutate state. Callers compare the
    // returned keys against the current ones themselves.
    // ====================================
    public PlayerData peek(UUID uuid) {
        return players.get(uuid);
    }

    public void markDirty() {
        dirty = true;
    }

    public void startAutoSave() {
        long ticks = config.saveIntervalMinutes() * 60L * 20L;
        autoSave = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (dirty) {
                saveSoon();
            }
        }, ticks, ticks);
    }

    // ====================================
    // Snapshot on the main thread, write off it - the map must not be read
    // while a tick could be mutating it. Once shutdown has taken its own final
    // snapshot there is nothing left worth queueing behind it.
    //
    // The snapshot and its sequence number are taken outside saveLock:
    // snapshots only ever happen on the main thread, so call order alone
    // keeps them ordered, and saveLock is left free to guard only the
    // write() section that actually touches disk.
    // ====================================
    public void saveSoon() {
        if (shuttingDown) {
            return;
        }

        YamlConfiguration snapshot = snapshot();
        long seq = saveSeq.incrementAndGet();
        dirty = false;

        // The scheduler refuses work from a disabled plugin, so a save asked
        // for while the server is pulling us down has to happen right here
        if (!plugin.isEnabled()) {
            write(snapshot, seq);
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> write(snapshot, seq));
    }

    // ====================================
    // dirty only clears on a write that actually landed - a failed write must
    // leave it set so the next autosave tick tries again instead of the
    // in-memory changes silently going unsaved.
    // ====================================
    private void saveNow() {
        if (write(snapshot(), saveSeq.incrementAndGet())) {
            dirty = false;
        }
    }

    public void shutdown() {
        // Stops the autosave and saveSoon from queueing anything the final
        // write would then have to outrank
        shuttingDown = true;

        if (!loaded) {
            plugin.getLogger().warning("Not saving " + FILE + ": it was never loaded, and an empty store"
                + " must not be written over a file nobody has read.");
            return;
        }

        // Under the lock so a still-running async write finishes before the
        // final one starts, and no new autosave can slip in behind it
        synchronized (saveLock) {
            if (autoSave != null) {
                autoSave.cancel();
                autoSave = null;
            }
            saveNow();
        }
    }

    private YamlConfiguration snapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
            PlayerData data = entry.getValue();
            String path = "players." + entry.getKey();

            yaml.set(path + ".points", data.points());
            yaml.set(path + ".week", data.weekKey());
            yaml.set(path + ".day", data.dayKey());
            yaml.set(path + ".claimed", data.claimed());
            yaml.set(path + ".daily", new LinkedHashMap<>(data.daily()));
        }
        return yaml;
    }

    // ====================================
    // Never write players.yml in place: a crash mid-write would leave a
    // truncated file where the whole server's progress used to be. The temp
    // file is the one that can be half-written, the rename is what publishes.
    //
    // Returns true only once the snapshot has actually landed on disk, so a
    // caller knows whether it is safe to clear dirty.
    // ====================================
    private boolean write(YamlConfiguration snapshot, long seq) {
        synchronized (saveLock) {
            if (!loaded) {
                plugin.getLogger().warning("Not writing " + FILE + ": it was never loaded.");
                return false;
            }

            // An older snapshot arriving late - a slow async write, or one
            // queued before shutdown took the last picture - would undo work.
            // Not a failure, just superseded, so no dirty flag is touched.
            if (seq < lastWritten) {
                return false;
            }
            lastWritten = seq;

            File temp = new File(file.getParentFile(), FILE + ".tmp");
            try {
                snapshot.save(temp);
                try {
                    Files.move(temp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to write " + FILE + ": " + e.getMessage());
                // Nothing landed, so the in-memory state is still unsaved and
                // the next autosave tick has to try again
                dirty = true;
                return false;
            }
        }
    }
}
