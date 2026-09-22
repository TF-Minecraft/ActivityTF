package net.tfminecraft.activitytf.managers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.config.Messages;
import net.tfminecraft.activitytf.gui.ActivityGui;
import net.tfminecraft.activitytf.hooks.ItemsAdderItems;
import net.tfminecraft.activitytf.hooks.TLibsItems;
import net.tfminecraft.activitytf.models.ActivityDef;
import net.tfminecraft.activitytf.models.PlayerData;
import net.tfminecraft.activitytf.models.RecordResult;
import net.tfminecraft.activitytf.models.Recorded;
import net.tfminecraft.activitytf.models.RewardEntry;
import net.tfminecraft.activitytf.store.PlayerStore;
import net.tfminecraft.activitytf.utils.ItemPath;
import net.tfminecraft.activitytf.utils.Utils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

public class ActivityManager {

    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_.]{1,16}$");

    private static final long MINUTE_TICKS = 60L * 20L;

    private static volatile ActivityManager instance;

    private final JavaPlugin plugin;
    private final ActivityConfiguration config;
    private final PlayerStore store;

    private final Set<String> warnedStoreNotLoaded = new HashSet<>();

    private final Set<String> warnedEmptyPools = new HashSet<>();

    private static String poolPaths(Set<String> pools) {
        return pools.stream().map(name -> name.equals(ActivityConfiguration.DEFAULT_POOL) ? "rewards.pool"
            : "rewards.pools." + name + " (or rewards." + name + ")")
            .collect(Collectors.joining(", "));
    }

    static final Set<String> reportedItemPaths = ConcurrentHashMap.newKeySet();

    static final Set<String> reportedBrokenCommands = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Long> clickCooldowns = new ConcurrentHashMap<>();

    private double clickTokens;
    private long clickTokensAt;
    private boolean clickTokensPrimed;

    private BukkitTask playtime;

    private ActivityManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = new ActivityConfiguration(plugin);
        this.store = new PlayerStore(plugin, config);
    }

    public static ActivityManager getInstance(JavaPlugin plugin) {
        if (instance == null) {
            instance = new ActivityManager(plugin);
        }
        return instance;
    }

    public static ActivityManager getInstance() {
        return instance;
    }

    public static boolean isSafeCommandName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    public static boolean canRunCommand(String command, String name) {
        return command != null && (!command.contains("%player%") || isSafeCommandName(name));
    }

    public void initialize() {
        config.load();
        store.load();
        store.startAutoSave();
        startPlaytime();
    }

    private void startPlaytime() {
        if (playtime != null) {
            playtime.cancel();
        }

        playtime = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Duration afk = afkThreshold(config.afkMinutes());
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (afk != null && player.getIdleDuration().compareTo(afk) >= 0) {
                    continue;
                }
                recordAction(player.getUniqueId(), "playtime", 1);
            }
        }, MINUTE_TICKS, MINUTE_TICKS);
    }

    static Duration afkThreshold(int minutes) {
        return minutes <= 0 ? null : Duration.ofMinutes(minutes);
    }

    public void shutdown() {
        if (playtime != null) {
            playtime.cancel();
            playtime = null;
        }
        store.shutdown();
        instance = null;
    }

    public ActivityConfiguration getConfiguration() {
        return config;
    }

    public PlayerStore getStore() {
        return store;
    }

    public Logger logger() {
        return plugin.getLogger();
    }

    public Recorded recordAction(UUID uuid, String activityId, int amount) {
        return record(uuid, activityId, amount, true).outcome();
    }

    public RecordResult recordAdmin(UUID uuid, String activityId, int amount, boolean force) {
        return record(uuid, activityId, amount, !force);
    }

    private RecordResult record(UUID uuid, String activityId, int amount, boolean gated) {
        ActivityDef def = config.activity(activityId);
        if (def == null || amount <= 0) {
            return new RecordResult(0, 0, Recorded.UNKNOWN);
        }

        PlayerData data = gated ? store.rolled(uuid) : store.get(uuid);
        if (gated && (data == null || !data.isRevealed(activityId))) {
            return new RecordResult(0, 0, Recorded.NOT_A_TASK);
        }

        RecordResult result = gated
            ? data.record(amount, def, config.barMax(), config.dailyMax(),
                config.activity("vote") == null ? config.dailyMax() : config.nonVoteDailyMax(),
                config.milestones())
            : data.recordForced(amount, def, config.barMax(), config.milestones());
        return credited(uuid, data, result, Utils.colorize(def.display()));
    }

    public RecordResult recordPoints(UUID uuid, int points) {
        if (points <= 0) {
            return new RecordResult(0, 0, Recorded.UNKNOWN);
        }
        PlayerData data = store.get(uuid);
        return credited(uuid, data, data.creditForced(points, config.barMax(), config.milestones()),
            config.messages().get("points-granted-source"));
    }

    private RecordResult credited(UUID uuid, PlayerData data, RecordResult result, String source) {
        store.markDirty();

        if (result.pointsAwarded() <= 0) {
            return result;
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return result;
        }

        Messages messages = config.messages();
        player.sendMessage(messages.get("points-earned",
            "%activity%", source,
            "%points%", result.pointsAwarded(),
            "%total%", data.points(),
            "%max%", config.barMax()));
        playSound(player, config.goalCompleteSound());

        if (result.milestonesReached() > 0) {
            player.sendMessage(messages.get("reward-ready"));
            playSound(player, config.barCompleteSound());
        }
        return result;
    }

    public boolean isTracked(UUID uuid, String activityId) {
        PlayerData data = store.rolled(uuid);
        return config.activity(activityId) != null && data != null && data.isRevealed(activityId);
    }

    public PlayerData tasks(UUID uuid) {
        PlayerData data = store.get(uuid);
        if (ensureTasks(data)) {
            store.markDirty();
        }
        return data;
    }

    private boolean ensureTasks(PlayerData data) {
        return data.ensureTasks(activityIds(), config.guaranteed(), ThreadLocalRandom.current());
    }

    private List<String> activityIds() {
        return config.activities().stream().map(ActivityDef::id).toList();
    }

    public enum Rerolled {
        DONE,
        NONE_LEFT,
        TOO_LATE,
        DISABLED,
        FAILED
    }

    public Rerolled reroll(UUID uuid) {
        int perDay = config.rerollsPerDay();
        if (perDay <= 0) {
            return Rerolled.DISABLED;
        }

        if (storeNeverLoaded("Refusing every reroll")) {
            return Rerolled.FAILED;
        }

        PlayerData data = store.get(uuid);
        if (data.dailyPoints() > config.rerollMaxPoints()) {
            return Rerolled.TOO_LATE;
        }

        if (data.rerolls() >= perDay) {
            return Rerolled.NONE_LEFT;
        }

        data.reroll(PlayerData.draw(activityIds(), config.guaranteed(), ThreadLocalRandom.current()),
            config.barMax());
        store.markDirty();
        return Rerolled.DONE;
    }

    private boolean storeNeverLoaded(String refusing) {
        if (store.isLoaded()) {
            return false;
        }
        if (warnedStoreNotLoaded.add(refusing)) {
            plugin.getLogger().severe(refusing + ": " + PlayerStore.FILE
                + " was never loaded, so nothing done here could be saved.");
        }
        return true;
    }

    public record Reveal(String revealedId, boolean drawChanged) {
    }

    public Reveal reveal(UUID uuid, int slot) {
        PlayerData data = store.get(uuid);
        List<String> before = data.tasks();
        String clicked = slot >= 0 && slot < before.size() ? before.get(slot) : null;

        boolean drawChanged = ensureTasks(data);
        int index = clicked == null ? slot : data.tasks().indexOf(clicked);

        boolean revealed = index >= 0 && index < data.tasks().size()
            && config.activity(data.tasks().get(index)) != null
            && data.reveal(index);

        if (drawChanged || revealed) {
            store.markDirty();
        }
        return new Reveal(revealed ? data.tasks().get(index) : null, drawChanged);
    }

    public int claim(Player player) {
        Messages messages = config.messages();

        if (storeNeverLoaded("Refusing every reward claim")) {
            player.sendMessage(messages.get("reward-failed"));
            return 0;
        }

        PlayerData data = store.get(player.getUniqueId());
        List<Integer> due = PlayerData.due(data.points(), data.claimedPoints(), config.milestones());
        if (due.isEmpty()) {
            return 0;
        }

        Map<Integer, RewardEntry> drops = config.milestoneDrops();
        Set<String> needed = neededPools(due, drops);
        boolean needsPool = !needed.isEmpty();

        Set<String> empty = needed.stream().filter(name -> config.rewardPool(name).isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!empty.isEmpty()) {
            if (warnedEmptyPools.add(String.join(", ", empty))) {
                plugin.getLogger().warning("Handed nothing to " + player.getUniqueId()
                    + ": " + poolPaths(empty) + " has no usable entry, so the milestone stays claimable.");
            }
            player.sendMessage(messages.get("reward-unconfigured"));
            return 0;
        }

        Map<String, List<RewardEntry>> snapshot = new HashMap<>();
        for (String name : needed) {
            snapshot.put(name, runnableEntries(config.rewardPool(name), player.getName()));
        }
        Function<String, List<RewardEntry>> pools = name -> snapshot.getOrDefault(name, List.of());
        if (needsPool && needed.stream().anyMatch(name -> pools.apply(name).isEmpty())) {
            plugin.getLogger().warning("No reward command could be run for '"
                + Utils.safeForLog(player.getName()) + "': the name cannot be safely pasted into a console"
                + " command. Use %uuid%-based reward commands to support Bedrock/unsafe names.");
            player.sendMessage(messages.get("reward-failed"));
            return 0;
        }

        int claimedBefore = data.claimedPoints();
        int claimedAfter = Collections.max(due);
        data.setClaimedPoints(claimedAfter);
        store.markDirty();

        if (!store.saveNow()) {
            data.setClaimedPoints(claimedBefore);
            store.markDirty();
            plugin.getLogger().severe("Handed nothing to " + player.getUniqueId() + ": claimed-points could"
                + " not be saved before the rewards ran (would have gone " + claimedBefore + " -> "
                + claimedAfter + "), so nothing was dispatched.");
            player.sendMessage(messages.get("reward-failed"));
            return 0;
        }

        Payout payout = payMilestones(due, drops, config.rewardMultiplier(), pools, ActivityManager::draw,
            (milestone, drawn, itemMultiplier) -> {
                if (!dispatchRewards(player, drawn, "milestone " + milestone, itemMultiplier)) {
                    return false;
                }
                player.sendMessage(messages.get("reward-claimed", "%reward%", Utils.colorize(drawn.display())));
                return true;
            },
            Utils.safeForLog(player.getName()) + "/" + player.getUniqueId(), plugin.getLogger());
        int paid = payout.paid();
        boolean spinFailed = payout.failed();

        if (paid < due.size()) {
            int rolledBack = rollbackClaimedPoints(claimedBefore, paid, due);
            data.setClaimedPoints(rolledBack);
            store.markDirty();
            if (rolledBack == claimedBefore) {
                store.saveSoon();
                plugin.getLogger().warning("Reward payout for " + player.getUniqueId() + " paid none of the"
                    + " milestones " + due + ": claimed-points is back at " + claimedBefore + " in memory but"
                    + " still " + claimedAfter + " on disk until the queued save lands.");
            } else if (!store.saveNow()) {
                plugin.getLogger().severe("Reward payout for " + player.getUniqueId() + " is out of sync:"
                    + " paid " + paid + " of the milestones " + due + ", claimed-points is " + rolledBack
                    + " in memory but " + claimedAfter + " on disk. Repair " + PlayerStore.FILE + " by hand.");
            }
            player.sendMessage(messages.get("reward-failed"));
            return paid;
        }

        if (spinFailed) {
            player.sendMessage(messages.get("reward-failed"));
            return paid;
        }

        playSound(player, config.barCompleteSound());
        return paid;
    }

    record Payout(int paid, boolean failed) {}

    @FunctionalInterface
    interface SpinPayer {
        boolean pay(int milestone, RewardEntry entry, int multiplier);
    }

    static Payout payMilestones(List<Integer> due, Map<Integer, RewardEntry> drops, int multiplier,
                                Function<String, List<RewardEntry>> pools,
                                Function<List<RewardEntry>, RewardEntry> draw,
                                SpinPayer pay, String who, Logger logger) {
        int paid = 0;
        for (int milestone : due) {
            int itemMultiplier = poolOf(drops.get(milestone)) == null ? multiplier : 1;
            List<RewardEntry> spins = rewardFor(milestone, drops, multiplier, pools, draw);
            int spun = 0;
            for (RewardEntry drawn : spins) {
                if (drawn != null && pay.pay(milestone, drawn, itemMultiplier)) {
                    spun++;
                }
            }
            if (spun == 0) {
                return new Payout(paid, true);
            }
            paid++;
            if (spun < spins.size()) {
                int owed = spins.size() - spun;
                logger.severe("Milestone " + milestone + " paid " + spun + " of " + spins.size() + " spins for "
                    + who + "; it stays claimed, so " + owed + (owed == 1 ? " spin is" : " spins are")
                    + " owed - see the 'milestone " + milestone + "' lines above for what failed.");
                return new Payout(paid, true);
            }
        }
        return new Payout(paid, false);
    }

    public static boolean needsPool(List<Integer> milestones, Map<Integer, RewardEntry> drops) {
        return !neededPools(milestones, drops).isEmpty();
    }

    public static Set<String> neededPools(List<Integer> milestones, Map<Integer, RewardEntry> drops) {
        Set<String> pools = new LinkedHashSet<>();
        for (int milestone : milestones) {
            String pool = poolOf(drops.get(milestone));
            if (pool != null) {
                pools.add(pool);
            }
        }
        return pools;
    }

    private static String poolOf(RewardEntry drop) {
        return drop == null ? ActivityConfiguration.DEFAULT_POOL : ActivityConfiguration.referencedPool(drop);
    }

    static List<RewardEntry> rewardFor(int milestone, Map<Integer, RewardEntry> drops, int multiplier,
                                       Function<String, List<RewardEntry>> pools,
                                       Function<List<RewardEntry>, RewardEntry> draw) {
        RewardEntry fixed = drops.get(milestone);
        String pool = poolOf(fixed);
        if (pool == null) {
            return List.of(withPaidAmount(fixed, multiplier));
        }
        List<RewardEntry> entries = pools.apply(pool);
        List<RewardEntry> spins = new ArrayList<>();
        for (int spin = 0; spin < multiplier; spin++) {
            spins.add(draw.apply(entries));
        }
        return spins;
    }

    static RewardEntry withPaidAmount(RewardEntry fixed, int multiplier) {
        return new RewardEntry(fixed.weight(), "#50d990x" + fixed.items().get(0).amount() * multiplier
            + " #b8906e" + fixed.display(), fixed.commands(), fixed.items());
    }

    public boolean claimDailyReward(Player player) {
        return claimDailyReward(player, path -> usable(resolveRewardItem(path)),
            (reward, multiplier) -> dispatchRewards(player, reward, "daily reward", multiplier));
    }

    boolean claimDailyReward(Player player, Predicate<String> resolvable,
                             BiPredicate<RewardEntry, Integer> pay) {
        PlayerData data = tasks(player.getUniqueId());
        if (data.dailyRewardClaimed() || !data.allRevealed()) {
            return false;
        }
        RewardEntry reward = dailyRewardFor(config.dailyRewards(), player);
        if (reward == null) {
            return false;
        }

        Messages messages = config.messages();
        String fromPool = ActivityConfiguration.referencedPool(reward);
        if (fromPool != null) {
            List<RewardEntry> pool = config.rewardPool(fromPool);
            if (pool.isEmpty()) {
                if (warnedEmptyPools.add(fromPool)) {
                    plugin.getLogger().warning("Handed nothing to " + player.getUniqueId()
                        + ": " + poolPaths(Set.of(fromPool)) + " has no usable entry, so the daily reward"
                        + " stays unclaimed.");
                }
                player.sendMessage(messages.get("reward-unconfigured"));
                return false;
            }
            reward = draw(runnableEntries(pool, player.getName()));
            if (reward == null) {
                plugin.getLogger().warning("No daily reward command could be run for '"
                    + Utils.safeForLog(player.getName()) + "': the name cannot be safely pasted into a console"
                    + " command. Use %uuid%-based reward commands to support Bedrock/unsafe names.");
                player.sendMessage(messages.get("reward-failed"));
                return false;
            }
        }

        boolean resolved = !reward.commands().isEmpty();
        for (RewardEntry.Item item : reward.items()) {
            try {
                resolved |= resolvable.test(item.path());
            } catch (Throwable t) {
            }
        }
        if (!resolved) {
            for (RewardEntry.Item item : reward.items()) {
                if (reportedItemPaths.add(item.path())) {
                    plugin.getLogger().warning("Daily reward item '" + Utils.safeForLog(item.path())
                        + "' could not be resolved for " + player.getUniqueId() + " - nothing was handed"
                        + " over; it is retried on the next /activity open.");
                }
            }
            player.sendMessage(messages.get("reward-failed"));
            return false;
        }

        if (storeNeverLoaded("Refusing every daily reward")) {
            player.sendMessage(messages.get("reward-failed"));
            return false;
        }

        data.setDailyRewardClaimed(true);
        store.markDirty();
        if (!store.saveNow()) {
            data.setDailyRewardClaimed(false);
            store.markDirty();
            plugin.getLogger().severe("Handed nothing to " + player.getUniqueId() + ": the daily reward could"
                + " not be saved as claimed before it was paid, so nothing was handed over.");
            player.sendMessage(messages.get("reward-failed"));
            return false;
        }

        int multiplier = fromPool != null ? 1 : config.rewardMultiplier();
        RewardEntry paid = fromPool != null ? reward : withPaidAmount(reward, multiplier);
        if (!pay.test(paid, multiplier)) {
            data.setDailyRewardClaimed(false);
            store.markDirty();
            store.saveSoon();
            plugin.getLogger().warning("Daily reward for " + player.getUniqueId() + " handed nothing over:"
                + " it is unclaimed again in memory, and on disk once the queued save lands.");
            player.sendMessage(messages.get("reward-failed"));
            return false;
        }

        player.sendMessage(messages.get("daily-reward-claimed", "%reward%", Utils.colorize(paid.display())));
        playSound(player, config.barCompleteSound());
        return true;
    }

    static RewardEntry dailyRewardFor(Map<String, RewardEntry> groups, Permissible player) {
        for (Map.Entry<String, RewardEntry> group : groups.entrySet()) {
            String node = "group." + group.getKey();
            if (player.isPermissionSet(node) && player.hasPermission(node)) {
                return group.getValue();
            }
        }
        return null;
    }

    private static RewardEntry draw(List<RewardEntry> pool) {
        int total = RewardEntry.totalWeight(pool);
        if (total <= 0) {
            return null;
        }
        return RewardEntry.pick(pool, ThreadLocalRandom.current().nextInt(total));
    }

    static int rollbackClaimedPoints(int claimedBefore, int paid, List<Integer> due) {
        int cap = Math.min(paid, due.size());
        return cap <= 0 ? claimedBefore : Collections.max(due.subList(0, cap));
    }

    static List<RewardEntry> runnableEntries(List<RewardEntry> pool, String name) {
        List<RewardEntry> runnable = new ArrayList<>();
        for (RewardEntry entry : pool) {
            if (!entry.items().isEmpty()) {
                runnable.add(entry);
                continue;
            }
            for (String command : entry.commands()) {
                if (canRunCommand(command, name)) {
                    runnable.add(entry);
                    break;
                }
            }
        }
        return runnable;
    }

    private boolean dispatchRewards(Player player, RewardEntry entry, String at, int multiplier) {
        return dispatchRewards(
            () -> giveItems(player, entry, multiplier, at,
                this::resolveRewardItem, plugin.getLogger()),
            () -> dispatchCommands(player, entry.commands(), "reward", plugin.getLogger(), CONSOLE));
    }

    static boolean dispatchRewards(BooleanSupplier items, BooleanSupplier commands) {
        boolean gaveItems = items.getAsBoolean();
        boolean ranCommands = commands.getAsBoolean();
        return gaveItems || ranCommands;
    }

    private ItemStack resolveRewardItem(String path) {
        return resolveRewardItem(path, config.itemPathsUsable(), config.itemsAdderUsable(),
            p -> TLibsItems.item(p), id -> ItemsAdderItems.item(id));
    }

    static ItemStack resolveRewardItem(String path, boolean tlibs, boolean ia,
                                       Function<String, ItemStack> tlibsItems,
                                       Function<String, ItemStack> iaItems) {
        if (ItemPath.isPluginPath(path)) {
            return tlibs ? tlibsItems.apply(path) : null;
        }
        if (ItemPath.isItemsAdderPath(path)) {
            String id = ItemPath.itemsAdderId(path);
            return id != null && ia ? iaItems.apply(id) : null;
        }
        Material material = ItemPath.material(path);
        return material == null ? null : new ItemStack(material);
    }

    static boolean usable(ItemStack stack) {
        return stack != null && stack.getType() != Material.AIR && isItem(stack);
    }

    static boolean isItem(ItemStack stack) {
        try {
            return stack.getType().isItem();
        } catch (Throwable t) {
            return true;
        }
    }

    static boolean giveItems(Player player, RewardEntry entry, int multiplier, String at,
                             Function<String, ItemStack> resolver, Logger logger) {
        boolean gaveAny = false;
        boolean insertedAny = false;
        boolean missedAny = false;
        for (RewardEntry.Item item : entry.items()) {
            int total = item.amount() * multiplier;
            try {
                ItemStack stack = resolver.apply(item.path());
                if (!usable(stack)) {
                    missedAny = true;
                    if (reportedItemPaths.add(item.path())) {
                        logger.warning("Reward item '" + Utils.safeForLog(item.path()) + "' (x" + total
                            + ", " + at + ") could not be resolved for "
                            + player.getUniqueId() + " - nothing was handed over for it.");
                    }
                    continue;
                }
                int max = Math.max(1, stack.getMaxStackSize());
                for (int left = total; left > 0; left -= max) {
                    ItemStack chunk = stack.clone();
                    chunk.setAmount(Math.min(left, max));
                    gaveAny = true;
                    Map<Integer, ItemStack> leftovers = player.getInventory().addItem(chunk);
                    insertedAny = true;
                    for (ItemStack leftover : leftovers.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover, drop -> {
                            drop.setOwner(player.getUniqueId());
                            drop.setThrower(player.getUniqueId());
                        });
                    }
                }
            } catch (Throwable t) {
                missedAny = true;
                logger.severe("Reward item '" + Utils.safeForLog(item.path()) + "' (x" + total
                    + ", " + at + ") threw for " + player.getUniqueId() + ": " + t);
            }
        }

        if (insertedAny && missedAny) {
            logger.warning("Only part of reward '" + Utils.safeForLog(entry.display()) + "' reached "
                + player.getUniqueId() + " at " + at + " - it stays claimed,"
                + " so hand the rest over by hand.");
        }
        if (gaveAny) {
            try {
                player.updateInventory();
            } catch (Throwable t) {
                logger.warning("Could not resync the inventory of " + player.getUniqueId()
                    + " after reward '" + Utils.safeForLog(entry.display()) + "' at " + at
                    + ": " + t);
            }
        }
        return gaveAny;
    }

    private static final Predicate<String> CONSOLE =
        command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

    static boolean dispatchCommands(Player player, List<String> commands, String what, Logger logger,
                                    Predicate<String> console) {
        String name = player.getName();
        String uuid = player.getUniqueId().toString();
        boolean ranAny = false;

        for (String command : commands) {
            if (!canRunCommand(command, name)) {
                logger.warning("Skipping " + what + " command '" + Utils.safeForLog(String.valueOf(command))
                    + "' for '" + Utils.safeForLog(name) + "': the name is not one that can be safely pasted"
                    + " into a console command.");
                continue;
            }

            try {
                if (!console.test(command.replace("%player%", name).replace("%uuid%", uuid))
                    && reportOnce(what, command)) {
                    logger.warning("The " + what + " command '" + Utils.safeForLog(String.valueOf(command))
                        + "' was refused by the console - it is probably unknown on this server. Not"
                        + " reported again until /activity reload.");
                }
                ranAny = true;
            } catch (Throwable t) {
                if (reportOnce(what + "-threw", command)) {
                    logger.severe("A " + what + " command '" + Utils.safeForLog(String.valueOf(command))
                        + "' threw for " + Utils.safeForLog(name) + ": " + t + ". Not reported again until"
                        + " /activity reload.");
                }
            }
        }

        return ranAny;
    }

    private static boolean reportOnce(String what, String command) {
        return reportedBrokenCommands.add(what + "|" + command);
    }

    public boolean runClickCommands(Player player, int slot) {
        PlayerData data = tasks(player.getUniqueId());
        List<String> drawn = data.tasks();
        if (slot < 0 || slot >= drawn.size()) {
            return false;
        }

        String id = drawn.get(slot);
        ActivityDef def = config.activity(id);
        if (def == null || !data.isRevealed(id)) {
            return false;
        }

        List<String> commands = runnableClickCommands(def, player.getName(), config.clickCommandsPerClick(),
            plugin.getLogger());
        if (commands.isEmpty()) {
            return false;
        }

        long now = System.nanoTime();
        if (!clickBudget(now, config.clickCommandsPerSecond(), false)) {
            return false;
        }
        if (!clickCooldownPassed(player.getUniqueId(), now,
            config.clickCommandCooldownMillis() * 1_000_000L)) {
            return false;
        }
        clickBudget(now, config.clickCommandsPerSecond(), commands.size(), true);

        return schedule(() -> runClickCommandsNow(player, commands, id, CONSOLE));
    }

    void runClickCommandsNow(Player player, List<String> commands, String id, Predicate<String> console) {
        InventoryView open = player.getOpenInventory();
        if (open != null && open.getTopInventory().getHolder() instanceof ActivityGui.Marker) {
            player.closeInventory();
        }

        Logger logger = plugin.getLogger();
        String name = player.getName();
        UUID uuid = player.getUniqueId();

        dispatchCommands(player, commands, "click", logger, command -> {
            String result = "threw";
            try {
                boolean ran = console.test(command);
                result = ran ? "done" : "refused";
                return ran;
            } finally {
                logger.info("ACTIVITY-AUDIT sender=" + Utils.quotedForLog(name) + " uuid=" + uuid
                    + " action=click-command activity=" + Utils.quotedForLog(id)
                    + " command=" + Utils.quotedForLog(command) + " result=" + result);
            }
        });
    }

    static List<String> runnableClickCommands(ActivityDef def, String name, int max, Logger logger) {
        if (def == null) {
            return List.of();
        }
        List<String> runnable = new ArrayList<>();
        for (String command : def.clickCommands()) {
            if (!canRunCommand(command, name)) {
                continue;
            }
            if (runnable.size() == max) {
                if (reportOnce("click-cap", def.id())) {
                    logger.warning("Activity '" + Utils.safeForLog(def.id()) + "' lists more than " + max
                        + " click-commands - only the first " + max + " are run (see"
                        + " click-commands-per-click). Not reported again until /activity reload.");
                }
                break;
            }
            runnable.add(command);
        }
        return runnable;
    }

    private boolean schedule(Runnable work) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, work);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not schedule an activity click's commands: " + t);
            return false;
        }
    }

    boolean clickBudget(long now, int perSecond, boolean take) {
        return clickBudget(now, perSecond, 1, take);
    }

    boolean clickBudget(long now, int perSecond, int cost, boolean take) {
        if (!clickTokensPrimed) {
            clickTokensPrimed = true;
            clickTokensAt = now;
            clickTokens = perSecond;
        }
        clickTokens = Math.min(perSecond, clickTokens + (now - clickTokensAt) / 1e9 * perSecond);
        clickTokensAt = now;
        if (clickTokens < 1) {
            return false;
        }
        if (take) {
            clickTokens -= cost;
        }
        return true;
    }

    boolean clickCooldownPassed(UUID uuid, long now, long intervalNanos) {
        Long last = clickCooldowns.get(uuid);
        if (last != null && now - last < intervalNanos) {
            return false;
        }
        clickCooldowns.put(uuid, now);
        return true;
    }

    public void forgetClickCooldown(UUID uuid) {
        clickCooldowns.remove(uuid);
    }

    int clickCooldownEntries() {
        return clickCooldowns.size();
    }

    public void onJoin(Player player) {
        PlayerData data = store.rolled(player.getUniqueId());
        if (data != null && data.claimable(config.milestones()) > 0) {
            player.sendMessage(config.messages().get("reward-ready"));
        }
    }

    public void reload() {
        config.load();
        warnedEmptyPools.clear();
        reportedItemPaths.clear();
        ItemsAdderItems.reset();
        reportedBrokenCommands.clear();
    }

    private void playSound(Player player, String soundKey) {
        if (soundKey != null) {
            player.playSound(player.getLocation(), soundKey, 1.0f, 1.0f);
        }
    }
}
