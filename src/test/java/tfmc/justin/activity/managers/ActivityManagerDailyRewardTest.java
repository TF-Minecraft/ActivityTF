package tfmc.justin.activity.managers;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.models.RewardEntry;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// daily-reward: ActivityManager.claimDailyReward on a real manager built
// without a server (see TestManagers). The store is pointed at a temp file,
// since the payout saves before it pays; the payout itself is the seam the
// package-private overload takes, because a real item needs a running server.
// ====================================
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
    // What each payout was handed, and what it answers
    private final List<RewardEntry> paid = new ArrayList<>();
    private boolean payWorks = true;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        ActivityDef[] defs = new ActivityDef[20];
        for (int i = 0; i < defs.length; i++) {
            defs[i] = new ActivityDef("a" + i, "a" + i, Material.PAPER, null, 1, 1, 0);
        }
        manager = TestManagers.manager(defs);
        TestManagers.messages(manager);
        TestManagers.storeLoaded(manager);
        TestManagers.rerollsPerDay(manager, 1);
        set(manager.getStore(), "file", new File(dir, "players.yml"));
        groups(Map.of("vip", DIAMOND));
    }

    private void groups(Map<String, RewardEntry> groups) throws ReflectiveOperationException {
        set(manager.getConfiguration(), "dailyRewards", groups);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Player player(Set<String> permissions) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "hasPermission" -> permissions.contains(String.valueOf(args[0]));
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
        return manager.claimDailyReward(player, reward -> {
            paid.add(reward);
            return payWorks;
        });
    }

    // Reveals every slot of today's draw but the last
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

    private boolean claimedOnDisk() {
        return YamlConfiguration.loadConfiguration(new File(dir, "players.yml"))
            .getBoolean("players." + uuid + ".daily-reward-claimed");
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
        assertTrue(claimedOnDisk(), "the claim was not saved before the payout");
        assertTrue(chat.get(0).contains("x1") && chat.get(0).contains("Diamond"), chat.toString());

        // Another open or click the same day pays nothing more
        assertFalse(claim(player));
        assertEquals(1, paid.size());
    }

    @Test
    void theChatLineCarriesTheMultipliedAmount() throws ReflectiveOperationException {
        groups(Map.of("vip", STEEL));
        set(manager.getConfiguration(), "rewardMultiplier", 2);
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

        // Back-date the row to a day it was claimed on; get() rolls it on
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

        // Joined the group since, and opens the GUI again
        assertTrue(claim(player(Set.of("group.vip"))));
        assertEquals(1, paid.size());
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
    void nothingIsPaidWhenNoGroupsAreConfigured() throws ReflectiveOperationException {
        groups(Map.of());
        revealAllButLast();
        revealLast();
        assertFalse(claim(player(Set.of("group.vip"))));
        assertTrue(paid.isEmpty());
        assertFalse(data().dailyRewardClaimed());
    }

    // ====================================
    // Group matching on its own: the first listed group the player is in
    // wins, whatever order his permissions come in
    // ====================================
    @Test
    void theFirstListedGroupWins() {
        Map<String, RewardEntry> groups = new LinkedHashMap<>();
        groups.put("ascended", STEEL);
        groups.put("vip", DIAMOND);

        assertSame(STEEL, ActivityManager.dailyRewardFor(groups, Set.of("group.vip", "group.ascended")::contains));
        assertSame(DIAMOND, ActivityManager.dailyRewardFor(groups, Set.of("group.vip")::contains));
        assertNull(ActivityManager.dailyRewardFor(groups, Set.of("group.default", "vip")::contains));
    }
}
