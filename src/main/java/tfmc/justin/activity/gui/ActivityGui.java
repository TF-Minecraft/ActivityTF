package tfmc.justin.activity.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.hooks.TLibsItems;
import tfmc.justin.activity.config.Messages;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.utils.Bar;
import tfmc.justin.activity.utils.Utils;

import java.util.ArrayList;
import java.util.List;

// ====================================
// Double-chest view of the week: the bar on top, one item per activity, the
// reward chest at the bottom - clicking it claims whatever milestones are due. Also
// the click listener - a marker holder is the cheapest way to tell our
// inventory apart from every other one.
// ====================================
public class ActivityGui implements Listener {

    private static final int SIZE = 54;
    private static final int BAR_SLOT = 4;
    private static final int REWARD_SLOT = 49;

    // Marketblock's grid: rows 2-5, columns 2-8 of a double chest
    private static final int[] ACTIVITY_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    // Marketblock's demand bar length - the per-activity bars match it
    private static final int PROGRESS_BAR_LENGTH = 20;

    private final ActivityManager manager;

    // The config outgrowing the window is a startup-time mistake, not a
    // per-open one - saying it every time anybody runs /activity is spam
    private boolean warnedTooManyActivities;

    public ActivityGui(ActivityManager manager) {
        this.manager = manager;
    }

    private static final class Marker implements InventoryHolder {

        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public Inventory build(Player player) {
        ActivityConfiguration config = manager.getConfiguration();
        Messages messages = config.messages();
        PlayerData data = manager.getStore().get(player.getUniqueId());

        Marker marker = new Marker();
        Inventory inventory = Bukkit.createInventory(marker, SIZE, Utils.colorize(config.guiTitle()));
        marker.inventory = inventory;

        inventory.setItem(BAR_SLOT, barItem(config, messages, data));

        List<ActivityDef> defs = config.activities();
        if (defs.size() > ACTIVITY_SLOTS.length && !warnedTooManyActivities) {
            warnedTooManyActivities = true;
            Bukkit.getLogger().warning("[activity] config.yml defines " + defs.size()
                + " activities but the GUI has room for " + ACTIVITY_SLOTS.length + " - the rest are not shown.");
        }

        for (int i = 0; i < ACTIVITY_SLOTS.length && i < defs.size(); i++) {
            inventory.setItem(ACTIVITY_SLOTS[i], activityItem(messages, defs.get(i), data));
        }

        inventory.setItem(REWARD_SLOT, rewardItem(config, messages, data));

        fillEmptySlots(inventory, messages);

        return inventory;
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

    private ItemStack activityItem(Messages messages, ActivityDef def, PlayerData data) {
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

        return item(iconStack(def), Utils.colorize(def.display()), lore);
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
    // resolving, falls back to the activity's Material.
    // ====================================
    private ItemStack iconStack(ActivityDef def) {
        if (def.iconPath() != null && Bukkit.getPluginManager().isPluginEnabled("TLibs")) {
            ItemStack fromPath = TLibsItems.item(def.iconPath());
            if (fromPath != null && !fromPath.getType().isAir()) {
                return fromPath;
            }
        }
        return new ItemStack(def.icon());
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
    // Nothing in this view is takeable, so every click in it is cancelled -
    // including shift-clicks from the player's own inventory, which is why
    // the top inventory is what gets checked rather than the clicked slot.
    // The reward chest is the one live control: it claims and redraws itself.
    // ====================================
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Marker)) {
            return;
        }
        event.setCancelled(true);

        if (event.getRawSlot() != REWARD_SLOT || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // The permission is checked on /activity, but an inventory can outlive
        // the permission that opened it - a revoked player must not still be
        // able to click a reward out of a window they left open
        if (!player.hasPermission("activity.use")) {
            return;
        }

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
