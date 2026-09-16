package tfmc.justin.activity.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.hooks.TLibsItems;
import tfmc.justin.activity.config.Messages;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.GroupDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.utils.Bar;
import tfmc.justin.activity.utils.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// ====================================
// Two double-chest views of the week. The main one - what /activity opens -
// holds the bar on top, one item per group in the grid, and the reward chest
// at the bottom, clicking which claims whatever milestones are due. Clicking
// a group opens the second view: the same bar, that group's activities in the
// same grid, and a Back button. Also the click listener - a marker holder is
// the cheapest way to tell our inventories apart from every other one, and it
// carries which of the two views was clicked.
// ====================================
public class ActivityGui implements Listener {

    private static final int SIZE = 54;
    private static final int DAILY_BAR_SLOT = 3;
    private static final int BAR_SLOT = 5;
    private static final int REWARD_SLOT = 49;
    private static final int BACK_SLOT = 53;

    // ====================================
    // Marketblock's grid: rows 2-5, columns 2-8, so the contents float inside
    // a border of filler. Both views draw into it - groups on the main one,
    // that group's activities on the other. Sized off GroupDef.MAX_GROUPS/
    // MAX_ACTIVITIES rather than its own constants, so ActivityConfiguration
    // can validate a config against the same numbers without importing this
    // package.
    // ====================================
    private static final int[] GRID = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    // Marketblock's demand bar length - the per-activity bars match it
    private static final int PROGRESS_BAR_LENGTH = 20;

    // The group id a group item carries, read back when it is clicked
    private static final NamespacedKey GROUP_KEY = new NamespacedKey("activity", "activity_group");

    private final ActivityManager manager;

    public ActivityGui(ActivityManager manager) {
        this.manager = manager;
    }

    private enum View { MAIN, GROUP }

    private static final class Marker implements InventoryHolder {

        private final View view;

        private Inventory inventory;

