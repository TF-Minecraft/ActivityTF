package net.tfminecraft.activitytf.gui;

import com.google.common.collect.ImmutableMultimap;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.config.Messages;
import net.tfminecraft.activitytf.config.ReferralConfiguration;
import net.tfminecraft.activitytf.config.ReferralConfiguration.Act;
import net.tfminecraft.activitytf.config.ReferralConfiguration.Milestone;
import net.tfminecraft.activitytf.config.ReferralConfiguration.Reward;
import net.tfminecraft.activitytf.hooks.ItemsAdderItems;
import net.tfminecraft.activitytf.hooks.TLibsItems;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.managers.ReferralManager;
import net.tfminecraft.activitytf.managers.ReferralManager.Claim;
import net.tfminecraft.activitytf.managers.ReferralManager.Link;
import net.tfminecraft.activitytf.managers.ReferralManager.State;
import net.tfminecraft.activitytf.models.ReferralData;
import net.tfminecraft.activitytf.models.RewardEntry;
import net.tfminecraft.activitytf.utils.Bar;
import net.tfminecraft.activitytf.utils.Utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** /referral: your own milestones, the players you invited, and picking who invited you. */
public class ReferralGui implements Listener {

    static final int SIZE = 54;
    static final int CONFIRM_SIZE = 27;

    static final int INVITER_SLOT = 0;
    static final int PROGRESS_SLOT = 4;
    static final int RULES_SLOT = 8;
    static final int MILESTONE_ROW = 9;
    static final int INVITED_HEADER_SLOT = 22;
    static final int FIRST_INVITEE_SLOT = 27;
    static final int INVITEES_PER_PAGE = 18;
    static final int BACK_SLOT = 45;
    static final int PREVIOUS_SLOT = 48;
    static final int RARE_SLOT = 49;
    static final int TYPE_NAME_SLOT = 49;
    static final int NEXT_SLOT = 50;
    static final int PLAYERS_PER_PAGE = 45;

    static final int CONFIRM_SLOT = 11;
    static final int CONFIRM_HEAD_SLOT = 13;
    static final int CANCEL_SLOT = 15;

    private static final int PROGRESS_BAR_LENGTH = 30;

    enum View {
        MAIN,
        PICK,
        CONFIRM
    }

    public static final class Marker implements InventoryHolder {

        private final View view;
        private int page;
        private boolean previous;
        private boolean next;
        private final UUID target;
        private final List<UUID> shown = new ArrayList<>();
        private final List<Integer> hours = new ArrayList<>();
        private Inventory inventory;

        Marker(View view, int page, UUID target) {
            this.view = view;
            this.page = page;
            this.target = target;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        View view() {
            return view;
        }

        int page() {
            return page;
        }

        UUID target() {
            return target;
        }
    }

    private final ActivityManager manager;
    private final Consumer<Player> openActivity;

    public ReferralGui(ActivityManager manager, Consumer<Player> openActivity) {
        this.manager = manager;
        this.openActivity = openActivity;
    }

    private ReferralManager referrals() {
        return manager.referrals();
    }

    private Messages messages() {
        return manager.getConfiguration().messages();
    }

    public void open(Player player) {
        player.openInventory(build(player, 0));
    }

    // ---- main view ---------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    public Inventory build(Player player, int page) {
        Messages messages = messages();
        ReferralManager referrals = referrals();
        ReferralData data = referrals.getStore().get(player.getUniqueId(), player.getName());
        Act act = referrals.act();

        Marker marker = new Marker(View.MAIN, page, null);
        Inventory inventory = Bukkit.createInventory(marker, SIZE, messages.get("referral.gui.title"));
        marker.inventory = inventory;

        inventory.setItem(INVITER_SLOT, inviterItem(player, data));
        inventory.setItem(PROGRESS_SLOT, progressItem(data, act));
        inventory.setItem(RULES_SLOT, rulesItem());

        List<Milestone> milestones = act == null ? List.of() : act.milestones();
        int first = MILESTONE_ROW + (9 - milestones.size()) / 2;
        for (int index = 0; index < milestones.size(); index++) {
            Milestone milestone = milestones.get(index);
            marker.hours.add(milestone.newPlayer() == null ? null : milestone.hours());
            inventory.setItem(first + index, ownMilestoneItem(data, milestone));
        }
        if (act == null) {
            inventory.setItem(MILESTONE_ROW + 4, item(Material.BARRIER, messages.get("referral.gui.no-act"), List.of()));
        }

