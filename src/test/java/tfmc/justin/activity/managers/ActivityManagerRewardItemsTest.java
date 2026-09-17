package tfmc.justin.activity.managers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.RewardEntry;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// The item half of a reward payout: ActivityManager.giveItems, which takes the
// path resolver and the logger rather than reaching for TLibs and the plugin,
// so the handover can be driven headless. The player, his inventory and his
// world are Proxy stubs (the technique the listener tests use) that record
// what was added and what was dropped.
//
// Not reachable headless, and so not covered here: the real resolver
// (ActivityManager#resolveRewardItem) - both its TLibs branch, which needs
// TLibs, MMOItems and MythicLib running, and its Material branch, where
// new ItemStack(Material) and Material#isItem() both need the live registry of
// a running server (the isItem() guard that refuses a block-only name such as
// CARROTS is therefore only exercised in production); claim() itself, which
// needs a live store, a live Messages and Bukkit.dispatchCommand; and the real
// PlayerInventory#addItem stacking rules, which are server code. What the stubs
// stand in for is exactly those calls: addItem, getWorld, dropItemNaturally and
// updateInventory. Everything between them - resolution, the multiplier, the
// stack split, the leftover drop, the success rule - is the real code.
// ====================================
class ActivityManagerRewardItemsTest {

    private final List<LogRecord> logged = new ArrayList<>();

    // The memo that keeps an unresolvable path from warning once per click is
    // static, so each test starts from an empty one
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

    // What one stub player was handed and what fell on the floor
    private static final class Handover {
        final List<ItemStack> added = new ArrayList<>();
        final List<ItemStack> dropped = new ArrayList<>();
        Map<Integer, ItemStack> leftover = new HashMap<>();
        boolean addItemThrows;
        boolean dropThrows;
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

    // ====================================
    // A stack that can exist headless: the real ItemStack constructors go
    // through the Bukkit registry, which needs a running server, but the
    // protected no-arg one only nulls a field. Type, amount and the item's own
    // maximum stack size are held here and clone() is a fresh copy, which is
    // all giveItems touches.
    // ====================================
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

    // The real resolver's bare-material branch, without the TLibs one:
    // matchMaterial is a name lookup and runs headless, building the stack
    // does not
    private static final Function<String, ItemStack> MATERIALS = path -> {
        Material material = Material.matchMaterial(path);
        return material == null ? null : new TestStack(material, 1);
    };

    // The entry giveItems is handed: display matters only to the partial-payout
    // warning, so every test that does not check it uses the same one
    private static RewardEntry entry(RewardEntry.Item... items) {
        return new RewardEntry(1, "Reward", List.of(), List.of(items));
    }

    private boolean give(Handover handover, Function<String, ItemStack> resolver, RewardEntry.Item... items) {
        return give(handover, 1, resolver, items);
    }