        private Marker(View view) {
            this.view = view;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public Inventory build(Player player) {
        ActivityConfiguration config = manager.getConfiguration();
        Messages messages = config.messages();
        PlayerData data = manager.getStore().get(player.getUniqueId());

        Inventory inventory = window(View.MAIN, Utils.colorize(config.guiTitle()));
        inventory.setItem(DAILY_BAR_SLOT, dailyBarItem(config, messages, data));
        inventory.setItem(BAR_SLOT, barItem(config, messages, data));

        // ====================================
        // Groups in config order. Anything past the grid's capacity has no
        // slot and is left out; load() has already said so.
        // ====================================
        int slot = 0;
        for (GroupDef group : config.groups().values()) {
            if (slot >= GRID.length) {
                break;
            }
            inventory.setItem(GRID[slot], groupItem(config, messages, group));
            slot++;
        }

        inventory.setItem(REWARD_SLOT, rewardItem(config, messages, data));

        fillEmptySlots(inventory, messages);

        return inventory;
    }

    // ====================================
    // The second level: one group's activities, in config order, and nothing
    // else live but the Back button. No reward chest here - claiming stays on
    // the main view, which Back is the only way out to.
    // ====================================
    public Inventory buildGroup(Player player, GroupDef group) {
        ActivityConfiguration config = manager.getConfiguration();
        Messages messages = config.messages();
        PlayerData data = manager.getStore().get(player.getUniqueId());

        Inventory inventory = window(View.GROUP, Utils.colorize(group.display()));
        inventory.setItem(DAILY_BAR_SLOT, dailyBarItem(config, messages, data));
        inventory.setItem(BAR_SLOT, barItem(config, messages, data));

        List<ActivityDef> page = pageOf(config.activities(), group.id());
        for (int slot = 0; slot < page.size(); slot++) {
            inventory.setItem(GRID[slot], activityItem(config, messages, page.get(slot), data));
        }

        inventory.setItem(BACK_SLOT, item(Material.BARRIER, messages.get("gui.back-name"), List.of()));

        fillEmptySlots(inventory, messages);

        return inventory;
    }

    private Inventory window(View view, String title) {
        Marker marker = new Marker(view);
        Inventory inventory = Bukkit.createInventory(marker, SIZE, title);
        marker.inventory = inventory;
        return inventory;
    }

    // ====================================
    // The activities of one group, in config order, capped at the grid's
    // capacity. Package-private and static so a test can check the ordering
    // and cap without building a whole GUI.
    // ====================================
    static List<ActivityDef> pageOf(Collection<ActivityDef> all, String groupId) {
        List<ActivityDef> page = new ArrayList<>();
        for (ActivityDef def : all) {
            if (page.size() >= GRID.length) {
                break;
            }
            if (groupId.equals(def.group())) {
                page.add(def);
            }
        }
        return page;
    }

    // ====================================
    // Marketblock's filler: every slot the loops above left null gets a gray
    // pane instead, so the window reads as intentionally designed rather than
    // half-empty. Clicks on it are already cancelled by onClick.
    // ====================================
    private void fillEmptySlots(Inventory inventory, Messages messages) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, messages.get("gui.filler-name"), List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    private ItemStack barItem(ActivityConfiguration config, Messages messages, PlayerData data) {
        String bar = Bar.render(data.points(), config.barMax(), config.barLength());

        return item(Material.EXPERIENCE_BOTTLE,
            messages.get("gui.bar-name", "%points%", data.points(), "%max%", config.barMax()),
            List.of(Utils.colorize(bar)));
    }

    private ItemStack dailyBarItem(ActivityConfiguration config, Messages messages, PlayerData data) {
        String bar = Bar.render(data.dailyPoints(), config.dailyMax(), config.barLength());

        return item(Material.CLOCK,
            messages.get("gui.daily-bar-name", "%points%", data.dailyPoints(), "%max%", config.dailyMax()),
            List.of(Utils.colorize(bar)));
    }

    // ====================================
    // The main view's tile: an icon, a name and an invitation to click it. The
    // group id rides along in the item's data container, the way Marketblock
    // carries a category id, so the click handler does not have to match on a
    // display name or a slot.
    // ====================================
    private ItemStack groupItem(ActivityConfiguration config, Messages messages, GroupDef group) {
        ItemStack stack = item(iconStack(config, group.icon(), group.iconPath()), Utils.colorize(group.display()),
            List.of(messages.get("gui.group-lore-click")));

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(GROUP_KEY, PersistentDataType.STRING, group.id());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack activityItem(ActivityConfiguration config, Messages messages, ActivityDef def, PlayerData data) {
        int count = data.count(def.id());
        int today = def.worth(count);

        List<String> lore = new ArrayList<>();
        // Three cases, since a plain count % every reads wrong at the edges:
        // capped activities fill across the whole daily budget (every * cap)
        // and stay full once capped; uncapped ones cycle every 'every' count;
        // an uncapped 'every: 1' has no meaningful cycle, so no bar is shown.
        // 'every' and 'daily-cap' are small config ints (clamped >= 1 / >= 0
        // in ActivityConfiguration), so every * cap cannot overflow an int.
        String progress;
        if (def.dailyCap() > 0) {
            int budget = def.every() * def.dailyCap();
            progress = Bar.render(Math.min(count, budget), budget, PROGRESS_BAR_LENGTH);
        } else if (def.every() > 1) {
            progress = Bar.render(count % def.every(), def.every(), PROGRESS_BAR_LENGTH);
        } else {
            progress = null;
        }
        if (progress != null) {
            lore.add(messages.get("gui.activity-lore-progress", "%bar%", Utils.colorize(progress)));
        }
        lore.add(def.dailyCap() > 0
            ? messages.get("gui.activity-lore-today-capped", "%today%", today, "%cap%", def.dailyCap())
            : messages.get("gui.activity-lore-today", "%today%", today));

        return item(iconStack(config, def.icon(), def.iconPath()), Utils.colorize(def.display()), lore);
    }

    private ItemStack rewardItem(ActivityConfiguration config, Messages messages, PlayerData data) {
        int due = data.claimable(config.rewardEvery());

        List<String> lore = new ArrayList<>();
        lore.add(messages.get("gui.reward-lore-header", "%every%", config.rewardEvery()));
        lore.addAll(Messages.colorize(config.rewardDisplay()));
        lore.add(messages.get("gui.reward-lore-claimed", "%claimed%", data.claimedPoints() / config.rewardEvery(),
            "%total%", config.barMax() / config.rewardEvery()));
        lore.add(" ");
        lore.add(due > 0
            ? messages.get("gui.reward-click", "%count%", due)
            : messages.get("gui.reward-nothing"));

        return item(config.rewardMaterial(), messages.get("gui.reward-name"), lore);
    }

    // ====================================
    // An icon written as a TLibs item path is built by TLibs, so an MMOItems
    // icon keeps its model and texture; the name and lore below are then set
    // on it like on any other icon. TLibs missing, or the path no longer
    // resolving, falls back to the Material. Activity icons and group labels
    // are built the same way, so both hand their pair in here.
    // ====================================
    private ItemStack iconStack(ActivityConfiguration config, Material icon, String iconPath) {
        if (iconPath != null && config.itemPathsUsable()) {
            ItemStack fromPath = TLibsItems.item(iconPath);
            if (fromPath != null && !fromPath.getType().isAir()) {
                return fromPath;
            }
        }
        return new ItemStack(icon);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        return item(new ItemStack(material), name, lore);
    }

    private ItemStack item(ItemStack stack, String name, List<String> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // ====================================
    // Nothing in either view is takeable, so every click in them is cancelled
    // - including shift-clicks from the player's own inventory, which is why
    // the top inventory is what gets checked rather than the clicked slot.
    // Which view was clicked comes off the holder, the way Marketblock reads
    // it off MBHolder, and decides what the live controls are.
    // ====================================
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Marker marker)) {
            return;
        }
        event.setCancelled(true);

        // Bottom-inventory clicks (the player's own inventory) and clicks
        // outside any inventory (raw slot -999) are cancelled above but must
        // not reach mainClick/groupClick, which only make sense for a slot in
        // our own top inventory
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        switch (marker.view) {
            case MAIN -> mainClick(player, event);
            case GROUP -> groupClick(player, event);
        }
    }

