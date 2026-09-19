package tfmc.justin.activity.gui;

import com.google.common.collect.ImmutableMultimap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.hooks.ItemsAdderItems;
import tfmc.justin.activity.hooks.TLibsItems;
import tfmc.justin.activity.config.Messages;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.utils.Bar;
import tfmc.justin.activity.utils.ItemPath;
import tfmc.justin.activity.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// ====================================
// One single-chest view of the week: the two bars on the top row, today's
// seven drawn tasks in the middle row, filler everywhere else. The weekly bar
// is also the claim button: clicking it hands over whatever milestones are
// due. A task starts hidden and is revealed by clicking it; only a revealed
// task earns anything (the gate itself lives in ActivityManager). Also the
// click listener - a marker holder is the cheapest way to tell our inventory
// apart from every other one.
// ====================================
public class ActivityGui implements Listener {

    private static final int SIZE = 27;
    private static final int DAILY_BAR_SLOT = 3;
    // Between the two bars, and shown to everyone: a player without the
    // permission sees what the perk is rather than nothing at all
    private static final int REROLL_SLOT = 4;
    private static final int BAR_SLOT = 5;

    // The middle row, one slot per daily task - PlayerData.TASKS_PER_DAY of
    // them, floating inside a border of filler
    private static final int[] TASK_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    // Marketblock's demand bar length - the per-activity bars match it
    private static final int PROGRESS_BAR_LENGTH = 20;

    private final ActivityManager manager;

    public ActivityGui(ActivityManager manager) {
        this.manager = manager;
    }

    // ====================================
    // What makes an open window "the activity menu". Public because the
    // manager asks the same question a tick after a click, to decide whether
    // the window it is about to close is still ours - identity of the
    // InventoryView is not something Bukkit promises.
    // ====================================
    public static final class Marker implements InventoryHolder {

        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public Inventory build(Player player) {
        ActivityConfiguration config = manager.getConfiguration();
        Messages messages = config.messages();
        PlayerData data = manager.tasks(player.getUniqueId());

        Marker marker = new Marker();
        Inventory inventory = Bukkit.createInventory(marker, SIZE, Utils.colorize(config.guiTitle()));
        marker.inventory = inventory;

        inventory.setItem(DAILY_BAR_SLOT, dailyBarItem(config, messages, data));
        inventory.setItem(REROLL_SLOT, rerollItem(config, messages, data, player));
        inventory.setItem(BAR_SLOT, barItem(config, messages, data));

        for (int slot = 0; slot < TASK_SLOTS.length; slot++) {
            ItemStack task = taskItem(config, messages, data, slot);
            if (task != null) {
                inventory.setItem(TASK_SLOTS[slot], task);
            }
        }

        fillEmptySlots(inventory, messages);

        return inventory;
    }

    // ====================================
    // One task slot: the hidden dye until it is revealed, then the activity's
    // own item. null - which fillEmptySlots then turns into filler - covers
    // both a slot beyond what could be drawn and one holding an activity a
    // reload has since removed.
    // ====================================
    private ItemStack taskItem(ActivityConfiguration config, Messages messages, PlayerData data, int slot) {
        String id = taskIdAt(config, data, slot);
        if (id == null) {
            return null;
        }
        ActivityDef def = config.activity(id);
        if (!data.isRevealed(def.id())) {
            return item(Material.GRAY_DYE, messages.get("gui.hidden-task-name"), List.of());
        }
        return activityItem(config, messages, def, data);
    }

    // ====================================
    // Which activity a task slot shows, or null when it shows filler: a slot
    // beyond what could be drawn, and one holding an activity a reload has
    // since removed, both have nothing to paint. Package-private and pure so
    // the seven-slot paint decision can be tested without a live inventory.
    // ====================================
    static String taskIdAt(ActivityConfiguration config, PlayerData data, int slot) {
        List<String> tasks = data.tasks();
        if (slot < 0 || slot >= tasks.size()) {
            return null;
        }
        String id = tasks.get(slot);
        return config.activity(id) == null ? null : id;
    }