        List<UUID> invited = referrals.invitees(player.getUniqueId());
        inventory.setItem(INVITED_HEADER_SLOT, item(Material.NAME_TAG,
            messages.get("referral.gui.invited-header", "%count%", invited.size()),
            List.of(messages.get("referral.gui.invited-header-lore"))));
        int pages = Math.max(1, (invited.size() + INVITEES_PER_PAGE - 1) / INVITEES_PER_PAGE);
        int shownPage = Math.max(0, Math.min(page, pages - 1));
        marker.page = shownPage;
        List<UUID> slice = invited.subList(shownPage * INVITEES_PER_PAGE,
            Math.min(invited.size(), (shownPage + 1) * INVITEES_PER_PAGE));
        for (int index = 0; index < slice.size(); index++) {
            marker.shown.add(slice.get(index));
            inventory.setItem(FIRST_INVITEE_SLOT + index, inviteeItem(data, slice.get(index), act));
        }

        inventory.setItem(BACK_SLOT, item(Material.EXPERIENCE_BOTTLE, messages.get("referral.gui.back"), List.of()));
        pageArrows(inventory, marker, shownPage, pages);
        inventory.setItem(RARE_SLOT, rareItem(data, act));
        fill(inventory);
        return inventory;
    }

    private ItemStack inviterItem(Player player, ReferralData data) {
        Messages messages = messages();
        if (data.invitedBy() != null) {
            ReferralData inviter = referrals().getStore().peek(data.invitedBy());
            String name = inviter == null ? "?" : inviter.name();
            return head(data.invitedBy(), messages.get("referral.gui.invited-by", "%name%", name),
                List.of(messages.get("referral.gui.invited-by-lore")));
        }
        Link refused = referrals().linkable(player.getUniqueId());
        if (refused != null) {
            return item(Material.GRAY_DYE, messages.get("referral.gui.link-closed"),
                List.of(messages.get(linkKey(refused))));
        }
        return item(Material.WRITABLE_BOOK, messages.get("referral.gui.who-invited"),
            List.of(messages.get("referral.gui.who-invited-lore")));
    }

    private ItemStack progressItem(ReferralData data, Act act) {
        Messages messages = messages();
        int goal = act == null ? 0 : act.milestones().get(act.milestones().size() - 1).minutes();
        List<Integer> marks = new ArrayList<>();
        if (act != null) {
            for (Milestone milestone : act.milestones()) {
                marks.add(milestone.minutes());
            }
        }
        List<String> lore = new ArrayList<>();
        lore.add(Utils.colorize(Bar.render(data.minutes(), goal, PROGRESS_BAR_LENGTH, marks)));
        lore.add(messages.get(data.invitedBy() == null ? "referral.gui.progress-unlinked" : "referral.gui.progress-lore"));
        return item(Material.CLOCK, messages.get("referral.gui.progress", "%played%", duration(data.minutes()),
            "%goal%", duration(goal)), lore);
    }

    private ItemStack rulesItem() {
        Messages messages = messages();
        ReferralConfiguration config = referrals().getConfiguration();
        return item(Material.BOOK, messages.get("referral.gui.rules"), messages.lines("referral.gui.rules-lore",
            "%open-days%", config.openDays(), "%new-days%", config.newAccountDays(), "%afk%", config.afkMinutes()));
    }

    private ItemStack rareItem(ReferralData data, Act act) {
        Messages messages = messages();
        if (act == null) {
            return item(Material.NETHER_STAR, messages.get("referral.gui.no-act"), List.of());
        }
        String limit = act.rareLimit() < 0 ? "∞" : Integer.toString(act.rareLimit());
        return item(Material.NETHER_STAR, messages.get("referral.gui.rare", "%used%", data.rareClaims(act.id()),
            "%limit%", limit), List.of(messages.get("referral.gui.rare-lore", "%act%", Utils.colorize(act.display()))));
    }

    private ItemStack ownMilestoneItem(ReferralData data, Milestone milestone) {
        Messages messages = messages();
        Reward reward = milestone.newPlayer();
        if (reward == null) {
            return item(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                messages.get("referral.gui.milestone-none", "%hours%", milestone.hours()), List.of());
        }
        State state = referrals().ownState(data, milestone);
        List<String> lore = rewardLore(reward, milestone);
        lore.add(" ");
        lore.add(switch (state) {
            case CLAIMED -> messages.get("referral.gui.status-claimed");
            case CLAIMABLE -> messages.get("referral.gui.status-claim");
            default -> data.invitedBy() == null ? messages.get("referral.gui.status-unlinked")
                : messages.get("referral.gui.status-locked", "%left%",
                    duration(milestone.minutes() - data.minutes()));
        });
        return rewardItem(reward, stateName(state, milestone, reward), lore, state == State.CLAIMABLE);
    }