    // ====================================
    // The main view's two live controls: the reward chest claims, and a group
    // item opens that group. The item is what is asked for the group id - a
    // config reloaded between opening and clicking can leave a tile naming a
    // group that is gone, and that click is simply ignored.
    // ====================================
    private void mainClick(Player player, InventoryClickEvent event) {
        // The permission is checked on /activity, but an inventory can outlive
        // the permission that opened it - a revoked player must not still be
        // able to claim a reward or navigate into a group from a window they
        // left open
        if (!player.hasPermission("activity.use")) {
            return;
        }

        if (event.getRawSlot() != REWARD_SLOT) {
            GroupDef group = clickedGroup(event.getCurrentItem());
            if (group != null) {
                player.openInventory(buildGroup(player, group));
            }
            return;
        }

        claim(player, event);
    }

    // Back is the only thing that does anything here
    private void groupClick(Player player, InventoryClickEvent event) {
        if (event.getRawSlot() == BACK_SLOT && player.hasPermission("activity.use")) {
            player.openInventory(build(player));
        }
    }

    private GroupDef clickedGroup(ItemStack clicked) {
        if (clicked == null) {
            return null;
        }
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) {
            return null;
        }
        String id = meta.getPersistentDataContainer().get(GROUP_KEY, PersistentDataType.STRING);
        return id == null ? null : manager.getConfiguration().groups().get(id);
    }

    private void claim(Player player, InventoryClickEvent event) {
        ActivityConfiguration config = manager.getConfiguration();
        if (manager.claim(player) == 0) {
            // ====================================
            // A claim of 0 is either "nothing was due" or a refusal, and every
            // refusal has already told the player why - saying "nothing to
            // claim yet" on top of that contradicts it. So the only two lines
            // sent here are the ones claim() cannot send itself: the missing
            // reward commands, which it leaves silent on purpose, and the
            // genuinely empty claim, which still owes the player an answer.
            // ====================================
            // A store that never loaded refuses every claim and has already
            // said so - what is or is not configured is beside the point
            if (!manager.getStore().isLoaded()) {
                return;
            }

            if (config.rewardCommands().isEmpty()) {
                player.sendMessage(config.messages().get("reward-unconfigured"));
                return;
            }

            // peek, not get: deciding which line to send must not create an
            // entry or dirty the store
            PlayerData data = manager.getStore().peek(player.getUniqueId());
            if (data == null || data.claimable(config.rewardEvery()) == 0) {
                player.sendMessage(config.messages().get("reward-nothing"));
            }
            return;
        }
        event.getView().getTopInventory().setItem(REWARD_SLOT,
            rewardItem(config, config.messages(), manager.getStore().get(player.getUniqueId())));
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
