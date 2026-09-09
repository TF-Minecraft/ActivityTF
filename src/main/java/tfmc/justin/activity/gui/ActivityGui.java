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
// Read-only chest view of the week: the bar on top, one item per activity,
// the reward list at the bottom. Also the click listener - a marker holder
// is the cheapest way to tell our inventory apart from every other one.
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
        String bar = Bar.render(data.points(), config.barLength(), config.barFilledChar(), config.barEmptyChar(),
            config.barFilledColor(), config.barEmptyColor());

        return item(Material.EXPERIENCE_BOTTLE,
            messages.get("gui.bar-name", "%percent%", data.points()),
            List.of(Utils.colorize(bar)));
    }

    private ItemStack activityItem(Messages messages, ActivityDef def, PlayerData data) {
        int count = data.count(def.id());
        boolean done = count >= def.dailyGoal();

        List<String> lore = new ArrayList<>();
        lore.add(messages.get("gui.activity-lore-progress", "%count%", count, "%goal%", def.dailyGoal()));
        lore.add(messages.get("gui.activity-lore-points", "%points%", def.points()));
        lore.add(messages.get(done ? "gui.activity-lore-done" : "gui.activity-lore-not-done"));

        return item(def.icon(), Utils.colorize(def.display()), lore);
    }

    private ItemStack rewardItem(ActivityConfiguration config, Messages messages, PlayerData data) {
        List<String> lore = new ArrayList<>();
        lore.add(messages.get("gui.reward-lore-header"));
        lore.addAll(Messages.colorize(config.rewardDisplay()));
        if (data.rewarded()) {
            lore.add(messages.get("gui.reward-claimed"));
        }

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
    // the top inventory is what gets checked rather than the clicked slot
    // ====================================
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Marker) {
            event.setCancelled(true);
        }
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
