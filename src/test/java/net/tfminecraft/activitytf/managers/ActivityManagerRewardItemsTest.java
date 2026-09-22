package net.tfminecraft.activitytf.managers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.tfminecraft.activitytf.models.RewardEntry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityManagerRewardItemsTest {

    private final List<LogRecord> logged = new ArrayList<>();

    @BeforeEach
    void clearReportedPaths() {
        ActivityManager.reportedItemPaths.clear();
        logged.clear();
    }

    private Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private static final class Handover {
        final List<ItemStack> added = new ArrayList<>();
        final List<ItemStack> dropped = new ArrayList<>();
        Map<Integer, ItemStack> leftover = new HashMap<>();
        boolean addItemThrows;
        boolean dropThrows;
        boolean updateThrows;
        int updates;
        Player player;
    }

    private static Handover player() {
        Handover handover = new Handover();
        UUID uuid = UUID.randomUUID();

        InvocationHandler worldHandler = (proxy, method, args) -> switch (method.getName()) {
            case "dropItemNaturally" -> {
                if (handover.dropThrows) {
                    throw new IllegalStateException("drop boom");
                }
                handover.dropped.add((ItemStack) args[1]);
                yield null;
            }
            case "toString" -> "stub-world";
            case "hashCode" -> 1;
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("unexpected World#" + method.getName());
        };
        World world = (World) Proxy.newProxyInstance(
            ActivityManagerRewardItemsTest.class.getClassLoader(), new Class<?>[]{World.class}, worldHandler);

        InvocationHandler inventoryHandler = (proxy, method, args) -> switch (method.getName()) {
            case "addItem" -> {
                for (ItemStack stack : (ItemStack[]) args[0]) {
                    handover.added.add(stack);
                }
                if (handover.addItemThrows) {
                    throw new IllegalStateException("addItem boom");
                }
                yield new HashMap<>(handover.leftover);
            }
            case "toString" -> "stub-inventory";
            case "hashCode" -> 2;
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("unexpected Inventory#" + method.getName());
        };
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(
            ActivityManagerRewardItemsTest.class.getClassLoader(),
            new Class<?>[]{PlayerInventory.class}, inventoryHandler);

        InvocationHandler playerHandler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getInventory" -> inventory;
            case "getWorld" -> world;
            case "getLocation" -> new Location(world, 0, 64, 0);
            case "updateInventory" -> {
                handover.updates++;
                if (handover.updateThrows) {
                    throw new IllegalStateException("resync boom");
                }
                yield null;
            }
            case "toString" -> "stub-player";
            case "hashCode" -> uuid.hashCode();
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("unexpected Player#" + method.getName());
        };
        handover.player = (Player) Proxy.newProxyInstance(
            ActivityManagerRewardItemsTest.class.getClassLoader(), new Class<?>[]{Player.class}, playerHandler);

        return handover;
    }

    private static final class TestStack extends ItemStack {
        private final Material type;
        private final int maxStackSize;
        private int amount;

        TestStack(Material type, int amount) {
            this(type, amount, 64);
        }

        TestStack(Material type, int amount, int maxStackSize) {
            this.type = type;
            this.amount = amount;
            this.maxStackSize = maxStackSize;
        }

        @Override
        public Material getType() {
            return type;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public void setAmount(int amount) {
            this.amount = amount;
        }

        @Override
        public int getMaxStackSize() {
            return maxStackSize;
        }

        @Override
        public TestStack clone() {
            return new TestStack(type, amount, maxStackSize);
        }
    }

    private static final Function<String, ItemStack> MATERIALS = path -> {
        Material material = Material.matchMaterial(path);
        return material == null ? null : new TestStack(material, 1);
    };

    private static RewardEntry entry(RewardEntry.Item... items) {
        return new RewardEntry(1, "Reward", List.of(), List.of(items));
    }

    private boolean give(Handover handover, Function<String, ItemStack> resolver, RewardEntry.Item... items) {
        return give(handover, 1, resolver, items);
    }

    private boolean give(Handover handover, int multiplier, Function<String, ItemStack> resolver,
                         RewardEntry.Item... items) {
        return ActivityManager.giveItems(handover.player, entry(items), multiplier, "milestone 10", resolver,
            logger());
    }

    private boolean loggedAtLeastOne(Level level, String fragment) {
        return logged.stream().anyMatch(record -> record.getLevel() == level
            && record.getMessage().contains(fragment));
    }

    @Test
    void aResolvedPathReachesTheInventoryWithTheConfiguredAmount() {
        Handover handover = player();

        assertTrue(give(handover, MATERIALS, new RewardEntry.Item("DIAMOND", 3)));

        assertEquals(1, handover.added.size());
        assertEquals(Material.DIAMOND, handover.added.get(0).getType());
        assertEquals(3, handover.added.get(0).getAmount());
        assertTrue(handover.dropped.isEmpty());
    }

    @Test
    void theAmountOverridesTheResolvedStackAndTheResolvedStackIsNotMutated() {
        Handover handover = player();
        ItemStack fromPath = new TestStack(Material.DIAMOND, 8);

        assertTrue(give(handover, path -> fromPath, new RewardEntry.Item("anything", 2)));

        assertEquals(2, handover.added.get(0).getAmount());
        assertEquals(8, fromPath.getAmount());
    }

    @Test
    void theMultiplierMultipliesTheConfiguredAmount() {
        Handover handover = player();

        assertTrue(give(handover, 2, MATERIALS, new RewardEntry.Item("DIAMOND", 3)));

        assertEquals(1, handover.added.size());
        assertEquals(6, handover.added.get(0).getAmount());
    }

    @Test
    void aMultiplierOfOneHandsOverExactlyTheConfiguredAmount() {
        Handover handover = player();

        assertTrue(give(handover, 1, MATERIALS, new RewardEntry.Item("DIAMOND", 3)));

        assertEquals(3, handover.added.get(0).getAmount());
    }

    @Test
    void anExactMultipleOfTheStackSizeIsSplitIntoWholeStacks() {
        Handover handover = player();

        assertTrue(give(handover, 3, MATERIALS, new RewardEntry.Item("DIAMOND", 64)));

        assertEquals(List.of(64, 64, 64), handover.added.stream().map(ItemStack::getAmount).toList());
    }

    @Test
    void aTotalWithARemainderEndsWithThePartStack() {
        Handover handover = player();

        assertTrue(give(handover, 2, MATERIALS, new RewardEntry.Item("DIAMOND", 50)));

        assertEquals(List.of(64, 36), handover.added.stream().map(ItemStack::getAmount).toList());
    }

    @Test
    void anUnstackableItemIsHandedOverOneAtATime() {
        Handover handover = player();
        ItemStack sword = new TestStack(Material.DIAMOND_SWORD, 1, 1);

        assertTrue(give(handover, 3, path -> sword, new RewardEntry.Item("m.sword.excalibur", 1)));

        assertEquals(List.of(1, 1, 1), handover.added.stream().map(ItemStack::getAmount).toList());
    }

    @Test
    void everyStackOfASplitDropsItsOwnOverflow() {
        Handover handover = player();
        handover.leftover = Map.of(0, new TestStack(Material.DIAMOND, 2));

        assertTrue(give(handover, 3, MATERIALS, new RewardEntry.Item("DIAMOND", 64)));

        assertEquals(3, handover.added.size());
        assertEquals(3, handover.dropped.size());
    }

    @Test
    void aPathThatDoesNotResolveAtPayoutHandsNothingOver() {
        Handover handover = player();

        assertFalse(give(handover, path -> null, new RewardEntry.Item("m.material.steel", 2)));

        assertTrue(handover.added.isEmpty());
        assertTrue(handover.dropped.isEmpty());
        assertTrue(loggedAtLeastOne(Level.WARNING, "could not be resolved"));
        assertFalse(loggedAtLeastOne(Level.SEVERE, "threw for"));
    }

    @Test
    void anItemsAdderPathThatDoesNotResolveAtPayoutHandsNothingOver() {
        Handover handover = player();

        assertFalse(give(handover, path -> null, new RewardEntry.Item("ia.tfmc:saucepan", 2)));

        assertTrue(handover.added.isEmpty());
        assertTrue(handover.dropped.isEmpty());
        assertTrue(loggedAtLeastOne(Level.WARNING, "could not be resolved"));
        assertTrue(loggedAtLeastOne(Level.WARNING, "ia.tfmc:saucepan"));
        assertFalse(loggedAtLeastOne(Level.SEVERE, "threw for"));
    }

    @Test
    void anUnresolvablePathIsReportedOnlyOnce() {
        Handover handover = player();

        assertFalse(give(handover, path -> null, new RewardEntry.Item("m.material.steel", 1)));
        assertFalse(give(handover, path -> null, new RewardEntry.Item("m.material.steel", 1)));

        assertEquals(1, logged.stream().filter(record -> record.getMessage().contains("could not be resolved"))
            .count());
    }

    @Test
    void theUnresolvedWarningNamesTheTotalAndTheMilestone() {
        Handover handover = player();

        assertFalse(give(handover, 2, path -> null, new RewardEntry.Item("m.material.steel", 3)));

        assertTrue(loggedAtLeastOne(Level.WARNING, "(x6, milestone 10)"));
    }

    @Test
    void anAirStackCountsAsUnresolvedRatherThanAsAReward() {
        Handover handover = player();

        assertFalse(give(handover, path -> new TestStack(Material.AIR, 1),
            new RewardEntry.Item("m.material.steel", 2)));

        assertTrue(handover.added.isEmpty());
    }

    @Test
    void aThrowingResolverIsSwallowedAndCountsAsNothingHandedOver() {
        Handover handover = player();

        assertFalse(give(handover, path -> {
            throw new IllegalStateException("boom");
        }, new RewardEntry.Item("DIAMOND", 1)));

        assertTrue(handover.added.isEmpty());
        assertTrue(loggedAtLeastOne(Level.SEVERE, "threw for"));
    }

    @Test
    void aThrowingAddItemStillCountsAsPaidBecauseTheInventoryWasTouched() {
        Handover handover = player();
        handover.addItemThrows = true;

        assertTrue(give(handover, MATERIALS, new RewardEntry.Item("DIAMOND", 1)));
        assertTrue(loggedAtLeastOne(Level.SEVERE, "threw for"));
    }

    @Test
    void oneUnresolvablePathAmongGoodOnesStillCountsAsPaidForTheRest() {
        Handover handover = player();

        assertTrue(ActivityManager.giveItems(handover.player,
            new RewardEntry(1, "Steel Bundle", List.of(),
                List.of(new RewardEntry.Item("NOT_A_MATERIAL", 1), new RewardEntry.Item("DIAMOND", 1))),
            1, "milestone 20", MATERIALS, logger()));

        assertEquals(1, handover.added.size());
        assertEquals(Material.DIAMOND, handover.added.get(0).getType());
        assertTrue(loggedAtLeastOne(Level.WARNING, "Only part of reward 'Steel Bundle'"));
    }

    @Test
    void whatDoesNotFitIsDroppedAtThePlayersFeetRatherThanLost() {
        Handover handover = player();
        ItemStack overflow = new TestStack(Material.DIAMOND, 2);
        handover.leftover = Map.of(0, overflow);

        assertTrue(give(handover, MATERIALS, new RewardEntry.Item("DIAMOND", 3)));

        assertEquals(List.of(overflow), handover.dropped);
    }

    @Test
    void aThrowingDropStillLeavesTheEntryPaid() {
        Handover handover = player();
        handover.leftover = Map.of(0, new TestStack(Material.DIAMOND, 2));
        handover.dropThrows = true;

        assertTrue(give(handover, MATERIALS, new RewardEntry.Item("DIAMOND", 3)));
    }

    @Test
    void theClientIsResyncedOnceAfterAHandover() {
        Handover handover = player();

        assertTrue(give(handover, MATERIALS, new RewardEntry.Item("DIAMOND", 3)));

        assertEquals(1, handover.updates);
    }

    @Test
    void theBlockOnlyGuardPassesAnythingThroughWithoutARegistry() {
        assertTrue(ActivityManager.isItem(new TestStack(Material.DIAMOND, 1)));
        assertTrue(ActivityManager.isItem(new TestStack(Material.CARROTS, 1)));
    }

    @Test
    void aThrowingResyncIsSwallowedAndLeavesTheEntryPaid() {
        Handover handover = player();
        handover.updateThrows = true;

        assertTrue(give(handover, MATERIALS, new RewardEntry.Item("DIAMOND", 1)));

        assertEquals(1, handover.added.size());
        assertTrue(loggedAtLeastOne(Level.WARNING, "Could not resync the inventory"));
    }

    @Test
    void aThrowingFirstInsertIsNotReportedAsAPartialHandover() {
        Handover handover = player();
        handover.addItemThrows = true;

        assertTrue(ActivityManager.giveItems(handover.player,
            new RewardEntry(1, "Steel Bundle", List.of(), List.of(new RewardEntry.Item("DIAMOND", 1))),
            1, "milestone 20", MATERIALS, logger()));

        assertTrue(loggedAtLeastOne(Level.SEVERE, "threw for"));
        assertFalse(loggedAtLeastOne(Level.WARNING, "Only part of reward"));
    }

    @Test
    void anEntryWithNoItemsHandsNothingOver() {
        Handover handover = player();

        assertFalse(give(handover, MATERIALS));
        assertTrue(handover.added.isEmpty());
        assertEquals(0, handover.updates);
    }

    @Test
    void anEntryCountsAsPaidIfEitherHalfSucceeded() {
        assertTrue(ActivityManager.dispatchRewards(() -> true, () -> false));
        assertTrue(ActivityManager.dispatchRewards(() -> false, () -> true));
        assertTrue(ActivityManager.dispatchRewards(() -> true, () -> true));
        assertFalse(ActivityManager.dispatchRewards(() -> false, () -> false));
    }

    @Test
    void bothHalvesOfAnEntryAlwaysRun() {
        boolean[] ran = new boolean[2];

        assertTrue(ActivityManager.dispatchRewards(() -> {
            ran[0] = true;
            return true;
        }, () -> {
            ran[1] = true;
            return false;
        }));

        assertTrue(ran[0]);
        assertTrue(ran[1]);
    }

    @Test
    void anItemEntryIsRunnableForANameNoCommandCanBePaidFor() {
        RewardEntry playerOnly = new RewardEntry(1, "player-only",
            List.of("give %player% diamond 3"), List.of());
        RewardEntry itemOnly = new RewardEntry(1, "items", List.of(),
            List.of(new RewardEntry.Item("m.material.steel", 3)));
        RewardEntry both = new RewardEntry(1, "both", List.of("give %player% diamond 3"),
            List.of(new RewardEntry.Item("DIAMOND", 1)));
        List<RewardEntry> pool = List.of(playerOnly, itemOnly, both);

        assertEquals(List.of(itemOnly, both), ActivityManager.runnableEntries(pool, "Bedrock Player"));
        assertEquals(pool, ActivityManager.runnableEntries(pool, "Notch"));
    }

    @Test
    void theWeightedDrawStillWalksAPoolOfMixedEntries() {
        RewardEntry commands = new RewardEntry(3, "commands", List.of("say hi"), List.of());
        RewardEntry items = new RewardEntry(1, "items", List.of(),
            List.of(new RewardEntry.Item("DIAMOND", 1)));
        List<RewardEntry> pool = List.of(commands, items);

        assertEquals(4, RewardEntry.totalWeight(pool));
        assertEquals(commands, RewardEntry.pick(pool, 2));
        assertEquals(items, RewardEntry.pick(pool, 3));
    }

    private static final Function<String, ItemStack> TLIBS_ITEMS = path -> new TestStack(Material.IRON_INGOT, 1);

    private static final Function<String, ItemStack> IA_ITEMS = id -> new TestStack(Material.DIAMOND, 1);

    private static ItemStack resolveReward(String path, boolean tlibs, boolean ia) {
        return ActivityManager.resolveRewardItem(path, tlibs, ia, TLIBS_ITEMS, IA_ITEMS);
    }

    @Test
    void anItemsAdderRewardPathNeedsOnlyTheItemsAdderGate() {
        assertEquals(Material.DIAMOND, resolveReward("ia.tfmc:saucepan", false, true).getType());
        assertEquals(Material.DIAMOND, resolveReward("ia.tfmc:saucepan", true, true).getType());
        assertNull(resolveReward("ia.tfmc:saucepan", true, false));
        assertNull(resolveReward("ia.tfmc:saucepan", false, false));
    }

    @Test
    void aTLibsRewardPathNeedsOnlyTheItemPathsGate() {
        assertEquals(Material.IRON_INGOT, resolveReward("m.material.steel", true, false).getType());
        assertEquals(Material.IRON_INGOT, resolveReward("m.material.steel", true, true).getType());
        assertNull(resolveReward("m.material.steel", false, true));
        assertNull(resolveReward("m.material.steel", false, false));
    }

    @Test
    void aMalformedItemsAdderRewardPathResolvesToNothing() {
        assertNull(resolveReward("ia.saucepan", true, true));
    }

    private static final RewardEntry DIAMONDS = new RewardEntry(1, "Diamond", List.of(),
        List.of(new RewardEntry.Item("DIAMOND", 3)));
    private static final RewardEntry POOLED = new RewardEntry(1, "pooled", List.of("say hi"), List.of());

    private static final Function<List<RewardEntry>, RewardEntry> NO_DRAW = pool -> {
        throw new AssertionError("a fixed drop drew from the pool");
    };

    private static Function<String, List<RewardEntry>> pools(RewardEntry... entries) {
        List<RewardEntry> pool = List.of(entries);
        return name -> pool;
    }

    @Test
    void aFixedDropIsPaidWithoutDrawingFromThePool() {
        List<RewardEntry> spins = ActivityManager.rewardFor(10, Map.of(10, DIAMONDS), 1, pools(POOLED), NO_DRAW);

        assertEquals(1, spins.size());
        RewardEntry paid = spins.get(0);
        assertEquals(DIAMONDS.items(), paid.items());
        assertTrue(paid.commands().isEmpty());
    }

    @Test
    void aPoolMilestoneStillDrawsFromThePool() {
        List<List<RewardEntry>> drawnFrom = new ArrayList<>();
        List<RewardEntry> drawn = ActivityManager.rewardFor(20, Map.of(10, DIAMONDS), 1, pools(POOLED), pool -> {
            drawnFrom.add(pool);
            return pool.get(0);
        });

        assertEquals(List.of(POOLED), drawn);
        assertEquals(List.of(List.of(POOLED)), drawnFrom);
    }

    @Test
    void aFixedDropsChatLineCarriesTheMultipliedAmount() {
        assertEquals("#50d990x3 #b8906eDiamond",
            ActivityManager.rewardFor(10, Map.of(10, DIAMONDS), 1, pools(), NO_DRAW).get(0).display());
        assertEquals("#50d990x6 #b8906eDiamond",
            ActivityManager.rewardFor(10, Map.of(10, DIAMONDS), 2, pools(), NO_DRAW).get(0).display());
    }

    @Test
    void aFixedDropIsOnePayoutAtAnyMultiplier() {
        List<RewardEntry> spins = ActivityManager.rewardFor(10, Map.of(10, DIAMONDS), 3, pools(POOLED), NO_DRAW);

        assertEquals(1, spins.size());
        assertEquals(DIAMONDS.items(), spins.get(0).items());
        assertEquals("#50d990x9 #b8906eDiamond", spins.get(0).display());
    }

    private static final RewardEntry STEEL_PICK = new RewardEntry(1, "Steel Pickaxe", List.of("say pick"),
        List.of(new RewardEntry.Item("m.tool.steel_pickaxe", 1)));

    @Test
    void aPoolMilestoneIsSpunMultiplierTimes() {
        List<RewardEntry> pool = List.of(POOLED, STEEL_PICK);
        List<RewardEntry> rolls = new ArrayList<>(List.of(STEEL_PICK, POOLED, STEEL_PICK));
        List<List<RewardEntry>> drawnFrom = new ArrayList<>();

        List<RewardEntry> spins = ActivityManager.rewardFor(20, Map.of(10, DIAMONDS), 3, name -> pool, p -> {
            drawnFrom.add(p);
            return rolls.remove(0);
        });

        assertEquals(List.of(STEEL_PICK, POOLED, STEEL_PICK), spins);
        assertEquals(List.of(pool, pool, pool), drawnFrom);
    }

    @Test
    void aMultiplierOfOneSpinsThePoolOnce() {
        int[] draws = {0};
        List<RewardEntry> spins = ActivityManager.rewardFor(20, Map.of(), 1, pools(POOLED, STEEL_PICK), p -> {
            draws[0]++;
            return p.get(1);
        });

        assertEquals(List.of(STEEL_PICK), spins);
        assertEquals(1, draws[0]);
    }

    @Test
    void spinsCanRepeatAnEntryAtItsOwnAmount() {
        List<RewardEntry> spins = ActivityManager.rewardFor(20, Map.of(), 2, pools(DIAMONDS, POOLED),
            p -> p.get(0));

        assertEquals(List.of(DIAMONDS, DIAMONDS), spins);
        assertEquals(3, spins.get(1).items().get(0).amount());
        assertEquals("Diamond", spins.get(1).display());
    }

    @Test
    void everyDueMilestoneFixedNeedsNoPool() {
        assertFalse(ActivityManager.needsPool(List.of(10, 20), Map.of(10, DIAMONDS, 20, DIAMONDS)));
    }

    @Test
    void aMixOfFixedAndPoolMilestonesNeedsThePool() {
        assertTrue(ActivityManager.needsPool(List.of(10, 20), Map.of(10, DIAMONDS)));
    }

    @Test
    void noDropsAtAllNeedsThePool() {
        assertTrue(ActivityManager.needsPool(List.of(10), Map.of()));
    }

    private record PaidSpin(int milestone, RewardEntry entry, int multiplier) {}

    private static final Function<List<RewardEntry>, RewardEntry> FIRST = p -> p.get(0);

    private ActivityManager.Payout payMilestones(List<Integer> due, Map<Integer, RewardEntry> drops,
                                                 int multiplier, List<PaidSpin> calls, boolean... answers) {
        int[] next = {0};
        return ActivityManager.payMilestones(due, drops, multiplier, pools(POOLED), FIRST,
            (milestone, entry, m) -> {
                calls.add(new PaidSpin(milestone, entry, m));
                return next[0] < answers.length ? answers[next[0]++] : true;
            }, "Steve/uuid", logger());
    }

    @Test
    void aPoolSpinIsPaidAtMultiplierOne() {
        List<PaidSpin> calls = new ArrayList<>();

        ActivityManager.Payout payout = payMilestones(List.of(20), Map.of(), 3, calls);

        assertEquals(new ActivityManager.Payout(1, false), payout);
        assertEquals(List.of(new PaidSpin(20, POOLED, 1), new PaidSpin(20, POOLED, 1),
            new PaidSpin(20, POOLED, 1)), calls);
    }

    @Test
    void aFixedDropIsPaidAtTheConfiguredMultiplier() {
        List<PaidSpin> calls = new ArrayList<>();

        ActivityManager.Payout payout = payMilestones(List.of(10), Map.of(10, DIAMONDS), 3, calls);

        assertEquals(new ActivityManager.Payout(1, false), payout);
        assertEquals(1, calls.size());
        assertEquals(3, calls.get(0).multiplier());
        assertEquals(DIAMONDS.items(), calls.get(0).entry().items());
    }

    @Test
    void aFailedSpinDoesNotStopTheOthers() {
        List<PaidSpin> calls = new ArrayList<>();

        ActivityManager.Payout payout = payMilestones(List.of(20), Map.of(), 3, calls, true, false, true);

        assertEquals(new ActivityManager.Payout(1, true), payout);
        assertEquals(3, calls.size());
        assertTrue(loggedAtLeastOne(Level.SEVERE,
            "Milestone 20 paid 2 of 3 spins for Steve/uuid; it stays claimed, so 1 spin is owed - see the "
                + "'milestone 20' lines above for what failed."));
    }

    @Test
    void everyPartialMilestoneIsLogged() {
        payMilestones(List.of(20), Map.of(), 2, new ArrayList<>(), false, true);
        payMilestones(List.of(20), Map.of(), 2, new ArrayList<>(), false, true);

        assertEquals(2, logged.stream().filter(r -> r.getLevel() == Level.SEVERE).count());
    }

    @Test
    void aMilestoneWhoseSpinsAllFailIsNotCounted() {
        List<PaidSpin> calls = new ArrayList<>();

        ActivityManager.Payout payout = payMilestones(List.of(20, 30), Map.of(), 2, calls, false, false);

        assertEquals(new ActivityManager.Payout(0, true), payout);
        assertEquals(2, calls.size());
        assertFalse(loggedAtLeastOne(Level.SEVERE, "spins for"));
    }

    @Test
    void aPartialMilestoneStopsBeforeTheNextOneAndStaysClaimed() {
        List<PaidSpin> calls = new ArrayList<>();

        ActivityManager.Payout payout = payMilestones(List.of(20, 30), Map.of(), 2, calls, true, false);

        assertEquals(new ActivityManager.Payout(1, true), payout);
        assertEquals(2, calls.size());
        assertEquals(20, calls.get(0).milestone());
        assertEquals(20, calls.get(1).milestone());

        assertEquals(20, ActivityManager.rollbackClaimedPoints(10, payout.paid(), List.of(20, 30)));
    }

    @Test
    void theLogNamesTheDailyRewardRatherThanAMilestone() {
        Handover handover = player();

        assertFalse(ActivityManager.giveItems(handover.player, entry(new RewardEntry.Item("NOT_A_MATERIAL", 2)),
            1, "daily reward", MATERIALS, logger()));

        assertTrue(loggedAtLeastOne(Level.WARNING, "(x2, daily reward) could not be resolved"));
        assertFalse(loggedAtLeastOne(Level.WARNING, "milestone"));
    }
    @Test
    void eachMilestoneDrawsOnlyItsNamedPoolWithIndependentSpins() {
        Map<Integer, RewardEntry> drops = Map.of(10,
            net.tfminecraft.activitytf.config.ActivityConfiguration.poolRef("pool_prologue"), 20,
            net.tfminecraft.activitytf.config.ActivityConfiguration.poolRef("pool_end"));
        Map<String, List<RewardEntry>> pools = Map.of("pool_prologue", List.of(DIAMONDS),
            "pool_end", List.of(POOLED));
        List<PaidSpin> calls = new ArrayList<>();
        assertEquals(new ActivityManager.Payout(2, false), ActivityManager.payMilestones(
            List.of(10, 20), drops, 2, name -> pools.getOrDefault(name, List.of()), FIRST,
            (milestone, reward, multiplier) -> { calls.add(new PaidSpin(milestone, reward, multiplier)); return true; },
            "test", logger()));
        assertEquals(List.of(new PaidSpin(10, DIAMONDS, 1), new PaidSpin(10, DIAMONDS, 1),
            new PaidSpin(20, POOLED, 1), new PaidSpin(20, POOLED, 1)), calls);
        assertEquals(new ActivityManager.Payout(0, true), ActivityManager.payMilestones(
            List.of(10), drops, 1, name -> List.of(), pool -> RewardEntry.pick(pool, 0),
            (milestone, reward, multiplier) -> { throw new AssertionError("Missing pool paid"); }, "test", logger()));
    }

}