    private ItemStack inviteeItem(ReferralData inviter, UUID inviteeId, Act act) {
        Messages messages = messages();
        ReferralData invitee = referrals().getStore().peek(inviteeId);
        List<String> lore = new ArrayList<>();
        lore.add(messages.get("referral.gui.invitee-played", "%played%", duration(invitee.minutes())));
        Integer next = null;
        if (act != null) {
            for (Milestone milestone : act.milestones()) {
                Reward reward = milestone.inviter();
                if (reward == null) {
                    continue;
                }
                State state = referrals().inviterState(inviter, invitee, act, milestone);
                if (state == State.CLAIMABLE && next == null) {
                    next = milestone.hours();
                }
                lore.add(messages.get("referral.gui.invitee-line-" + state.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    "%hours%", milestone.hours(), "%reward%", Utils.colorize(reward.display()),
                    "%left%", duration(milestone.minutes() - invitee.minutes())));
            }
        }
        lore.add(" ");
        lore.add(messages.get(next == null ? "referral.gui.invitee-nothing" : "referral.gui.invitee-click"));
        String name = next == null
            ? messages.get("referral.gui.invitee", "%name%", invitee.name(), "%played%", duration(invitee.minutes()))
            : messages.get("referral.gui.invitee-claimable", "%name%", invitee.name(),
                "%played%", duration(invitee.minutes()), "%hours%", next);
        ItemStack head = head(inviteeId, name, lore);
        if (next != null) {
            glint(head);
        }
        return head;
    }

    private String stateName(State state, Milestone milestone, Reward reward) {
        String key = switch (state) {
            case CLAIMED -> "referral.gui.milestone-claimed";
            case CLAIMABLE -> "referral.gui.milestone-claimable";
            default -> "referral.gui.milestone-locked";
        };
        return messages().get(key, "%hours%", milestone.hours(), "%reward%", Utils.colorize(reward.display()));
    }

    /** Exactly what a reward hands over: its items, or every option of the pool it draws from. */
    List<String> rewardLore(Reward reward, Milestone milestone) {
        Messages messages = messages();
        List<String> lore = new ArrayList<>();
        if (milestone.rare()) {
            lore.add(messages.get("referral.gui.rare-tag"));
        }
        if (reward.fixed() != null) {
            lore.add(messages.get("referral.gui.contents"));
            for (RewardEntry.Item item : reward.fixed().items()) {
                lore.add(Utils.colorize("#50d990x" + item.amount() + " &f" + ActivityConfiguration.itemName(item.path())));
            }
            if (!reward.fixed().commands().isEmpty() && !reward.fixed().display().isBlank()) {
                lore.add(Utils.colorize("&f" + reward.fixed().display()));
            }
            return lore;
        }
        List<RewardEntry> pool = referrals().getConfiguration().pool(reward.pool());
        if (pool.isEmpty()) {
            lore.add(messages.get("gui.reward-preview-unavailable"));
            return lore;
        }
        lore.add(messages.get("gui.reward-preview-one"));
        for (String option : RewardPreview.summarize(pool)) {
            lore.add(Utils.colorize(option));
        }
        return lore;
    }

    private ItemStack rewardItem(Reward reward, String name, List<String> lore, boolean glint) {
        ItemStack stack = rewardIcon(reward);
        item(stack, name, lore);
        if (glint) {
            glint(stack);
        }
        return stack;
    }

    ItemStack rewardIcon(Reward reward) {
        String path = reward.icon();
        if (path == null && reward.fixed() != null && !reward.fixed().items().isEmpty()) {
            path = reward.fixed().items().get(0).path();
        }
        if (path != null) {
            ItemStack resolved = ActivityManager.resolveRewardItem(path, manager.getConfiguration().itemPathsUsable(),
                manager.getConfiguration().itemsAdderUsable(), TLibsItems::item, ItemsAdderItems::item);
            if (ActivityManager.usable(resolved)) {
                ItemStack icon = resolved.clone();
                icon.setAmount(1);
                return icon;
            }
        }
        return new ItemStack(reward.pool() != null ? Material.CHEST : Material.PAPER);
    }

    // ---- choosing who invited you --------------------------------------------------------------