    // ====================================
    // Marketblock's filler: every slot the loops above left null gets a gray
    // pane instead, so the window reads as intentionally designed rather than
    // half-empty. Clicks on it are already cancelled by onClick.
    // ====================================
    private void fillEmptySlots(Inventory inventory, Messages messages) {
        ItemStack filler = filler(messages);
        for (int slot = 0; slot < SIZE; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    // ====================================
    // The weekly bar, which is also the claim button: the marked-up bar, where
    // the rewards sit, and then what the player can do about it right now.
    // ====================================
    private ItemStack barItem(ActivityConfiguration config, Messages messages, PlayerData data) {
        List<Integer> milestones = config.milestones();
        String bar = Bar.render(data.points(), config.barMax(), config.barLength(), milestones);
        int due = data.claimable(milestones);
        Integer next = nextMilestone(data, milestones);

        List<String> lore = new ArrayList<>();
        lore.add(Utils.colorize(bar));
        lore.add(" ");
        if (due > 0) {
            lore.add(messages.get("gui.reward-click", "%count%", due));
        } else if (next != null) {
            lore.add(messages.get("gui.bar-lore-next", "%points%", next));
        } else {
            lore.add(messages.get("gui.bar-lore-done"));
        }

        return item(Material.EXPERIENCE_BOTTLE,
            messages.get("gui.bar-name", "%points%", data.points(), "%max%", config.barMax()),
            lore);
    }

    // The lowest milestone still ahead of what has been paid for, or null once
    // every one of them has been claimed this week
    private static Integer nextMilestone(PlayerData data, List<Integer> milestones) {
        for (Integer milestone : milestones) {
            if (milestone > data.claimedPoints()) {
                return milestone;
            }
        }
        return null;
    }

    // ====================================
    // The reroll button. Everyone sees it; the lore is what differs, so a
    // player without the rank learns the perk exists instead of wondering
    // what the button is for.
    // ====================================
    private ItemStack rerollItem(ActivityConfiguration config, Messages messages, PlayerData data, Player player) {
        int perDay = config.rerollsPerDay();
        String lore;
        if (perDay <= 0) {
            lore = messages.get("gui.reroll-lore-disabled");
        } else if (!player.hasPermission("activity.reroll")) {
            lore = messages.get("gui.reroll-lore-locked");
        } else if (data.dailyPoints() > config.rerollMaxPoints()) {
            // Past the threshold the button refuses for the rest of the day,
            // so showing a remaining count would only be a lie
            lore = messages.get("gui.reroll-lore-too-late", "%points%", config.rerollMaxPoints());
        } else {
            lore = messages.get("gui.reroll-lore-left",
                "%left%", Math.max(0, perDay - data.rerolls()), "%max%", perDay);
        }

        return item(Material.NETHER_STAR, messages.get("gui.reroll-name"), List.of(lore));
    }

    private ItemStack dailyBarItem(ActivityConfiguration config, Messages messages, PlayerData data) {
        int dailyPoints = Math.min(data.dailyPoints(), config.dailyMax());
        String bar = Bar.render(dailyPoints, config.dailyMax(), config.barLength());

        return item(Material.EXPERIENCE_BOTTLE,
            messages.get("gui.daily-bar-name", "%points%", dailyPoints, "%max%", config.dailyMax()),
            List.of(Utils.colorize(bar)));
    }

    private ItemStack activityItem(ActivityConfiguration config, Messages messages, ActivityDef def, PlayerData data) {
        return item(iconStack(config, def.icon(), def.iconPath()), Utils.colorize(def.display()),
            activityLore(messages, def, data.count(def.id())));
    }

    // ====================================
    // A revealed task's lore: the operator's description first, then the
    // progress bar, then what the day has paid so far. The description is
    // free-form operator text with no label or placeholder of its own, so it
    // is emitted as configured rather than through a messages.yml key - the
    // same treatment def.display() gets, colour codes and all. Only reached
    // from activityItem, never from the hidden-task branch, so a description
    // cannot give away an unrevealed task. Package-private and pure so the
    // ordering can be pinned without a live Bukkit inventory.
    // ====================================
    static List<String> activityLore(Messages messages, ActivityDef def, int count) {
        List<String> lore = new ArrayList<>();
        for (String line : def.description()) {
            lore.add(Utils.colorize(line));
        }
        String progress = progressBar(def, count);
        if (progress != null) {
            lore.add(messages.get("gui.activity-lore-progress", "%bar%", Utils.colorize(progress)));
        }
        lore.add(def.dailyCap() > 0
            ? messages.get("gui.activity-lore-today-capped", "%today%", def.completions(count), "%cap%", def.dailyCap())
            : messages.get("gui.activity-lore-today", "%today%", def.completions(count)));
        return lore;
    }

    // ====================================
    // Progress toward the NEXT point, not the whole day: once the daily cap is
    // reached the bar is shown full rather than reset to empty. Below the cap
    // it is count % every out of every, so it fills and restarts once per
    // point earned. An uncapped 'every: 1' has no meaningful partial progress
    // (every count is already a whole point), so no bar is shown; capped at
    // every: 1 still shows full, same as any other capped activity.
    // Package-private and static so a test can check it without a live
    // Bukkit inventory.
    // ====================================
    static String progressBar(ActivityDef def, int count) {
        if (def.dailyCap() > 0 && def.worth(count) >= def.capPoints()) {
            return Bar.render(1, 1, PROGRESS_BAR_LENGTH);
        }
        if (def.every() > 1) {
            return Bar.render(count % def.every(), def.every(), PROGRESS_BAR_LENGTH);
        }
        return null;
    }

    // ====================================
    // An icon written as a TLibs item path is built by TLibs, so an MMOItems
    // icon keeps its model and texture; an ia.<namespace:id> one is built by
    // ItemsAdder the same way. The name and lore below are then set on it like
    // on any other icon. The backing plugin missing, or the path no longer
    // resolving, falls back to the Material - which is PAPER behind any path.
    // ====================================
    private ItemStack iconStack(ActivityConfiguration config, Material icon, String iconPath) {
        ItemStack fromPath = fromPath(config, iconPath);
        if (fromPath != null && !fromPath.getType().isAir()) {
            return fromPath;
        }
        return new ItemStack(icon);
    }

    private ItemStack fromPath(ActivityConfiguration config, String iconPath) {
        // Lambda, not a TLibsItems::item method reference: a reference would
        // resolve the TLibsItems class eagerly, ahead of the itemPathsUsable()
        // gate, and reintroduce the class-init crash on a server without the
        // TLibs trio (see ActivityManager's resolveRewardItem for the same rule).
        return fromPath(iconPath, config.itemPathsUsable(), config.itemsAdderUsable(),
            path -> TLibsItems.item(path), id -> ItemsAdderItems.item(id));
    }

    // Each path form on its own gate: a server without ItemsAdder must still
    // build its m. icons, and one without the TLibs trio its ia. ones. Neither
    // hook throws - both answer null for anything they cannot build.
    //
    // Static and with both hooks handed in, so all four gate combinations can
    // be pinned headless - the two gates being independent is the whole point
    // of the feature, and an '&&' slipped in here would otherwise go unnoticed.
    static ItemStack fromPath(String iconPath, boolean tlibs, boolean ia,
                              Function<String, ItemStack> tlibsItems,
                              Function<String, ItemStack> iaItems) {
        if (iconPath == null) {
            return null;
        }
        if (ItemPath.isItemsAdderPath(iconPath)) {
            String id = ItemPath.itemsAdderId(iconPath);
            return id != null && ia ? iaItems.apply(id) : null;
        }
        return tlibs ? tlibsItems.apply(iconPath) : null;
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        return item(new ItemStack(material), name, lore);
    }

    // ====================================
    // Every icon this GUI shows passes through here, so the enchant glint
    // (kept) never drags an "Efficiency II" tooltip line along with it - nor,
    // for an enchanted book or a custom item carrying stored enchantments
    // rather than applied ones, the same line under its "Stored Enchantments"
    // heading - and a custom item's "When in Main Hand: ..." attribute lines
    // stay off too. HIDE_ATTRIBUTES alone is not enough on this API version: a
    // vanilla tool with no explicit attribute modifiers still shows its
    // built-in attack-damage lines, because those come from the item's
    // default component rather than an explicit modifier list a flag can
    // hide. Setting an empty modifier map overrides that default with "no
    // modifiers at all", which starves HIDE_ATTRIBUTES of anything to hide in
    // the first place.
    // ====================================
    private ItemStack item(ItemStack stack, String name, List<String> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_STORED_ENCHANTS);
            meta.setAttributeModifiers(ImmutableMultimap.of());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // ====================================
    // Nothing in the view is takeable, so every click in it is cancelled -
    // including shift-clicks from the player's own inventory, which is why the
    // top inventory is what gets checked rather than the clicked slot.
    // ====================================
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Marker)) {
            return;
        }
        event.setCancelled(true);

