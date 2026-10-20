package net.tfminecraft.activitytf.store;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.activitytf.models.ReferralData;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** referrals.yml: every account's counted playtime, link and claims for one season. */
public class ReferralStore {

    public static final String FILE = "referrals.yml";

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, ReferralData> players = new ConcurrentHashMap<>();
    private final Object saveLock = new Object();

    private volatile String season = "";
    private volatile String salt = "";
    private volatile boolean dirty;
    private volatile boolean loaded;
    private volatile boolean shuttingDown;

    private final AtomicLong saveSeq = new AtomicLong();
    private long lastWritten;

    public ReferralStore(JavaPlugin plugin) {
        this(plugin, new File(plugin.getDataFolder(), FILE));
    }

    ReferralStore(JavaPlugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    /** Reads the file; a file from another season is moved aside and this one starts empty. */
    public void load(String currentSeason) {
        players.clear();
        loaded = false;
        shuttingDown = false;
        season = currentSeason;
        salt = "";
        if (file.exists()) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file);
            } catch (Exception e) {
                plugin.getLogger().severe(FILE + " could not be parsed (" + e.getMessage() + ") - referral saving"
                    + " is off until an operator fixes or moves it aside.");
                return;
            }
            salt = yaml.getString("salt", "");
            String stored = yaml.getString("season", "");
            if (!stored.equals(currentSeason)) {
                if (!archive(stored)) {
                    return;
                }
            } else {
                read(yaml.getConfigurationSection("players"));
            }
        }
        if (salt.isEmpty()) {
            byte[] bytes = new byte[16];
            new SecureRandom().nextBytes(bytes);
            salt = HexFormat.of().formatHex(bytes);
            dirty = true;
        }
        loaded = true;
    }

    private boolean archive(String stored) {
        String tag = stored.isEmpty() ? "unset" : stored.replaceAll("[^0-9A-Za-z-]", "-");
        File old = new File(file.getParentFile(), "referrals-" + tag + "-" + System.currentTimeMillis() + ".yml");
        try {
            Files.move(file.toPath(), old.toPath());
            plugin.getLogger().info("The referral season changed (" + stored + " -> " + season + ") - the old links"
                + " are kept in " + old.getName() + " and this season starts empty.");
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("The referral season changed, but " + FILE + " could not be moved aside ("
                + e.getMessage() + ") - referral saving is off.");
            return false;
        }
    }

    private void read(ConfigurationSection root) {
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(key);
            UUID uuid = uuid(key);
            if (entry == null || uuid == null) {
                plugin.getLogger().warning("Skipping malformed entry '" + key + "' in " + FILE);
                continue;
            }
            players.put(uuid, parse(entry));
        }
    }

    static ReferralData parse(ConfigurationSection entry) {
        ReferralData data = new ReferralData(entry.getString("name", ""), entry.getLong("first-seen"));
        data.setMinutes(entry.getInt("minutes"));
        UUID inviter = uuid(entry.getString("invited-by", ""));
        if (inviter != null) {
            data.link(inviter, entry.getLong("linked-at"));
        }
        data.claimed().addAll(entry.getIntegerList("claimed"));
        data.inviterClaimed().addAll(entry.getIntegerList("inviter-claimed"));
        for (String ip : entry.getStringList("ips")) {
            data.addIp(ip);
        }
        ConfigurationSection rare = entry.getConfigurationSection("rare");
        if (rare != null) {
            for (String act : rare.getKeys(false)) {
                data.setRareClaims(act, rare.getInt(act));
            }
        }
        return data;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public ReferralData get(UUID uuid, String name) {
        ReferralData data = players.computeIfAbsent(uuid, id -> {
            dirty = true;
            return new ReferralData(name, 0);
        });
        if (name != null && !name.equals(data.name())) {
            data.setName(name);
            dirty = true;
        }
        return data;
    }

    public ReferralData peek(UUID uuid) {
        return players.get(uuid);
    }

    public Map<UUID, ReferralData> all() {
        return players;
    }

    /** The account a name was last seen on, ignoring case; null when nobody this season used it. */
    public UUID byName(String name) {
        for (Map.Entry<UUID, ReferralData> entry : players.entrySet()) {
            if (entry.getValue().name() != null && entry.getValue().name().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Salted hash of a connection address, so the file never holds a raw IP. */
    public String ipHash(String address) {
        return UUID.nameUUIDFromBytes((salt + "|" + address).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public String season() {
        return season;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
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

    public void shutdown() {
        shuttingDown = true;
        if (loaded) {
            saveNow();
        }
    }

    YamlConfiguration snapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Referral links and counted playtime. Written by the plugin - do not edit"
            + " while the server runs."));
        yaml.set("season", season);
        yaml.set("salt", salt);
        yaml.createSection("players");
        for (Map.Entry<UUID, ReferralData> entry : players.entrySet()) {
            ReferralData data = entry.getValue();
            String path = "players." + entry.getKey();
            yaml.set(path + ".name", data.name());
            yaml.set(path + ".minutes", data.minutes());
            yaml.set(path + ".first-seen", data.firstSeen());
            if (data.invitedBy() != null) {
                yaml.set(path + ".invited-by", data.invitedBy().toString());
                yaml.set(path + ".linked-at", data.linkedAt());
            }
            yaml.set(path + ".claimed", new ArrayList<>(data.claimed()));
            yaml.set(path + ".inviter-claimed", new ArrayList<>(data.inviterClaimed()));
            yaml.set(path + ".ips", data.ipList());
            if (!data.rareClaims().isEmpty()) {
                yaml.createSection(path + ".rare", data.rareClaims());
            }
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