    @SuppressWarnings("deprecation")
    Inventory buildPicker(Player player, int page) {
        Messages messages = messages();
        List<Player> others = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(player.getUniqueId()) && player.canSee(online)) {
                others.add(online);
            }
        }
        others.sort(Comparator.comparing(online -> online.getName().toLowerCase(Locale.ROOT)));
        int pages = Math.max(1, (others.size() + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
        int shownPage = Math.max(0, Math.min(page, pages - 1));

        Marker marker = new Marker(View.PICK, shownPage, null);
        Inventory inventory = Bukkit.createInventory(marker, SIZE, messages.get("referral.gui.pick-title"));
        marker.inventory = inventory;
        List<Player> slice = others.subList(shownPage * PLAYERS_PER_PAGE,
            Math.min(others.size(), (shownPage + 1) * PLAYERS_PER_PAGE));
        for (int index = 0; index < slice.size(); index++) {
            Player other = slice.get(index);
            marker.shown.add(other.getUniqueId());
            inventory.setItem(index, head(other.getUniqueId(), messages.get("referral.gui.pick-player",
                "%name%", other.getName()), List.of(messages.get("referral.gui.pick-player-lore"))));
        }
        inventory.setItem(BACK_SLOT, item(Material.OAK_DOOR, messages.get("referral.gui.pick-back"), List.of()));
        pageArrows(inventory, marker, shownPage, pages);
        inventory.setItem(TYPE_NAME_SLOT, item(Material.OAK_SIGN, messages.get("referral.gui.type-name"),
            List.of(messages.get("referral.gui.type-name-lore"))));
        fill(inventory);
        return inventory;
    }

    private void pageArrows(Inventory inventory, Marker marker, int page, int pages) {
        Messages messages = messages();
        marker.previous = page > 0;
        marker.next = page < pages - 1;
        if (marker.previous) {
            inventory.setItem(PREVIOUS_SLOT, item(Material.ARROW, messages.get("referral.gui.previous"), List.of()));
        }
        if (marker.next) {
            inventory.setItem(NEXT_SLOT, item(Material.ARROW, messages.get("referral.gui.next"), List.of()));
        }
    }

    /** The last step before a permanent link: shows who and asks once more. */
    @SuppressWarnings("deprecation")
    public Inventory buildConfirm(UUID inviter) {
        Messages messages = messages();
        ReferralData data = referrals().getStore().peek(inviter);
        String name = data == null ? "?" : data.name();
        Marker marker = new Marker(View.CONFIRM, 0, inviter);
        Inventory inventory = Bukkit.createInventory(marker, CONFIRM_SIZE, messages.get("referral.gui.confirm-title"));
        marker.inventory = inventory;
        inventory.setItem(CONFIRM_SLOT, item(Material.LIME_CONCRETE, messages.get("referral.gui.confirm", "%name%", name),
            List.of(messages.get("referral.gui.confirm-lore"))));
        inventory.setItem(CONFIRM_HEAD_SLOT, head(inviter, messages.get("referral.gui.pick-player", "%name%", name),
            List.of()));
        inventory.setItem(CANCEL_SLOT, item(Material.RED_CONCRETE, messages.get("referral.gui.cancel"), List.of()));
        fill(inventory);
        return inventory;
    }

    /** The command's typed-name route: the same confirm screen the picker leads to. */
    public void confirmTyped(Player player, String name) {
        Link refused = referrals().linkable(player.getUniqueId());
        UUID inviter = refused == null ? referrals().find(name) : null;
        if (refused == null && inviter == null) {
            refused = Link.UNKNOWN;
        } else if (refused == null && inviter.equals(player.getUniqueId())) {
            refused = Link.SELF;
        }
        if (refused != null) {
            player.sendMessage(messages().get(linkKey(refused), "%name%", name));
            return;
        }
        player.openInventory(buildConfirm(inviter));
    }

    // ---- clicks ------------------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Marker marker)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()
            || !(event.getWhoClicked() instanceof Player player)
            || !player.hasPermission("activity.referral")) {
            return;
        }
        int slot = event.getRawSlot();
        switch (marker.view()) {
            case MAIN -> clickMain(player, marker, slot);
            case PICK -> clickPicker(player, marker, slot);
            case CONFIRM -> clickConfirm(player, marker, slot);
        }
    }

    // Opening another inventory from inside its click event is left to the next tick.
    private void show(Player player, Supplier<Inventory> next) {
        referrals().runNextTick(() -> {
            if (player.isOnline()) {
                player.openInventory(next.get());
            }
        });
    }

    private static boolean pageClick(Marker marker, int slot) {
        return slot == PREVIOUS_SLOT && marker.previous || slot == NEXT_SLOT && marker.next;
    }

    private void clickMain(Player player, Marker marker, int slot) {
        if (slot == BACK_SLOT) {
            if (!player.hasPermission("activity.use")) {
                player.sendMessage(messages().get("admin.no-permission"));
                return;
            }
            sound(player);
            referrals().runNextTick(() -> openActivity.accept(player));
            return;
        }
        if (pageClick(marker, slot)) {
            sound(player);
            show(player, () -> build(player, marker.page() + (slot == NEXT_SLOT ? 1 : -1)));
            return;
        }
        if (slot == INVITER_SLOT) {
            Link refused = referrals().linkable(player.getUniqueId());
            if (refused != null) {
                player.sendMessage(messages().get(linkKey(refused), "%name%", ""));
                return;
            }
            sound(player);
            show(player, () -> buildPicker(player, 0));
            return;
        }
        int first = MILESTONE_ROW + (9 - marker.hours.size()) / 2;
        int index = slot - first;
        if (slot >= MILESTONE_ROW && slot < MILESTONE_ROW + 9 && index >= 0 && index < marker.hours.size()
            && marker.hours.get(index) != null) {
            Claim claim = referrals().claimOwn(player, marker.hours.get(index));
            report(player, claim);
            show(player, () -> build(player, marker.page()));
            return;
        }
        int invitee = slot - FIRST_INVITEE_SLOT;
        if (invitee >= 0 && invitee < marker.shown.size()) {
            List<Claim> claims = referrals().claimAllForInvitee(player, marker.shown.get(invitee));
            if (claims.isEmpty()) {
                player.sendMessage(messages().get("referral.claim.nothing"));
            } else {
                report(player, claims.get(claims.size() - 1));
            }
            show(player, () -> build(player, marker.page()));
        }
    }

    private void clickPicker(Player player, Marker marker, int slot) {
        if (slot == BACK_SLOT) {
            sound(player);
            show(player, () -> build(player, 0));
        } else if (pageClick(marker, slot)) {
            sound(player);
            show(player, () -> buildPicker(player, marker.page() + (slot == NEXT_SLOT ? 1 : -1)));
        } else if (slot == TYPE_NAME_SLOT) {
            sound(player);
            referrals().runNextTick(player::closeInventory);
            String text = messages().get("referral.type-name-chat");
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(text)
                .clickEvent(ClickEvent.suggestCommand("/referral invitedby ")));
        } else if (slot >= 0 && slot < marker.shown.size()) {
            sound(player);
            show(player, () -> buildConfirm(marker.shown.get(slot)));
        }
    }

    private void clickConfirm(Player player, Marker marker, int slot) {
        if (slot == CANCEL_SLOT) {
            sound(player);
            show(player, () -> build(player, 0));
            return;
        }
        if (slot != CONFIRM_SLOT) {
            return;
        }
        Link link = referrals().link(player, marker.target());
        ReferralData inviter = referrals().getStore().peek(marker.target());
        player.sendMessage(messages().get(linkKey(link), "%name%", inviter == null ? "?" : inviter.name()));
        if (link == Link.LINKED) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            Player online = Bukkit.getPlayer(marker.target());
            if (online != null) {
                online.sendMessage(messages().get("referral.linked-inviter", "%name%", player.getName()));
            }
        }
        show(player, () -> build(player, 0));
    }

    private void report(Player player, Claim claim) {
        if (claim == Claim.CLAIMED) {
            String sound = manager.getConfiguration().barCompleteSound();
            if (sound != null) {
                player.playSound(player.getLocation(), sound, 1f, 1f);
            }
            return;
        }
        player.sendMessage(messages().get("referral.claim." + claim.name().toLowerCase(Locale.ROOT).replace('_', '-')));
    }

    static String linkKey(Link link) {
        return "referral.link." + link.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Marker)) {
            return;
        }
        int size = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < size) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ---- items -------------------------------------------------------------------------------

    static String duration(int minutes) {
        int safe = Math.max(0, minutes);
        return safe < 60 ? safe + "m" : safe / 60 + "h" + (safe % 60 == 0 ? "" : " " + safe % 60 + "m");
    }

    private ItemStack head(UUID uuid, String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        if (stack.getItemMeta() instanceof SkullMeta skull) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(uuid);
            skull.setOwningPlayer(owner);
            stack.setItemMeta(skull);
        }
        return item(stack, name, lore);
    }

    private static void glint(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        stack.setItemMeta(meta);
    }

    private void fill(Inventory inventory) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, messages().get("gui.filler-name"), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        return item(new ItemStack(material), name, lore);
    }

    @SuppressWarnings("deprecation")
    private static ItemStack item(ItemStack stack, String name, List<String> lore) {
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_STORED_ENCHANTS,
            ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.setAttributeModifiers(ImmutableMultimap.of());
        stack.setItemMeta(meta);
        return stack;
    }

    private static void sound(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
    }
}