        // Bottom-inventory clicks (the player's own inventory) and clicks
        // outside any inventory (raw slot -999) are cancelled above but must
        // not reach the controls below, which only make sense for a slot in
        // our own top inventory
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // The permission is checked on /activity, but an inventory can outlive
        // the permission that opened it - a revoked player must not still be
        // able to claim a reward or reveal a task from a window they left open
        if (!player.hasPermission("activity.use")) {
            return;
        }

        if (event.getRawSlot() == BAR_SLOT) {
            clickSound(player);
            claim(player, event);
            return;
        }

        if (event.getRawSlot() == REROLL_SLOT) {
            clickSound(player);
            reroll(player, event);
            return;
        }

        int task = taskSlot(event.getRawSlot());
        if (task < 0) {
            return;
        }

        ActivityManager.Reveal reveal = revealTask(player, task);
        if (reveal.revealedId() != null) {
            clickSound(player);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        } else if (!reveal.drawChanged()) {
            // ====================================
            // Nothing to reveal and nothing to repaint: the task was already
            // revealed. That is the click that runs the activity's
            // 'click-commands', if it configures any - so the first click
            // reveals and every later one runs them. The manager owns the
            // revealed check, the dispatch, the two rate limits and the
            // close; a click it refused (nothing to run, or one of the
            // limits) stays as silent as it has always been, and only a click
            // that really dispatched something makes a sound.
            // ====================================
            if (manager.runClickCommands(player, task)) {
                clickSound(player);
            }
            return;
        }

