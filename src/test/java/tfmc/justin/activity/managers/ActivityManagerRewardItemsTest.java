package tfmc.justin.activity.managers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
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
// (ActivityManager#resolveRewardItem) where it calls TLibsItems for an m. path
// - that needs TLibs, MMOItems and MythicLib running, and touching the class
// without them fails on class initialisation; claim() itself, which needs a
// live store, a live Messages and Bukkit.dispatchCommand; and the real
// PlayerInventory#addItem stacking rules, which are server code. What the
// stubs stand in for is exactly those three calls: addItem, getWorld and
// dropItem. Everything between them - resolution, the amount, the leftover
// drop, the success rule - is the real code.
// ====================================
class ActivityManagerRewardItemsTest {

    private static final Logger LOGGER = Logger.getLogger("ActivityManagerRewardItemsTest");

    // What one stub player was handed and what fell on the floor
    private static final class Handover {
        final List<ItemStack> added = new ArrayList<>();
        final List<ItemStack> dropped = new ArrayList<>();
        Map<Integer, ItemStack> leftover = new HashMap<>();
        Player player;
    }

    private static Handover player() {
        Handover handover = new Handover();
        UUID uuid = UUID.randomUUID();

        InvocationHandler worldHandler = (proxy, method, args) -> switch (method.getName()) {
            case "dropItem" -> {
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
    // protected no-arg one only nulls a field. Type and amount are held here
    // and clone() is a fresh copy, which is all giveItems touches.
    // ====================================
    private static final class TestStack extends ItemStack {
        private final Material type;
        private int amount;

        TestStack(Material type, int amount) {
            this.type = type;
            this.amount = amount;
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
        public TestStack clone() {
            return new TestStack(type, amount);
        }
    }

    // The real resolver's bare-material branch, without the TLibs one:
    // matchMaterial is a name lookup and runs headless, building the stack
    // does not
    private static final Function<String, ItemStack> MATERIALS = path -> {
        Material material = Material.matchMaterial(path);
        return material == null ? null : new TestStack(material, 1);
    };

    @Test
    void aResolvedPathReachesTheInventoryWithTheConfiguredAmount() {
        Handover handover = player();

        assertTrue(ActivityManager.giveItems(handover.player,
            List.of(new RewardEntry.Item("DIAMOND", 3)), MATERIALS, LOGGER));

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

        assertTrue(ActivityManager.giveItems(handover.player,
            List.of(new RewardEntry.Item("anything", 2)), path -> fromPath, LOGGER));

        assertEquals(2, handover.added.get(0).getAmount());
        assertEquals(8, fromPath.getAmount());
    }

    // TLibs down, or the MMOItems id deleted since load: nothing was handed
    // over, so claim() must see a failure and roll the milestone back
    @Test
    void aPathThatDoesNotResolveAtPayoutHandsNothingOver() {
        Handover handover = player();

        assertFalse(ActivityManager.giveItems(handover.player,
            List.of(new RewardEntry.Item("m.material.steel", 2)), path -> null, LOGGER));

        assertTrue(handover.added.isEmpty());
        assertTrue(handover.dropped.isEmpty());
    }

    @Test
    void anAirStackCountsAsUnresolvedRatherThanAsAReward() {
        Handover handover = player();

        assertFalse(ActivityManager.giveItems(handover.player,
            List.of(new RewardEntry.Item("m.material.steel", 2)),
            path -> new TestStack(Material.AIR, 1), LOGGER));

        assertTrue(handover.added.isEmpty());
    }

    // A resolver that throws must not unwind out of the payout: claim() has
    // already burned and saved the milestones by then
    @Test
    void aThrowingResolverIsSwallowedAndCountsAsNothingHandedOver() {
        Handover handover = player();

        assertFalse(ActivityManager.giveItems(handover.player,
            List.of(new RewardEntry.Item("DIAMOND", 1)), path -> {
                throw new IllegalStateException("boom");
            }, LOGGER));

        assertTrue(handover.added.isEmpty());
    }

    // One bad path among good ones costs only itself: the rest is still paid,
    // and the entry counts as paid, so re-drawing it cannot duplicate them
    @Test
    void oneUnresolvablePathAmongGoodOnesStillCountsAsPaidForTheRest() {
        Handover handover = player();

        assertTrue(ActivityManager.giveItems(handover.player,
            List.of(new RewardEntry.Item("NOT_A_MATERIAL", 1), new RewardEntry.Item("DIAMOND", 1)),
            MATERIALS, LOGGER));

        assertEquals(1, handover.added.size());
        assertEquals(Material.DIAMOND, handover.added.get(0).getType());
    }

    @Test
    void whatDoesNotFitIsDroppedAtThePlayersFeetRatherThanLost() {
        Handover handover = player();
        ItemStack overflow = new TestStack(Material.DIAMOND, 2);
        handover.leftover = Map.of(0, overflow);

        assertTrue(ActivityManager.giveItems(handover.player,
            List.of(new RewardEntry.Item("DIAMOND", 3)), MATERIALS, LOGGER));

        assertEquals(List.of(overflow), handover.dropped);
    }

    @Test
    void anEntryWithNoItemsHandsNothingOver() {
        Handover handover = player();

        assertFalse(ActivityManager.giveItems(handover.player, List.of(), MATERIALS, LOGGER));
        assertTrue(handover.added.isEmpty());
    }

    // ====================================
    // runnableEntries with items in the pool: an item never has a name pasted
    // into it, so it pays a Bedrock/unsafe name that no %player% command can.
    // ====================================
    @Test
    void anItemEntryIsRunnableForANameNoCommandCanBePaidFor() {
        RewardEntry playerOnly = new RewardEntry(1, "player-only", List.of("give %player% diamond 3"));
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
        RewardEntry commands = new RewardEntry(3, "commands", List.of("say hi"));
        RewardEntry items = new RewardEntry(1, "items", List.of(),
            List.of(new RewardEntry.Item("DIAMOND", 1)));
        List<RewardEntry> pool = List.of(commands, items);

        assertEquals(4, RewardEntry.totalWeight(pool));
        assertEquals(commands, RewardEntry.pick(pool, 2));
        assertEquals(items, RewardEntry.pick(pool, 3));
    }
}
