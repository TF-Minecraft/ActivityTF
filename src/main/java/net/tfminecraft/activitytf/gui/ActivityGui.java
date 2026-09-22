package net.tfminecraft.activitytf.gui;

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
import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.hooks.ItemsAdderItems;
import net.tfminecraft.activitytf.hooks.TLibsItems;
import net.tfminecraft.activitytf.config.Messages;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.models.ActivityDef;
import net.tfminecraft.activitytf.models.PlayerData;
import net.tfminecraft.activitytf.utils.Bar;
import net.tfminecraft.activitytf.utils.ItemPath;
import net.tfminecraft.activitytf.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class ActivityGui implements Listener {

    private static final int SIZE = 27;
    private static final int DAILY_BAR_SLOT = 3;
    private static final int REROLL_SLOT = 4;
    private static final int BAR_SLOT = 5;

    private static final int[] TASK_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    private static final int PROGRESS_BAR_LENGTH = 20;

    private final ActivityManager manager;

    public ActivityGui(ActivityManager manager) {
        this.manager = manager;
    }

    public static final class Marker implements InventoryHolder {

        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
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

    static String taskIdAt(ActivityConfiguration config, PlayerData data, int slot) {
        List<String> tasks = data.tasks();
        if (slot < 0 || slot >= tasks.size()) {
            return null;
        }
        String id = tasks.get(slot);
        return config.activity(id) == null ? null : id;
    }

    private void fillEmptySlots(Inventory inventory, Messages messages) {
        ItemStack filler = filler(messages);
        for (int slot = 0; slot < SIZE; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

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

    private static Integer nextMilestone(PlayerData data, List<Integer> milestones) {
        for (Integer milestone : milestones) {
            if (milestone > data.claimedPoints()) {
                return milestone;
            }
        }
        return null;
    }

    private ItemStack rerollItem(ActivityConfiguration config, Messages messages, PlayerData data, Player player) {
        int perDay = config.rerollsPerDay();
        String lore;
        if (perDay <= 0) {
            lore = messages.get("gui.reroll-lore-disabled");
        } else if (!player.hasPermission("activity.reroll")) {
            lore = messages.get("gui.reroll-lore-locked");
        } else if (data.dailyPoints() > config.rerollMaxPoints()) {
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

    static String progressBar(ActivityDef def, int count) {
        if (def.dailyCap() > 0 && def.worth(count) >= def.capPoints()) {
            return Bar.render(1, 1, PROGRESS_BAR_LENGTH);
        }
        if (def.every() > 1) {
            return Bar.render(count % def.every(), def.every(), PROGRESS_BAR_LENGTH);
        }
        return null;
    }

    private ItemStack iconStack(ActivityConfiguration config, Material icon, String iconPath) {
        ItemStack fromPath = fromPath(config, iconPath);
        if (fromPath != null && !fromPath.getType().isAir()) {
            return fromPath;
        }
        return new ItemStack(icon);
    }

    private ItemStack fromPath(ActivityConfiguration config, String iconPath) {
        return fromPath(iconPath, config.itemPathsUsable(), config.itemsAdderUsable(),
            path -> TLibsItems.item(path), id -> ItemsAdderItems.item(id));
    }

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

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
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

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Marker)) {
            return;
        }
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

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
            if (manager.runClickCommands(player, task)) {
                clickSound(player);
            }
            return;
        }

        ActivityConfiguration config = manager.getConfiguration();
        PlayerData data = manager.tasks(player.getUniqueId());
        Inventory top = event.getView().getTopInventory();
        if (reveal.drawChanged()) {
            repaintAll(top, config, data, player);
        } else {
            top.setItem(event.getRawSlot(), taskOrFiller(config, data, task));
        }
    }

    ActivityManager.Reveal revealTask(Player player, int task) {
        ActivityManager.Reveal reveal = manager.reveal(player.getUniqueId(), task);
        if (reveal.revealedId() != null) {
            manager.claimDailyReward(player);
        }
        return reveal;
    }

    private void repaintAll(Inventory top, ActivityConfiguration config, PlayerData data, Player player) {
        Messages messages = config.messages();
        top.setItem(DAILY_BAR_SLOT, dailyBarItem(config, messages, data));
        top.setItem(REROLL_SLOT, rerollItem(config, messages, data, player));
        top.setItem(BAR_SLOT, barItem(config, messages, data));
        for (int slot = 0; slot < TASK_SLOTS.length; slot++) {
            top.setItem(TASK_SLOTS[slot], taskOrFiller(config, data, slot));
        }
    }

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

    private ItemStack taskOrFiller(ActivityConfiguration config, PlayerData data, int slot) {
        Messages messages = config.messages();
        ItemStack task = taskItem(config, messages, data, slot);
        return task != null ? task : filler(messages);
    }

    private ItemStack filler(Messages messages) {
        return item(Material.GRAY_STAINED_GLASS_PANE, messages.get("gui.filler-name"), List.of());
    }

    static int taskSlot(int rawSlot) {
        for (int slot = 0; slot < TASK_SLOTS.length; slot++) {
            if (TASK_SLOTS[slot] == rawSlot) {
                return slot;
            }
        }
        return -1;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.forgetClickCooldown(event.getPlayer().getUniqueId());
    }

    private static void clickSound(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
    }

    private void claim(Player player, InventoryClickEvent event) {
        ActivityConfiguration config = manager.getConfiguration();
        if (manager.claim(player) == 0) {
            return;
        }
        event.getView().getTopInventory().setItem(BAR_SLOT,
            barItem(config, config.messages(), manager.getStore().get(player.getUniqueId())));
    }

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