        // ====================================
        // Slots on the open view, never a reopen: reopening the inventory from
        // inside a click event is discouraged by Bukkit and would drop
        // whatever is on the player's cursor. Just the clicked slot normally -
        // but a draw that was compacted or topped up mid-click (a reload
        // dropped an activity, the day rolled over, /activity reset ran) has
        // moved every task after the change, so then all seven are repainted.
        // ====================================
        ActivityConfiguration config = manager.getConfiguration();
        PlayerData data = manager.tasks(player.getUniqueId());
        Inventory top = event.getView().getTopInventory();
        if (reveal.drawChanged()) {
            // The draw only moves at a rollover or a reload, both of which
            // wipe or rescale what the two bars show - repainting the tasks
            // alone would leave a fresh draw beside a stale "10/10" daily bar
            // and a claim button that no longer does anything
            repaintAll(top, config, data, player);
        } else {
            top.setItem(event.getRawSlot(), taskOrFiller(config, data, task));
        }
    }

    // ====================================
    // The reveal half of a task click, apart from the event and the sounds
    // (Sound needs a running server) so it can be driven headless: reveals
    // the slot and, when that revealed something, pays the daily reward if
    // this was the last of today's draw.
    // ====================================
    ActivityManager.Reveal revealTask(Player player, int task) {
        ActivityManager.Reveal reveal = manager.reveal(player.getUniqueId(), task);
        if (reveal.revealedId() != null) {
            manager.claimDailyReward(player);
        }
        return reveal;
    }

