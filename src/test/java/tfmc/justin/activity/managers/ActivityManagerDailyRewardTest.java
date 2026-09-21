package tfmc.justin.activity.managers;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.models.RewardEntry;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityManagerDailyRewardTest {

    @TempDir
    File dir;

    private static final RewardEntry STEEL = new RewardEntry(1, "Steel", List.of(),
        List.of(new RewardEntry.Item("m.material.steel", 3)));
    private static final RewardEntry DIAMOND = new RewardEntry(1, "Diamond", List.of(),
        List.of(new RewardEntry.Item("DIAMOND", 1)));

    private ActivityManager manager;
    private final UUID uuid = UUID.randomUUID();
    private final List<String> chat = new ArrayList<>();
    private final List<RewardEntry> paid = new ArrayList<>();
    private final List<Integer> paidMultipliers = new ArrayList<>();
    private boolean payWorks = true;
    private boolean resolves = true;
    private final List<Boolean> onDiskAtPayout = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ActivityDef[] defs = new ActivityDef[20];
        for (int i = 0; i < defs.length; i++) {
            defs[i] = new ActivityDef("a" + i, "a" + i, Material.PAPER, null, 1, 1, 0);
        }
        manager = TestManagers.manager(defs);
        TestManagers.messages(manager);
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        TestManagers.storeFile(manager, new File(dir, "players.yml"));
        TestManagers.dailyRewards(manager, Map.of("vip", DIAMOND));
        ActivityManager.reportedItemPaths.clear();
    }

    private Player player(Set<String> permissions) {
        return player(permissions::contains, permissions::contains);
    }

    private Player player(Predicate<String> has, Predicate<String> set) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getName" -> "Steve";
            case "hasPermission" -> has.test(String.valueOf(args[0]));
            case "isPermissionSet" -> set.test(String.valueOf(args[0]));
            case "sendMessage" -> chat.add(String.valueOf(args[0]));
            case "toString" -> "stub-player";
            case "hashCode" -> 1;
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("unexpected Player#" + method.getName());
        };
        return (Player) Proxy.newProxyInstance(
            ActivityManagerDailyRewardTest.class.getClassLoader(), new Class<?>[]{Player.class}, handler);
    }

    private boolean claim(Player player) {
        return manager.claimDailyReward(player, path -> resolves, (reward, multiplier) -> {
            onDiskAtPayout.add(claimedOnDisk());
            paid.add(reward);
            paidMultipliers.add(multiplier);
            return payWorks;
        });
    }

    private void revealAllButLast() {
        int tasks = manager.tasks(uuid).tasks().size();
        for (int slot = 0; slot < tasks - 1; slot++) {
            manager.reveal(uuid, slot);
        }
    }

    private void revealLast() {
        manager.reveal(uuid, manager.tasks(uuid).tasks().size() - 1);
    }

    private PlayerData data() {
        return manager.getStore().get(uuid);
    }

    private File file() {
        return new File(dir, "players.yml");
    }

    private boolean claimedOnDisk() {
        return YamlConfiguration.loadConfiguration(file()).getBoolean("players." + uuid + ".daily-reward-claimed");
    }

    @Test
    void paidOnceTheLastTaskIsRevealedAndNotBefore() {
        Player player = player(Set.of("group.vip"));
        revealAllButLast();
        assertFalse(claim(player));
        assertTrue(paid.isEmpty());

        revealLast();
        assertTrue(claim(player));
        assertEquals(1, paid.size());
        assertEquals(DIAMOND.items(), paid.get(0).items());
        assertTrue(data().dailyRewardClaimed());
        assertTrue(chat.get(0).contains("x1") && chat.get(0).contains("Diamond"), chat.toString());

        assertFalse(claim(player));
        assertEquals(1, paid.size());
    }

    @Test
    void theClaimIsSavedBeforeThePayoutRuns() {
        revealAllButLast();
        revealLast();

        assertTrue(claim(player(Set.of("group.vip"))));
        assertEquals(List.of(true), onDiskAtPayout);
    }

    @Test
    void theChatLineCarriesTheMultipliedAmount() throws ReflectiveOperationException {
        TestManagers.dailyRewards(manager, Map.of("vip", STEEL));
        Field multiplier = manager.getConfiguration().getClass().getDeclaredField("rewardMultiplier");
        multiplier.setAccessible(true);
        multiplier.set(manager.getConfiguration(), 2);
        revealAllButLast();
        revealLast();

        assertTrue(claim(player(Set.of("group.vip"))));
        assertEquals("#50d990x6 #b8906eSteel", paid.get(0).display());
        assertTrue(chat.get(0).contains("x6") && chat.get(0).contains("Steel"), chat.toString());
    }

    @Test
    void aRerollAfterClaimingDoesNotPayASecondTime() {
        Player player = player(Set.of("group.vip"));
        revealAllButLast();
        revealLast();
        assertTrue(claim(player));

        assertEquals(ActivityManager.Rerolled.DONE, manager.reroll(uuid));
        assertTrue(data().dailyRewardClaimed(), "the reroll cleared the claim");
        revealAllButLast();
        revealLast();
        assertFalse(claim(player));
        assertEquals(1, paid.size());
    }

    @Test
    void theNextDayPaysAgain() {
        Player player = player(Set.of("group.vip"));
        revealAllButLast();
        revealLast();
        assertTrue(claim(player));

        PlayerData row = manager.getStore().peek(uuid);
        row.roll(row.weekKey(), "1999-01-01");
        row.setDailyRewardClaimed(true);
        assertFalse(data().dailyRewardClaimed(), "the rollover kept yesterday's claim");
        revealAllButLast();
        revealLast();
        assertTrue(claim(player));
        assertEquals(2, paid.size());
    }

    @Test
    void noMatchingGroupIsNotMarkedAndCanStillBePaidLaterThatDay() {
        revealAllButLast();
        revealLast();
        assertFalse(claim(player(Set.of("group.default"))));
        assertTrue(paid.isEmpty());
        assertFalse(data().dailyRewardClaimed());

        assertTrue(claim(player(Set.of("group.vip"))));
        assertEquals(1, paid.size());
    }

    @Test
    void anOperatorInNoGroupGetsNothing() {
        revealAllButLast();
        revealLast();

        assertFalse(claim(player(node -> true, node -> false)));
        assertTrue(paid.isEmpty());
        assertFalse(data().dailyRewardClaimed());
    }

    @Test
    void aNodeSetToFalseIsNotMembership() {
        revealAllButLast();
        revealLast();

        assertFalse(claim(player(node -> false, node -> true)));
        assertTrue(paid.isEmpty());
    }

    @Test
    void aFailedPayoutIsNotMarkedAndIsRetried() {
        Player player = player(Set.of("group.vip"));
        revealAllButLast();
        revealLast();
        payWorks = false;
        assertFalse(claim(player));
        assertFalse(data().dailyRewardClaimed());
        assertFalse(claimedOnDisk(), "the rollback never reached disk");
        assertTrue(chat.get(0).contains("could not be handed over"), chat.toString());

        payWorks = true;
        assertTrue(claim(player));
        assertEquals(2, paid.size());
        assertTrue(data().dailyRewardClaimed());
    }

    @Test
    void anUnresolvableItemIsRefusedWithoutAnyWriteAndWarnedOnce() {
        List<String> warnings = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        TestManagers.logger().addHandler(capture);
        try {
            Player player = player(Set.of("group.vip"));
            revealAllButLast();
            revealLast();
            resolves = false;

            assertFalse(claim(player));
            assertFalse(claim(player));

            assertTrue(paid.isEmpty());
            assertFalse(data().dailyRewardClaimed());
            assertFalse(file().exists(), "a refused claim wrote players.yml");
            assertEquals(2, chat.stream().filter(line -> line.contains("could not be handed over")).count());
            assertEquals(1, warnings.stream().filter(line -> line.contains("Daily reward item 'DIAMOND'")).count(),
                warnings.toString());

            resolves = true;
            assertTrue(claim(player));
            assertEquals(1, paid.size());
        } finally {
            TestManagers.logger().removeHandler(capture);
        }
    }

    @Test
    void aClaimThatCannotBeSavedPaysNothing() throws IOException {
        File notADirectory = new File(dir, "blocker");
        Files.writeString(notADirectory.toPath(), "x");
        TestManagers.storeFile(manager, new File(notADirectory, "players.yml"));
        revealAllButLast();
        revealLast();

        assertFalse(claim(player(Set.of("group.vip"))));
        assertTrue(paid.isEmpty());
        assertFalse(data().dailyRewardClaimed());
        assertTrue(chat.get(0).contains("could not be handed over"), chat.toString());
    }

    @Test
    void aStoreThatNeverLoadedPaysNothing() throws ReflectiveOperationException {
        revealAllButLast();
        revealLast();
        Field loaded = manager.getStore().getClass().getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.set(manager.getStore(), false);

        assertFalse(claim(player(Set.of("group.vip"))));
        assertTrue(paid.isEmpty());
        assertFalse(data().dailyRewardClaimed());
        assertFalse(file().exists());
    }

    @Test
    void nothingIsPaidWhenNoGroupsAreConfigured() {
        TestManagers.dailyRewards(manager, Map.of());
        revealAllButLast();
        revealLast();
        assertFalse(claim(player(Set.of("group.vip"))));
        assertTrue(paid.isEmpty());
        assertFalse(data().dailyRewardClaimed());
    }

    @Test
    void aPoolGroupPaysOneDrawFromThePoolAtMultiplierOne() throws ReflectiveOperationException {
        RewardEntry gem = new RewardEntry(1, "&dA Gem", List.of(), List.of(new RewardEntry.Item("EMERALD", 2)));
        pool(List.of(gem));
        Field multiplier = manager.getConfiguration().getClass().getDeclaredField("rewardMultiplier");
        multiplier.setAccessible(true);
        multiplier.set(manager.getConfiguration(), 3);
        TestManagers.dailyRewards(manager, Map.of("vip", ActivityConfiguration.DAILY_POOL));
        revealAllButLast();
        revealLast();

        assertTrue(claim(player(Set.of("group.vip"))));
        assertEquals(List.of(gem), paid);
        assertEquals(List.of(1), paidMultipliers);
        assertTrue(data().dailyRewardClaimed());
        assertTrue(chat.get(0).contains("A Gem"), chat.toString());
    }

    @Test
    void aPoolGroupAndAnItemGroupEachPayTheirOwn() {
        RewardEntry gem = new RewardEntry(1, "A Gem", List.of(), List.of(new RewardEntry.Item("EMERALD", 1)));
        pool(List.of(gem));
        Map<String, RewardEntry> groups = new LinkedHashMap<>();
        groups.put("noble", DIAMOND);
        groups.put("legacy", ActivityConfiguration.DAILY_POOL);
        TestManagers.dailyRewards(manager, groups);
        revealAllButLast();
        revealLast();

        assertTrue(claim(player(Set.of("group.legacy", "group.noble"))));
        assertEquals(DIAMOND.items(), paid.get(0).items());

        data().setDailyRewardClaimed(false);
        assertTrue(claim(player(Set.of("group.legacy"))));
        assertEquals(gem, paid.get(1));
    }

    @Test
    void aPoolGroupWithAnEmptyPoolPaysNothingAndStaysUnclaimed() {
        pool(List.of());
        TestManagers.dailyRewards(manager, Map.of("vip", ActivityConfiguration.DAILY_POOL));
        revealAllButLast();
        revealLast();

        assertFalse(claim(player(Set.of("group.vip"))));
        assertTrue(paid.isEmpty());
        assertFalse(data().dailyRewardClaimed());
        assertFalse(file().exists(), "a refused claim wrote players.yml");
        assertEquals(1, chat.size());
    }

    @Test
    void aFailedPoolPayoutIsNotMarkedAndIsRetried() {
        pool(List.of(new RewardEntry(1, "A Gem", List.of(), List.of(new RewardEntry.Item("EMERALD", 1)))));
        TestManagers.dailyRewards(manager, Map.of("vip", ActivityConfiguration.DAILY_POOL));
        Player player = player(Set.of("group.vip"));
        revealAllButLast();
        revealLast();
        payWorks = false;
        assertFalse(claim(player));
        assertFalse(data().dailyRewardClaimed());

        payWorks = true;
        assertTrue(claim(player));
        assertTrue(data().dailyRewardClaimed());
    }

    private void pool(List<RewardEntry> pool) {
        TestManagers.pool(manager, ActivityConfiguration.DEFAULT_POOL, pool);
    }

    @Test
    void theFirstListedGroupWins() {
        Map<String, RewardEntry> groups = new LinkedHashMap<>();
        groups.put("ascended", STEEL);
        groups.put("vip", DIAMOND);

        assertSame(STEEL, ActivityManager.dailyRewardFor(groups, player(Set.of("group.vip", "group.ascended"))));
        assertSame(DIAMOND, ActivityManager.dailyRewardFor(groups, player(Set.of("group.vip"))));
        assertNull(ActivityManager.dailyRewardFor(groups, player(Set.of("group.default", "vip"))));
    }
    @Test
    void namedDailyPoolPaysItsOwnRewardAndMissingPoolStaysUnclaimed() throws Exception {
        Field pools = ActivityConfiguration.class.getDeclaredField("rewardPools");
        pools.setAccessible(true);
        pools.set(manager.getConfiguration(), Map.of("pool", List.of(DIAMOND), "pool_end", List.of(STEEL)));
        TestManagers.dailyRewards(manager, Map.of("vip", ActivityConfiguration.poolRef("pool_missing")));
        revealAllButLast();
        revealLast();
        assertFalse(claim(player(Set.of("group.vip"))));
        assertTrue(paid.isEmpty());
        assertFalse(data().dailyRewardClaimed());
        TestManagers.dailyRewards(manager, Map.of("vip", ActivityConfiguration.poolRef("pool_end")));
        assertTrue(claim(player(Set.of("group.vip"))));
        assertEquals(List.of(STEEL), paid);
        assertEquals(List.of(1), paidMultipliers);
    }

}
