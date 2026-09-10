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
import tfmc.justin.activity.config.Messages;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.utils.Bar;
import tfmc.justin.activity.utils.Utils;

import java.util.ArrayList;
import java.util.List;

// ====================================
// Chest view of the week: the bar on top, one item per activity, the reward
// chest at the bottom - clicking it claims whatever milestones are due. Also
// the click listener - a marker holder is the cheapest way to tell our
// inventory apart from every other one.
// ====================================
public class ActivityGui implements Listener {

    private static final int SIZE = 27;
    private static final int BAR_SLOT = 4;
    private static final int FIRST_ACTIVITY_SLOT = 10;
    private static final int REWARD_SLOT = 22;
    private static final int ACTIVITY_SLOTS = REWARD_SLOT - FIRST_ACTIVITY_SLOT;

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
        if (defs.size() > ACTIVITY_SLOTS && !warnedTooManyActivities) {
            warnedTooManyActivities = true;
            Bukkit.getLogger().warning("[activity] config.yml defines " + defs.size()
                + " activities but the GUI has room for " + ACTIVITY_SLOTS + " - the rest are not shown.");
        }

        int slot = FIRST_ACTIVITY_SLOT;
        for (ActivityDef def : defs) {
            if (slot >= REWARD_SLOT) {
                break;
            }
            inventory.setItem(slot, activityItem(messages, def, data));
            slot++;
        }

        inventory.setItem(REWARD_SLOT, rewardItem(config, messages, data));

        return inventory;
    }

    private ItemStack barItem(ActivityConfiguration config, Messages messages, PlayerData data) {
        String bar = Bar.render(data.points(), config.barMax(), config.barLength(), config.barFilledChar(),
            config.barEmptyChar(), config.barFilledColor(), config.barEmptyColor());

        return item(Material.EXPERIENCE_BOTTLE,
            messages.get("gui.bar-name", "%points%", data.points(), "%max%", config.barMax()),
            List.of(Utils.colorize(bar)));
    }

    private ItemStack activityItem(Messages messages, ActivityDef def, PlayerData data) {
        int today = def.worth(data.count(def.id()));

        List<String> lore = new ArrayList<>();
        lore.add(messages.get("gui.activity-lore-points", "%points%", def.points(), "%every%", def.every()));
        lore.add(def.dailyCap() > 0
            ? messages.get("gui.activity-lore-today-capped", "%today%", today, "%cap%", def.dailyCap())
            : messages.get("gui.activity-lore-today", "%today%", today));

        return item(def.icon(), Utils.colorize(def.display()), lore);
    }

    private ItemStack rewardItem(ActivityConfiguration config, Messages messages, PlayerData data) {
        int due = data.claimable(config.rewardEvery());

        List<String> lore = new ArrayList<>();
        lore.add(messages.get("gui.reward-lore-header", "%every%", config.rewardEvery()));
        lore.addAll(Messages.colorize(config.rewardDisplay()));
        lore.add(messages.get("gui.reward-lore-claimed", "%claimed%", data.claimedPoints() / config.rewardEvery(),
            "%total%", config.barMax() / config.rewardEvery()));
        lore.add(due > 0
            ? messages.get("gui.reward-click", "%count%", due)
            : messages.get("gui.reward-nothing"));

        return item(config.rewardMaterial(), messages.get("gui.reward-name"), lore);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
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