    // Every slot build() paints, on the open view: used wherever a click
    // changed the draw and the bars at once
    private void repaintAll(Inventory top, ActivityConfiguration config, PlayerData data, Player player) {
        Messages messages = config.messages();
        top.setItem(DAILY_BAR_SLOT, dailyBarItem(config, messages, data));
        top.setItem(REROLL_SLOT, rerollItem(config, messages, data, player));
        top.setItem(BAR_SLOT, barItem(config, messages, data));
        for (int slot = 0; slot < TASK_SLOTS.length; slot++) {
            top.setItem(TASK_SLOTS[slot], taskOrFiller(config, data, slot));
        }
    }

    // ====================================
    // The reroll click. The permission is checked here rather than in the
    // manager: a player without it is not a failed reroll but a sales pitch,
    // and nothing about their data is read or written.
    //
    // A feature the server has switched off is not a sales pitch either, so
    // that is checked first - the same order rerollItem() paints the lore in.
    // ====================================
    private void reroll(Player player, InventoryClickEvent event) {
        ActivityConfiguration config = manager.getConfiguration();
        Messages messages = config.messages();

        if (config.rerollsPerDay() <= 0) {
            player.sendMessage(messages.get("reroll-disabled"));
            return;
        }

        if (!player.hasPermission("activity.reroll")) {
            player.sendMessage(messages.get("reroll-locked"));
            return;
        }

        switch (manager.reroll(player.getUniqueId())) {
            // Unreachable: the rerollsPerDay() <= 0 guard above already
            // returns on this exact condition. Kept so the switch stays
            // exhaustive over Rerolled without a default branch.
            case DISABLED -> player.sendMessage(messages.get("reroll-disabled"));
            case FAILED -> player.sendMessage(messages.get("reroll-failed"));
            case NONE_LEFT -> player.sendMessage(messages.get("reroll-none-left"));
            case TOO_LATE -> player.sendMessage(
                messages.get("reroll-too-late", "%points%", config.rerollMaxPoints()));
            case DONE -> {
                player.sendMessage(messages.get("reroll-done"));
                repaintAll(event.getView().getTopInventory(), config,
                    manager.getStore().get(player.getUniqueId()), player);
            }
        }
    }

    // A repaint must leave nothing stale behind, so a slot with no task in it
    // gets the same filler build() would have put there
    private ItemStack taskOrFiller(ActivityConfiguration config, PlayerData data, int slot) {
        Messages messages = config.messages();
        ItemStack task = taskItem(config, messages, data, slot);
        return task != null ? task : filler(messages);
    }

    private ItemStack filler(Messages messages) {
        return item(Material.GRAY_STAINED_GLASS_PANE, messages.get("gui.filler-name"), List.of());
    }

    // The task index this raw slot holds, or -1 for any other slot.
    // Package-private so the click routing can be tested without a server.
    static int taskSlot(int rawSlot) {
        for (int slot = 0; slot < TASK_SLOTS.length; slot++) {
            if (TASK_SLOTS[slot] == rawSlot) {
                return slot;
            }
        }
        return -1;
    }

    // ====================================
    // The per-player click-commands cooldown would otherwise keep an entry
    // for every player who ever clicked a task, for the whole uptime. This
    // listener is registered unconditionally (see ActivityPlugin), so there
    // is no new registration to add for it.
    // ====================================
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.forgetClickCooldown(event.getPlayer().getUniqueId());
    }

    // Marketblock's menu click; a successful claim still plays its own sound
    private static void clickSound(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
    }

    private void claim(Player player, InventoryClickEvent event) {
        ActivityConfiguration config = manager.getConfiguration();
        // A claim of 0 is either "nothing was due" or a refusal, and claim()
        // has already told the player about every refusal. Nothing was due
        // needs no line either - the bar's own lore says when the next reward
        // lands.
        if (manager.claim(player) == 0) {
            return;
        }
        event.getView().getTopInventory().setItem(BAR_SLOT,
            barItem(config, config.messages(), manager.getStore().get(player.getUniqueId())));
    }

    // ====================================
    // A drag is not a click, so it slips past onClick entirely. Raw slots
    // below SIZE are the top inventory - a drag that only touches the
    // player's own inventory is left alone.
    // ====================================
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Marker)) {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < SIZE) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