    private boolean give(Handover handover, int multiplier, Function<String, ItemStack> resolver,
                         RewardEntry.Item... items) {
        return ActivityManager.giveItems(handover.player, entry(items), multiplier, 10, resolver, logger());
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

    // 'amount:' says how many to hand over, so it replaces whatever the
    // resolved stack was built with rather than multiplying it - and it is set
    // on a clone, so the resolver's own stack is untouched
    @Test
    void theAmountOverridesTheResolvedStackAndTheResolvedStackIsNotMutated() {
        Handover handover = player();
        ItemStack fromPath = new TestStack(Material.DIAMOND, 8);

        assertTrue(give(handover, path -> fromPath, new RewardEntry.Item("anything", 2)));

        assertEquals(2, handover.added.get(0).getAmount());
        assertEquals(8, fromPath.getAmount());
    }

    // ====================================
    // rewards.multiplier, applied at payout rather than at load so a reload
    // changes what the next claim pays
    // ====================================

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

    // ====================================
    // A total above one stack goes over as whole stacks of the resolved item's
    // own maximum, never as one oversized slot
    // ====================================

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

    // The finding this split exists for: 64 copies of an item whose stack size
    // is 1 in a single slot is a dupe primitive, so the item's own limit is
    // what the split uses - not the vanilla 64
    @Test
    void anUnstackableItemIsHandedOverOneAtATime() {
        Handover handover = player();
        ItemStack sword = new TestStack(Material.DIAMOND_SWORD, 1, 1);

        assertTrue(give(handover, 3, path -> sword, new RewardEntry.Item("m.sword.excalibur", 1)));

        assertEquals(List.of(1, 1, 1), handover.added.stream().map(ItemStack::getAmount).toList());
    }

    // Every stack of the split gets the same overflow treatment
    @Test
    void everyStackOfASplitDropsItsOwnOverflow() {
        Handover handover = player();
        handover.leftover = Map.of(0, new TestStack(Material.DIAMOND, 2));

        assertTrue(give(handover, 3, MATERIALS, new RewardEntry.Item("DIAMOND", 64)));

        assertEquals(3, handover.added.size());
        assertEquals(3, handover.dropped.size());
    }

    // TLibs down, or the MMOItems id deleted since load: nothing was handed
    // over, so claim() must see a failure and roll the milestone back - and it
    // must get there by the "could not be resolved" route, not by something
    // throwing on the way
    @Test
    void aPathThatDoesNotResolveAtPayoutHandsNothingOver() {
        Handover handover = player();

        assertFalse(give(handover, path -> null, new RewardEntry.Item("m.material.steel", 2)));

        assertTrue(handover.added.isEmpty());
        assertTrue(handover.dropped.isEmpty());
        assertTrue(loggedAtLeastOne(Level.WARNING, "could not be resolved"));
        assertFalse(loggedAtLeastOne(Level.SEVERE, "threw for"));
    }

    // A claim can be repeated at click rate, so a path that cannot resolve is
    // reported once rather than once per click
    @Test
    void anUnresolvablePathIsReportedOnlyOnce() {
        Handover handover = player();

        assertFalse(give(handover, path -> null, new RewardEntry.Item("m.material.steel", 1)));
        assertFalse(give(handover, path -> null, new RewardEntry.Item("m.material.steel", 1)));

        assertEquals(1, logged.stream().filter(record -> record.getMessage().contains("could not be resolved"))
            .count());
    }

    // The amount and the milestone are what an operator needs to re-issue the
    // reward by hand
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

    // A resolver that throws must not unwind out of the payout: claim() has
    // already burned and saved the milestones by then. Nothing reached the
    // inventory, so the milestone is still refused.
    @Test
    void aThrowingResolverIsSwallowedAndCountsAsNothingHandedOver() {
        Handover handover = player();

        assertFalse(give(handover, path -> {
            throw new IllegalStateException("boom");
        }, new RewardEntry.Item("DIAMOND", 1)));

        assertTrue(handover.added.isEmpty());
        assertTrue(loggedAtLeastOne(Level.SEVERE, "threw for"));
    }

    // ====================================
    // addItem is the point of no return: it can throw having already filled
    // some slots, so the entry counts as paid the moment the inventory is
    // touched. Counting it unpaid would hand the milestone back and let the
    // next click pay those same items a second time.
    // ====================================
    @Test
    void aThrowingAddItemStillCountsAsPaidBecauseTheInventoryWasTouched() {
        Handover handover = player();
        handover.addItemThrows = true;

        assertTrue(give(handover, MATERIALS, new RewardEntry.Item("DIAMOND", 1)));
        assertTrue(loggedAtLeastOne(Level.SEVERE, "threw for"));
    }

    // One bad path among good ones costs only itself: the rest is still paid,
    // and the entry counts as paid, so re-drawing it cannot duplicate them.
    // An operator gets one line naming the entry, since the milestone is gone.
    @Test
    void oneUnresolvablePathAmongGoodOnesStillCountsAsPaidForTheRest() {
        Handover handover = player();

        assertTrue(ActivityManager.giveItems(handover.player,
            new RewardEntry(1, "Steel Bundle", List.of(),
                List.of(new RewardEntry.Item("NOT_A_MATERIAL", 1), new RewardEntry.Item("DIAMOND", 1))),
            1, 20, MATERIALS, logger()));

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

    // ====================================
    // The drop happens after the entry is already counted as paid, on purpose:
    // a drop that throws must not un-pay a milestone whose items are already
    // in the inventory.
    // ====================================
    @Test
    void aThrowingDropStillLeavesTheEntryPaid() {
        Handover handover = player();
        handover.leftover = Map.of(0, new TestStack(Material.DIAMOND, 2));
        handover.dropThrows = true;

        assertTrue(give(handover, MATERIALS, new RewardEntry.Item("DIAMOND", 3)));
    }

    // The inventory is mutated inside a cancelled InventoryClickEvent, so the
    // client needs telling or it shows ghost stacks until the window reopens
    @Test
    void theClientIsResyncedOnceAfterAHandover() {
        Handover handover = player();

        assertTrue(give(handover, MATERIALS, new RewardEntry.Item("DIAMOND", 3)));

        assertEquals(1, handover.updates);
    }

    @Test
    void anEntryWithNoItemsHandsNothingOver() {
        Handover handover = player();

        assertFalse(give(handover, MATERIALS));
        assertTrue(handover.added.isEmpty());
        assertEquals(0, handover.updates);
    }

    // ====================================
    // The combine that decides whether a drawn entry counts as paid. This is
    // the whole duplicate-prevention rule for a mixed entry: an entry whose
    // items went out but whose commands failed must stay paid, or the next
    // click draws it again and hands those items over twice.
    // ====================================
    @Test
    void anEntryCountsAsPaidIfEitherHalfSucceeded() {
        assertTrue(ActivityManager.dispatchRewards(() -> true, () -> false));
        assertTrue(ActivityManager.dispatchRewards(() -> false, () -> true));
        assertTrue(ActivityManager.dispatchRewards(() -> true, () -> true));
        assertFalse(ActivityManager.dispatchRewards(() -> false, () -> false));
    }

    // Both halves always run: the commands are not skipped just because the
    // items already succeeded
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

    // ====================================
    // runnableEntries with items in the pool: an item never has a name pasted
    // into it, so it pays a Bedrock/unsafe name that no %player% command can.
    // ====================================
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
}
