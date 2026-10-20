package net.tfminecraft.activitytf.managers;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.permissions.PermissionAttachment;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.activitytf.ActivityPlugin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/** A real ActivityPlugin on MockBukkit, with referral.yml written first and a settable clock. */
public final class ReferralFixture {

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 12, 0);

    public static final String CONFIG = """
        enabled: true
        season:
          start: "2026-10-15"
          open-days: 14
        link:
          new-account-days: 7
          same-ip-check: true
          max-invitees: 0
          max-links-per-day: 0
        playtime:
          afk-minutes: 5
          move-blocks: 3
          ignored-worlds: [character_selection]
        pools:
          pool_ref_rare:
            - weight: 1
              display: "Rare emerald"
              items:
                - item: EMERALD
                  amount: 2
        acts:
          act_1:
            display: "Act I"
            starts: "2026-10-01"
            rare-limit: 1
            milestones:
              - hours: 1
                new-player:
                  display: "Starter kit"
                  items:
                    - item: BREAD
                      amount: 4
                inviter:
                  display: "Coin"
                  items:
                    - item: GOLD_NUGGET
                      amount: 3
              - hours: 3
                new-player:
                  display: "Small"
                  items:
                    - item: IRON_INGOT
                      amount: 2
              - hours: 5
                rare: true
                new-player:
                  display: "Rare act reward"
                  icon: EMERALD
                  pool: pool_ref_rare
                inviter: pool_ref_rare
        """;

    public final ServerMock server;
    public final ActivityPlugin plugin;
    public final ActivityManager manager;
    public final ReferralManager referrals;
    public final AtomicLong clock = new AtomicLong(millis(NOW));
    public final List<String> logs = new ArrayList<>();

    private final Server previousServer;
    private final Object previousManager;

    private ReferralFixture(String config) throws Exception {
        previousServer = Bukkit.getServer();
        previousManager = field(ActivityManager.class, "instance").get(null);
        field(Bukkit.class, "server").set(null, null);
        field(ActivityManager.class, "instance").set(null, null);
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        server.addSimpleWorld("character_selection");
        plugin = (ActivityPlugin) server.getPluginManager().loadPlugin(ActivityPlugin.class);
        plugin.getLogger().addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        if (config != null) {
            Path folder = plugin.getDataFolder().toPath();
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("referral.yml"), config);
        }
        server.getPluginManager().enablePlugin(plugin);
        manager = ActivityManager.getInstance();
        referrals = manager.referrals();
        referrals.setClock(clock::get);
    }

    public static ReferralFixture start() throws Exception {
        return new ReferralFixture(CONFIG);
    }

    public static ReferralFixture start(String config) throws Exception {
        return new ReferralFixture(config);
    }

    public void stop() throws Exception {
        try {
            MockBukkit.unmock();
        } finally {
            field(ActivityManager.class, "instance").set(null, previousManager);
            field(Bukkit.class, "server").set(null, previousServer);
        }
    }

    public static long millis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public void advanceDays(int days) {
        clock.addAndGet(days * 24L * 60L * 60L * 1000L);
    }

    /** A player whose idle time, first join and connection the test controls; already joined. */
    public TestPlayer join(String name, String ip) {
        TestPlayer player = new TestPlayer(server, name, ip, clock.get());
        server.addPlayer(player);
        player.permissions = player.addAttachment(plugin, "activity.referral", true);
        player.permissions.setPermission("activity.use", true);
        return player;
    }

    public void tick() {
        referrals.tick();
    }

    public boolean counts(PlayerMock player) {
        return referrals.counts(player, clock.get());
    }

    public void writeReferral(String config) throws IOException {
        Files.writeString(plugin.getDataFolder().toPath().resolve("referral.yml"), config);
    }

    public static final class TestPlayer extends PlayerMock {

        public Duration idle = Duration.ZERO;
        public InetSocketAddress address;
        public PermissionAttachment permissions;
        public long firstPlayed;

        TestPlayer(ServerMock server, String name, String ip, long firstPlayed) {
            super(server, name, UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes()));
            this.firstPlayed = firstPlayed;
            address = new InetSocketAddress(ip == null ? "127.0.0.1" : ip, 25565);
        }

        @Override
        public InetSocketAddress getAddress() {
            return address;
        }

        @Override
        public Duration getIdleDuration() {
            return idle;
        }

        @Override
        public long getFirstPlayed() {
            return firstPlayed;
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
