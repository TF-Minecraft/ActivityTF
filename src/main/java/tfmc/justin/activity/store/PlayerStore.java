package tfmc.justin.activity.store;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public class PlayerStore {

    public static final String FILE = "players.yml";

    private final JavaPlugin plugin;
    private final ActivityConfiguration config;
    private final File file;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private final Object saveLock = new Object();

    private volatile boolean dirty;
    private BukkitTask autoSave;

    private volatile boolean loaded;

    private volatile boolean shuttingDown;

    private final AtomicLong saveSeq = new AtomicLong();
    private long lastWritten;

    public PlayerStore(JavaPlugin plugin, ActivityConfiguration config) {
        this.plugin = plugin;
        this.config = config;
        this.file = new File(plugin.getDataFolder(), FILE);
    }

    public void load() {
        players.clear();
        if (file.exists() && !read()) {
            plugin.getLogger().severe("Saving is disabled until an operator moves " + FILE
                + " aside by hand. Nothing earned from now on will be kept.");
            return;
        }
        loaded = true;
    }

    private boolean read() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            return quarantine("could not be parsed (" + e.getMessage() + ")");
        }

        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            Set<String> stray = yaml.getKeys(false);
            stray.remove("players");
            if (!stray.isEmpty()) {
                return quarantine("has no 'players' section");
            }
            return true;
        }

        for (String key : root.getKeys(false)) {
            readEntry(root, key);
        }
        return true;
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

        players.put(uuid, parse(entry, config.barMax(), config.dailyMax(), id -> config.activity(id) != null,
            config.activity("vote")));
    }

    static PlayerData readEntry(ConfigurationSection root, String key, int barMax, int dailyMax,
                                Predicate<String> known) {
        return readEntry(root, key, barMax, dailyMax, known, null);
    }

    static PlayerData readEntry(ConfigurationSection root, String key, int barMax, int dailyMax,
                                Predicate<String> known, ActivityDef vote) {
        ConfigurationSection entry = root.getConfigurationSection(key);
        if (entry == null) {
            return null;
        }

        try {
            UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            return null;
        }

        return parse(entry, barMax, dailyMax, known, vote);
    }

    private static PlayerData parse(ConfigurationSection entry, int barMax, int dailyMax,
                                    Predicate<String> known, ActivityDef vote) {
        Map<String, Integer> daily = new HashMap<>();
        ConfigurationSection dailySection = entry.getConfigurationSection("daily");
        if (dailySection != null) {
            for (String id : dailySection.getKeys(false)) {
                daily.put(id, Math.max(0, dailySection.getInt(id)));
            }
        }

        int points = Math.max(0, Math.min(entry.getInt("points"), barMax));
        int claimedPoints = Math.max(0, Math.min(entry.getInt("claimed-points"), points));
        int dailyPoints = Math.max(0, Math.min(entry.getInt("daily-points"), dailyMax));

        List<String> tasks = new ArrayList<>(entry.getStringList("tasks"));
        tasks.removeIf(known.negate());

        int rerolls = Math.max(0, entry.getInt("rerolls"));

        PlayerData data = new PlayerData(points, dailyPoints, entry.getString("week", ""),
            entry.getString("day", ""), claimedPoints, daily, tasks, entry.getStringList("revealed"), rerolls);
        data.setDailyRewardClaimed(entry.getBoolean("daily-reward-claimed", false));
        data.setVotePoints(entry.contains("vote-points") ? entry.getInt("vote-points")
            : vote == null ? 0 : vote.worth(daily.getOrDefault(vote.id(), 0)));
        return data;
    }

    private boolean quarantine(String reason) {
        File backup = new File(file.getParentFile(), FILE + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().severe(FILE + " " + reason + " - kept a copy as " + backup.getName()
                + " and started from an empty store.");
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe(FILE + " " + reason + ", and copying it aside failed: " + e.getMessage());
            return false;
        }
    }

    public PlayerData get(UUID uuid) {
        PlayerData data = rolled(uuid);
        if (data != null) {
            return data;
        }

        ActivityConfiguration.Keys keys = config.currentKeys();
        data = new PlayerData(keys.week(), keys.day());
        players.put(uuid, data);
        dirty = true;
        return data;
    }

    public PlayerData rolled(UUID uuid) {
        PlayerData data = players.get(uuid);
        if (data == null) {
            return null;
        }

        ActivityConfiguration.Keys keys = config.currentKeys();
        if (data.roll(keys.week(), keys.day()) | data.clamp(config.barMax())) {
            dirty = true;
        }
        return data;
    }

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

    public void saveSoon() {
        if (shuttingDown) {
            return;
        }

        YamlConfiguration snapshot = snapshot();
        long seq = saveSeq.incrementAndGet();
        dirty = false;

        if (!plugin.isEnabled()) {
            write(snapshot, seq);
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> write(snapshot, seq));
    }

    public boolean saveNow() {
        if (!write(snapshot(), saveSeq.incrementAndGet())) {
            return false;
        }
        dirty = false;
        return true;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void shutdown() {
        shuttingDown = true;

        if (!loaded) {
            plugin.getLogger().warning("Not saving " + FILE + ": it was never loaded, and an empty store"
                + " must not be written over a file nobody has read.");
            return;
        }

        synchronized (saveLock) {
            if (autoSave != null) {
                autoSave.cancel();
                autoSave = null;
            }
            saveNow();
        }
    }

    private YamlConfiguration snapshot() {
        return snapshot(players);
    }

    static YamlConfiguration snapshot(Map<UUID, PlayerData> players) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
            PlayerData data = entry.getValue();
            if (data.points() == 0 && data.claimedPoints() == 0 && data.dailyPoints() == 0
                && data.daily().isEmpty() && data.tasks().isEmpty() && data.rerolls() == 0
                && !data.dailyRewardClaimed()) {
                continue;
            }

            String path = "players." + entry.getKey();

            yaml.set(path + ".points", data.points());
            yaml.set(path + ".week", data.weekKey());
            yaml.set(path + ".day", data.dayKey());
            yaml.set(path + ".claimed-points", data.claimedPoints());
            yaml.set(path + ".daily-points", data.dailyPoints());
            yaml.set(path + ".vote-points", data.votePoints());
            yaml.set(path + ".rerolls", data.rerolls());
            yaml.set(path + ".daily-reward-claimed", data.dailyRewardClaimed());
            yaml.set(path + ".daily", new LinkedHashMap<>(data.daily()));
            yaml.set(path + ".tasks", new ArrayList<>(data.tasks()));
            yaml.set(path + ".revealed", new ArrayList<>(new TreeSet<>(data.revealed())));
        }
        return yaml;
    }

    private boolean write(YamlConfiguration snapshot, long seq) {
        synchronized (saveLock) {
            if (!loaded) {
                plugin.getLogger().warning("Not writing " + FILE + ": it was never loaded.");
                return false;
            }

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
                dirty = true;
                return false;
            }
        }
    }
}
