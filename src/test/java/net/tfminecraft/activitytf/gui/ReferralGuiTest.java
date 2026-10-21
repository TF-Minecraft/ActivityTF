package net.tfminecraft.activitytf.gui;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.activitytf.config.ReferralConfiguration.Reward;
import net.tfminecraft.activitytf.managers.ReferralFixture;
import net.tfminecraft.activitytf.managers.ReferralFixture.TestPlayer;
import net.tfminecraft.activitytf.managers.ReferralManager;
import net.tfminecraft.activitytf.managers.ReferralManager.Link;
import net.tfminecraft.activitytf.models.ReferralData;
import net.tfminecraft.activitytf.models.RewardEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferralGuiTest {

    private ReferralFixture fixture;
    private ReferralManager referrals;
    private ReferralGui gui;
    private final List<PlayerMock> activityOpened = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        fixture = ReferralFixture.start();
        referrals = fixture.referrals;
        gui = new ReferralGui(fixture.manager, player -> activityOpened.add((PlayerMock) player));
    }

    @AfterEach
    void stop() throws Exception {
        fixture.stop();
    }

    private static List<String> said(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
            lines.add(line);
        }
        return lines;
    }

    private Inventory top(PlayerMock player) {
        return player.getOpenInventory().getTopInventory();
    }

    private ReferralGui.Marker marker(PlayerMock player) {
        return (ReferralGui.Marker) top(player).getHolder();
    }

    private InventoryClickEvent click(PlayerMock player, int slot) {
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(),
            InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        gui.onClick(event);
        fixture.server.getScheduler().performOneTick();
        return event;
    }

    @SuppressWarnings("deprecation")
    private static String name(ItemStack stack) {
        return stack.getItemMeta().getDisplayName();
    }

    @SuppressWarnings("deprecation")
    private static List<String> lore(ItemStack stack) {
        return stack.getItemMeta().getLore();
    }

    private ReferralData data(PlayerMock player) {
        return referrals.getStore().peek(player.getUniqueId());
    }

    private void link(TestPlayer invitee, TestPlayer inviter) {
        assertEquals(Link.LINKED, referrals.link(invitee, inviter.getUniqueId()));
    }

    @Test
    void anUnlinkedPlayerSeesWhoInvitedYouTheirProgressRulesAndLockedMilestones() {
        TestPlayer player = fixture.join("Newbie", "10.0.0.1");
        data(player).setMinutes(95);
        gui.open(player);
        Inventory inventory = top(player);
        assertEquals(ReferralGui.SIZE, inventory.getSize());
        assertSame(inventory, inventory.getHolder().getInventory());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            assertNotNull(inventory.getItem(slot), "empty slot " + slot);
        }
        assertEquals(Material.WRITABLE_BOOK, inventory.getItem(ReferralGui.INVITER_SLOT).getType());
        assertTrue(name(inventory.getItem(ReferralGui.PROGRESS_SLOT)).contains("1h 35m"));
        assertTrue(name(inventory.getItem(ReferralGui.PROGRESS_SLOT)).contains("5h"));
        assertTrue(lore(inventory.getItem(ReferralGui.RULES_SLOT)).stream().anyMatch(line -> line.contains("14")));
        assertEquals(Material.BREAD, inventory.getItem(11).getType(), "three milestones sit one slot apart in the middle");
        assertEquals(Material.IRON_INGOT, inventory.getItem(13).getType());
        assertEquals(Material.EMERALD, inventory.getItem(15).getType());
        assertTrue(lore(inventory.getItem(11)).stream().anyMatch(line -> line.contains("pick who invited you")));
        assertTrue(lore(inventory.getItem(15)).stream().anyMatch(line -> line.contains("x2") && line.contains("Emerald")),
            "a one-entry pool lists what is inside");
        assertFalse(inventory.getItem(11).getItemMeta().hasEnchantmentGlintOverride());
        assertTrue(name(inventory.getItem(ReferralGui.RARE_SLOT)).contains("0 players"));
        assertTrue(lore(inventory.getItem(ReferralGui.RULES_SLOT)).stream().anyMatch(line -> line.contains("5h")),
            "the rules name the hours that count for the rare track");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(ReferralGui.MILESTONE_NEXT_SLOT).getType());
        assertTrue(name(inventory.getItem(ReferralGui.INVITED_HEADER_SLOT)).contains("(0)"));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(ReferralGui.NEXT_SLOT).getType());
    }

    @Test
    void pickingAnOnlinePlayerLeadsToAConfirmScreenAndLinks() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        gui.open(player);
        click(player, ReferralGui.INVITER_SLOT);
        assertEquals(ReferralGui.View.PICK, marker(player).view());
        assertEquals(Material.PLAYER_HEAD, top(player).getItem(0).getType());
        assertTrue(name(top(player).getItem(0)).contains("Inviter"));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, top(player).getItem(1).getType(), "you are not listed");

        click(player, 0);
        assertEquals(ReferralGui.View.CONFIRM, marker(player).view());
        assertEquals(inviter.getUniqueId(), marker(player).target());
        click(player, 0);
        assertEquals(ReferralGui.View.CONFIRM, marker(player).view(), "only the two buttons do anything");
        click(player, ReferralGui.CANCEL_SLOT);
        assertEquals(ReferralGui.View.MAIN, marker(player).view());
        assertNull(data(player).invitedBy());

        click(player, ReferralGui.INVITER_SLOT);
        click(player, 0);
        said(inviter);
        said(player);
        click(player, ReferralGui.CONFIRM_SLOT);
        assertEquals(inviter.getUniqueId(), data(player).invitedBy());
        assertTrue(said(player).stream().anyMatch(line -> line.contains("linked to") && line.contains("Inviter")));
        assertTrue(said(inviter).stream().anyMatch(line -> line.contains("Newbie")));
        assertEquals(Material.PLAYER_HEAD, top(player).getItem(ReferralGui.INVITER_SLOT).getType());
        assertTrue(name(top(player).getItem(ReferralGui.INVITER_SLOT)).contains("Inviter"));

        click(player, ReferralGui.INVITER_SLOT);
        assertTrue(said(player).stream().anyMatch(line -> line.contains("already linked")));
        assertEquals(ReferralGui.View.MAIN, marker(player).view());
    }

    @Test
    void aRefusedConfirmSaysWhyAndAnOfflineInviterGetsNoMessage() {
        TestPlayer twin = fixture.join("Twin", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.1");
        player.openInventory(gui.buildConfirm(twin.getUniqueId()));
        said(player);
        click(player, ReferralGui.CONFIRM_SLOT);
        assertTrue(said(player).stream().anyMatch(line -> line.contains("same connection")));

        player.openInventory(gui.buildConfirm(UUID.randomUUID()));
        assertTrue(name(top(player).getItem(ReferralGui.CONFIRM_SLOT)).contains("?"));
        click(player, ReferralGui.CONFIRM_SLOT);
        assertTrue(said(player).stream().anyMatch(line -> line.contains("Nobody called")));

        TestPlayer offline = fixture.join("Gone", "10.0.0.9");
        UUID gone = offline.getUniqueId();
        offline.disconnect();
        player.openInventory(gui.buildConfirm(gone));
        click(player, ReferralGui.CONFIRM_SLOT);
        assertEquals(gone, data(player).invitedBy());
    }

    @Test
    void theClosedLinkSlotSaysWhyWhenClicked() {
        TestPlayer player = fixture.join("Veteran", "10.0.0.1");
        fixture.advanceDays(30);
        gui.open(player);
        assertEquals(Material.GRAY_DYE, top(player).getItem(ReferralGui.INVITER_SLOT).getType());
        said(player);
        click(player, ReferralGui.INVITER_SLOT);
        assertTrue(said(player).stream().anyMatch(line -> line.contains("only open")));
    }

    @Test
    void thePickerPagesTypesAndGoesBack() {
        TestPlayer player = fixture.join("Newbie", "10.0.0.1");
        for (int index = 0; index < 50; index++) {
            fixture.join("P" + index, "10.1.0." + index);
        }
        player.openInventory(gui.buildPicker(player, 0));
        assertEquals(Material.ARROW, top(player).getItem(ReferralGui.NEXT_SLOT).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, top(player).getItem(ReferralGui.PREVIOUS_SLOT).getType());
        click(player, ReferralGui.NEXT_SLOT);
        assertEquals(1, marker(player).page());
        assertEquals(Material.ARROW, top(player).getItem(ReferralGui.PREVIOUS_SLOT).getType());
        assertEquals(Material.PLAYER_HEAD, top(player).getItem(4).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, top(player).getItem(5).getType());
        click(player, ReferralGui.PREVIOUS_SLOT);
        assertEquals(0, marker(player).page());
        player.openInventory(gui.buildPicker(player, 99));
        assertEquals(1, marker(player).page(), "a page past the end shows the last one");

        click(player, ReferralGui.BACK_SLOT);
        assertEquals(ReferralGui.View.MAIN, marker(player).view());

        player.openInventory(gui.buildPicker(player, 0));
        said(player);
        click(player, ReferralGui.TYPE_NAME_SLOT);
        assertTrue(said(player).stream().anyMatch(line -> line.contains("type the name")));
        player.openInventory(gui.buildPicker(player, 0));
        click(player, 46);
        assertEquals(ReferralGui.View.PICK, marker(player).view());
    }

    @Test
    void aTypedNameOpensTheSameConfirmOrSaysWhyNot() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        gui.confirmTyped(player, "inviter");
        assertEquals(ReferralGui.View.CONFIRM, marker(player).view());
        assertEquals(inviter.getUniqueId(), marker(player).target());

        said(player);
        gui.confirmTyped(player, "nobody");
        assertTrue(said(player).stream().anyMatch(line -> line.contains("nobody")));
        gui.confirmTyped(player, "Newbie");
        assertTrue(said(player).stream().anyMatch(line -> line.contains("yourself")));
        link(player, inviter);
        gui.confirmTyped(player, "Inviter");
        assertTrue(said(player).stream().anyMatch(line -> line.contains("already linked")));
    }

    @Test
    void ownMilestonesGlowWhenClaimableAndAClickPaysIt() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        link(player, inviter);
        data(player).setMinutes(60);
        gui.open(player);
        Inventory inventory = top(player);
        assertEquals(Boolean.TRUE, inventory.getItem(11).getItemMeta().getEnchantmentGlintOverride());
        assertTrue(lore(inventory.getItem(13)).stream().anyMatch(line -> line.contains("2h")), "time left");
        assertTrue(lore(inventory.getItem(11)).stream().anyMatch(line -> line.contains("Bread")));

        click(player, 13);
        assertTrue(said(player).stream().anyMatch(line -> line.contains("Not played long enough")));
        click(player, 11);
        assertTrue(player.getInventory().contains(Material.BREAD, 4));
        assertTrue(name(top(player).getItem(11)).contains("✔"));
        assertFalse(top(player).getItem(11).getItemMeta().hasEnchantmentGlintOverride());
        click(player, 10);
        click(player, 9);
    }

    @Test
    void invitedPlayersShowTheirHoursAndAClickClaimsEverythingDue() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer first = fixture.join("Alpha", "10.0.0.2");
        TestPlayer second = fixture.join("Beta", "10.0.0.3");
        link(first, inviter);
        link(second, inviter);
        data(first).setMinutes(300);
        data(second).setMinutes(300);
        data(second).inviterClaimed().addAll(List.of(1, 5));

        gui.open(inviter);
        Inventory inventory = top(inviter);
        ItemStack alpha = inventory.getItem(ReferralGui.FIRST_INVITEE_SLOT);
        assertEquals(Material.PLAYER_HEAD, alpha.getType());
        assertTrue(name(alpha).contains("Alpha") && name(alpha).contains("CLAIM 1h"));
        assertEquals(Boolean.TRUE, alpha.getItemMeta().getEnchantmentGlintOverride());
        assertTrue(lore(alpha).stream().anyMatch(line -> line.contains("Counts for your rare track")
            && !line.contains(" at ")));
        ItemStack beta = inventory.getItem(ReferralGui.FIRST_INVITEE_SLOT + 1);
        assertFalse(name(beta).contains("CLAIM"));
        assertTrue(lore(beta).stream().anyMatch(line -> line.contains("✔")));
        assertTrue(name(inventory.getItem(ReferralGui.INVITED_HEADER_SLOT)).contains("(2)"));
        assertTrue(name(inventory.getItem(ReferralGui.RARE_SLOT)).contains("2 players"));

        said(inviter);
        click(inviter, ReferralGui.FIRST_INVITEE_SLOT);
        assertTrue(inviter.getInventory().contains(Material.GOLD_NUGGET, 3));
        click(inviter, ReferralGui.FIRST_INVITEE_SLOT + 1);
        assertTrue(said(inviter).stream().anyMatch(line -> line.contains("Nothing to claim")));

        data(first).inviterClaimed().remove(5);
        data(inviter).addIp(data(first).ips().iterator().next());
        click(inviter, ReferralGui.FIRST_INVITEE_SLOT);
        assertTrue(said(inviter).stream().anyMatch(line -> line.contains("same connection")));
        assertTrue(lore(top(inviter).getItem(ReferralGui.FIRST_INVITEE_SLOT)).stream()
            .anyMatch(line -> line.contains("Doesn't count for your rare track")), "the goal reached on a shared connection");
        click(inviter, ReferralGui.FIRST_INVITEE_SLOT + 5);
    }

    @Test
    void manyInvitedPlayersArePaged() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        for (int index = 0; index < 20; index++) {
            TestPlayer invitee = fixture.join("I" + (char) ('a' + index), "10.2.0." + index);
            link(invitee, inviter);
        }
        gui.open(inviter);
        assertEquals(Material.ARROW, top(inviter).getItem(ReferralGui.NEXT_SLOT).getType());
        click(inviter, ReferralGui.NEXT_SLOT);
        assertEquals(1, marker(inviter).page());
        assertEquals(Material.PLAYER_HEAD, top(inviter).getItem(ReferralGui.FIRST_INVITEE_SLOT + 1).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, top(inviter).getItem(ReferralGui.FIRST_INVITEE_SLOT + 2).getType());
        click(inviter, ReferralGui.PREVIOUS_SLOT);
        assertEquals(0, marker(inviter).page());
        click(inviter, ReferralGui.NEXT_SLOT);
        assertEquals(1, marker(inviter).page(), "no arrow on the last page, so nothing happens");
        click(inviter, ReferralGui.BACK_SLOT);
        assertEquals(List.of(inviter), activityOpened);

        gui.open(inviter);
        click(inviter, ReferralGui.PREVIOUS_SLOT);
        assertEquals(0, marker(inviter).page(), "no arrow on the first page either");
        inviter.permissions.setPermission("activity.use", false);
        said(inviter);
        click(inviter, ReferralGui.BACK_SLOT);
        assertEquals(1, activityOpened.size(), "the way back needs activity.use");
        assertTrue(said(inviter).stream().anyMatch(line -> line.contains("No permission")));
        TestPlayer gone = fixture.join("Gone", "10.9.9.9");
        gone.openInventory(gui.build(gone, 0));
        InventoryClickEvent late = new InventoryClickEvent(gone.getOpenInventory(),
            InventoryType.SlotType.CONTAINER, ReferralGui.INVITER_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        gui.onClick(late);
        gone.disconnect();
        fixture.server.getScheduler().performOneTick();
    }

    @Test
    void withoutAnActTheMenuSaysSo() throws Exception {
        fixture.writeReferral(ReferralFixture.CONFIG.replace("starts: \"2026-10-01\"", "starts: \"2026-11-01\""));
        referrals.reload();
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        link(player, inviter);
        gui.open(inviter);
        assertEquals(Material.BARRIER, top(inviter).getItem(13).getType());
        assertTrue(lore(top(inviter).getItem(ReferralGui.FIRST_INVITEE_SLOT)).stream()
            .anyMatch(line -> line.contains("Nothing to claim")));
        assertEquals(Material.NETHER_STAR, top(inviter).getItem(ReferralGui.RARE_SLOT).getType());
        assertTrue(name(top(inviter).getItem(ReferralGui.RARE_SLOT)).contains("No referral rewards"));
        click(inviter, ReferralGui.RARE_SLOT);
        assertTrue(name(top(inviter).getItem(22)).contains("No referral rewards"));
    }

    @Test
    void milestonesWithOnlyAnInviterRewardAndNoRareTrack() throws Exception {
        fixture.writeReferral(ReferralFixture.CONFIG
            .replace("rare-track:", "unused-track:")
            .replace("""
                  - hours: 3
                    new-player:
                      display: "Small"
                      items:
                        - item: IRON_INGOT
                          amount: 2
                """, """
                  - hours: 3
                    inviter:
                      display: "Inviter only"
                      commands: ["say hi"]
                """));
        referrals.reload();
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        link(player, inviter);
        gui.open(player);
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, top(player).getItem(13).getType());
        click(player, 13);
        assertTrue(name(top(player).getItem(ReferralGui.RARE_SLOT)).contains("No rare track"));
        assertFalse(lore(top(player).getItem(ReferralGui.RULES_SLOT)).stream()
            .anyMatch(line -> line.contains("rare track")), "no rare track, no rule about it");
        gui.open(inviter);
        assertFalse(lore(top(inviter).getItem(ReferralGui.FIRST_INVITEE_SLOT)).stream()
            .anyMatch(line -> line.contains("rare track")), "no rare track, no rare line");
        click(inviter, ReferralGui.RARE_SLOT);
        assertEquals(Material.BARRIER, top(inviter).getItem(22).getType());
    }

    @Test
    void theRareTrackMenuShowsEachStepAndClaimsIt() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer first = fixture.join("Alpha", "10.0.0.2");
        TestPlayer second = fixture.join("Beta", "10.0.0.3");
        link(first, inviter);
        link(second, inviter);
        data(first).setMinutes(300);
        data(first).inviterClaimed().addAll(List.of(1, 5));
        data(second).setMinutes(60);

        gui.open(inviter);
        ItemStack star = top(inviter).getItem(ReferralGui.RARE_SLOT);
        assertEquals(Material.NETHER_STAR, star.getType());
        assertTrue(name(star).contains("1 player") && !name(star).contains("players"), name(star));
        assertEquals(Boolean.TRUE, star.getItemMeta().getEnchantmentGlintOverride(), "the 1-player step is ready");
        assertTrue(lore(star).stream().anyMatch(line -> line.contains("Next step") && line.contains("1 player")));
        assertTrue(lore(top(inviter).getItem(ReferralGui.FIRST_INVITEE_SLOT + 1)).stream()
            .anyMatch(line -> line.contains("Counts for your rare track at 5h")));

        click(inviter, ReferralGui.RARE_SLOT);
        Inventory rare = top(inviter);
        assertEquals(ReferralGui.SIZE, rare.getSize());
        assertTrue(lore(rare.getItem(ReferralGui.RARE_HEADER_SLOT)).stream()
            .anyMatch(line -> line.contains("Reached 5h") && line.contains("Alpha")));
        ItemStack one = rare.getItem(ReferralGui.rareStepSlot(0));
        ItemStack two = rare.getItem(ReferralGui.rareStepSlot(1));
        assertEquals(Material.CHEST, one.getType(), "a pool step without an icon");
        assertEquals(Boolean.TRUE, one.getItemMeta().getEnchantmentGlintOverride());
        assertEquals(Material.DIAMOND, two.getType());
        assertFalse(two.getItemMeta().hasEnchantmentGlintOverride());
        assertTrue(lore(two).stream().anyMatch(line -> line.contains("x1") && line.contains("Diamond")));
        assertTrue(lore(two).stream().anyMatch(line -> line.contains("1 player") && line.contains("more need")));

        said(inviter);
        click(inviter, ReferralGui.rareStepSlot(1));
        assertTrue(said(inviter).stream().anyMatch(line -> line.contains("Not enough of the players you invited")));
        click(inviter, ReferralGui.rareStepSlot(0));
        assertTrue(inviter.getInventory().contains(Material.EMERALD, 2));
        assertTrue(name(top(inviter).getItem(ReferralGui.rareStepSlot(0))).contains("✔"));
        click(inviter, ReferralGui.RARE_HEADER_SLOT);
        assertEquals(ReferralGui.View.RARE, marker(inviter).view());

        click(inviter, ReferralGui.BACK_SLOT);
        assertEquals(ReferralGui.View.MAIN, marker(inviter).view());
        star = top(inviter).getItem(ReferralGui.RARE_SLOT);
        assertFalse(star.getItemMeta().hasEnchantmentGlintOverride());

        data(second).setMinutes(300);
        data(second).inviterClaimed().addAll(List.of(1, 5));
        data(inviter).rareClaimed().add(2);
        gui.open(inviter);
        assertTrue(lore(top(inviter).getItem(ReferralGui.RARE_SLOT)).stream()
            .anyMatch(line -> line.contains("Every rare reward claimed")));
    }

    @Test
    void theRareHeaderShortensALongListOfNames() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        for (int index = 0; index < 12; index++) {
            TestPlayer invitee = fixture.join("R" + (char) ('a' + index), "10.3.0." + index);
            link(invitee, inviter);
            data(invitee).setMinutes(300);
        }
        inviter.openInventory(gui.buildRare(inviter));
        assertTrue(lore(top(inviter).getItem(ReferralGui.RARE_HEADER_SLOT)).stream()
            .anyMatch(line -> line.contains("+2")));
        TestPlayer nobody = fixture.join("Nobody", "10.4.0.1");
        nobody.openInventory(gui.buildRare(nobody));
        assertTrue(lore(top(nobody).getItem(ReferralGui.RARE_HEADER_SLOT)).stream()
            .anyMatch(line -> line.contains("Nobody you invited")));
    }

    @Test
    void moreThanSevenMilestonesScrollTheRow() throws Exception {
        StringBuilder milestones = new StringBuilder("milestones:\n");
        for (int hours = 1; hours <= 9; hours++) {
            milestones.append("  - hours: ").append(hours).append("\n    new-player: {items: [{item: ")
                .append(hours == 9 ? "DIAMOND" : "IRON_INGOT").append("}]}\n");
        }
        String config = ReferralFixture.CONFIG;
        config = config.substring(0, config.indexOf("milestones:")) + milestones + config.substring(config.indexOf("rare-track:"));
        fixture.writeReferral(config);
        referrals.reload();
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        link(player, inviter);
        data(player).setMinutes(9 * 60);
        gui.open(player);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, top(player).getItem(ReferralGui.MILESTONE_PREVIOUS_SLOT).getType());
        assertEquals(Material.ARROW, top(player).getItem(ReferralGui.MILESTONE_NEXT_SLOT).getType());
        assertEquals(Material.IRON_INGOT, top(player).getItem(16).getType(), "the first seven fill the row");
        assertTrue(name(top(player).getItem(10)).contains("1h"));

        click(player, ReferralGui.MILESTONE_NEXT_SLOT);
        assertEquals(1, marker(player).milestonePage());
        assertTrue(name(top(player).getItem(10)).contains("8h"));
        assertEquals(Material.DIAMOND, top(player).getItem(11).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, top(player).getItem(12).getType(), "the last page is not padded");
        assertEquals(Material.ARROW, top(player).getItem(ReferralGui.MILESTONE_PREVIOUS_SLOT).getType());
        click(player, ReferralGui.MILESTONE_NEXT_SLOT);
        assertEquals(1, marker(player).milestonePage(), "no arrow on the last page");

        click(player, 11);
        assertTrue(player.getInventory().contains(Material.DIAMOND, 1));
        assertEquals(1, marker(player).milestonePage(), "a claim keeps the row where it was");
        assertTrue(name(top(player).getItem(11)).contains("✔"));
        assertTrue(lore(top(player).getItem(ReferralGui.RULES_SLOT)).stream().anyMatch(line -> line.contains("9h")));

        click(player, ReferralGui.MILESTONE_PREVIOUS_SLOT);
        assertEquals(0, marker(player).milestonePage());
        click(player, ReferralGui.MILESTONE_PREVIOUS_SLOT);
        assertEquals(0, marker(player).milestonePage());
        player.openInventory(gui.build(player, 0, 99));
        assertEquals(1, marker(player).milestonePage(), "a page past the end shows the last");
    }

    @Test
    void rewardLoreListsItemsCommandsAndPools() {
        Reward items = new Reward("Kit", null, null, new RewardEntry(1, "", List.of(),
            List.of(new RewardEntry.Item("IRON_INGOT", 3))));
        assertEquals(2, gui.rewardLore(items).size());
        Reward command = new Reward("Cmd", null, null, new RewardEntry(1, "A title", List.of("say hi"), List.of()));
        assertTrue(gui.rewardLore(command).stream().anyMatch(line -> line.contains("A title")));
        Reward silent = new Reward("Cmd", null, null, new RewardEntry(1, "", List.of("say hi"), List.of()));
        assertEquals(1, gui.rewardLore(silent).size());
        net.tfminecraft.activitytf.managers.TestManagers.pool(fixture.manager, "pool_two", List.of(
            new RewardEntry(1, "&aOne", List.of(), List.of(new RewardEntry.Item("DIAMOND", 1))),
            new RewardEntry(1, "&aOther", List.of(), List.of(new RewardEntry.Item("GOLD_INGOT", 2)))));
        List<String> options = gui.rewardLore(new Reward("Two", null, "pool_two", null));
        assertTrue(options.get(0).contains("One random reward"), String.valueOf(options));
        assertEquals(3, options.size());
        Reward empty = new Reward("Pool", null, "pool_nothing", null);
        assertTrue(gui.rewardLore(empty).get(0).contains("unavailable"));

        assertEquals(Material.CHEST, gui.rewardIcon(empty).getType());
        assertEquals(Material.PAPER, gui.rewardIcon(silent).getType());
        assertEquals(Material.PAPER, gui.rewardIcon(new Reward("x", "m.missing.item", null, null)).getType());
        ItemStack icon = gui.rewardIcon(items);
        assertEquals(Material.IRON_INGOT, icon.getType());
        assertEquals(1, icon.getAmount());
    }

    @Test
    void durationsReadAsHoursAndMinutes() {
        assertEquals("0m", ReferralGui.duration(-5));
        assertEquals("59m", ReferralGui.duration(59));
        assertEquals("1h", ReferralGui.duration(60));
        assertEquals("2h 5m", ReferralGui.duration(125));
        assertArrayEquals(new int[] {10, 12, 14, 16}, ReferralGui.milestoneSlots(4));
        assertArrayEquals(new int[] {13}, ReferralGui.milestoneSlots(1));
        assertArrayEquals(new int[] {11, 12, 13, 14, 15}, ReferralGui.milestoneSlots(5));
        assertArrayEquals(new int[] {10, 11, 12, 13, 14, 15, 16}, ReferralGui.milestoneSlots(7));
        assertEquals(10, ReferralGui.rareStepSlot(0));
        assertEquals(16, ReferralGui.rareStepSlot(6));
        assertEquals(19, ReferralGui.rareStepSlot(7));
        assertEquals(43, ReferralGui.rareStepSlot(27));
        assertEquals("referral.link.inviter-busy", ReferralGui.linkKey(Link.INVITER_BUSY));
    }

    @Test
    void clicksOutsideTheMenuOrByStrangersAreIgnoredAndDragsAreBlocked() {
        TestPlayer player = fixture.join("Newbie", "10.0.0.1");
        gui.open(player);
        InventoryClickEvent outside = new InventoryClickEvent(player.getOpenInventory(),
            InventoryType.SlotType.CONTAINER, ReferralGui.SIZE + 3, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        gui.onClick(outside);
        assertTrue(outside.isCancelled());
        assertEquals(ReferralGui.View.MAIN, marker(player).view());

        player.permissions.setPermission("activity.referral", false);
        assertTrue(click(player, ReferralGui.INVITER_SLOT).isCancelled());
        assertEquals(ReferralGui.View.MAIN, marker(player).view());

        InventoryDragEvent drag = new InventoryDragEvent(player.getOpenInventory(), null,
            new ItemStack(Material.STONE), false, Map.of(3, new ItemStack(Material.STONE)));
        gui.onDrag(drag);
        assertTrue(drag.isCancelled());
        InventoryDragEvent below = new InventoryDragEvent(player.getOpenInventory(), null,
            new ItemStack(Material.STONE), false, Map.of(ReferralGui.SIZE + 1, new ItemStack(Material.STONE)));
        gui.onDrag(below);
        assertFalse(below.isCancelled());

        player.closeInventory();
        Inventory chest = fixture.server.createInventory(null, 9);
        player.openInventory(chest);
        InventoryClickEvent other = new InventoryClickEvent(player.getOpenInventory(),
            InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        gui.onClick(other);
        assertFalse(other.isCancelled());
        InventoryDragEvent otherDrag = new InventoryDragEvent(player.getOpenInventory(), null,
            new ItemStack(Material.STONE), false, Map.of(0, new ItemStack(Material.STONE)));
        gui.onDrag(otherDrag);
        assertFalse(otherDrag.isCancelled());
    }

    @Test
    void theActivityMenuGetsAReferralButton() {
        TestPlayer player = fixture.join("Newbie", "10.0.0.1");
        ActivityGui activity = new ActivityGui(fixture.manager);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, activity.build(player).getItem(22).getType(),
            "no button without an opener");
        List<PlayerMock> opened = new ArrayList<>();
        activity.setReferralOpener(p -> opened.add((PlayerMock) p));
        player.openInventory(activity.build(player));
        assertEquals(Material.PLAYER_HEAD, top(player).getItem(22).getType());
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(),
            InventoryType.SlotType.CONTAINER, 22, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        activity.onClick(event);
        fixture.server.getScheduler().performOneTick();
        assertEquals(List.of(player), opened);

        activity.open(player);
        assertInstanceOf(ActivityGui.Marker.class, top(player).getHolder());

        player.permissions.setPermission("activity.referral", false);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, activity.build(player).getItem(22).getType());
        player.openInventory(activity.build(player));
        activity.onClick(new InventoryClickEvent(player.getOpenInventory(),
            InventoryType.SlotType.CONTAINER, 22, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        assertEquals(1, opened.size());
    }

    @Test
    void anOfflineHeadIsStillAHead() {
        TestPlayer player = fixture.join("Newbie", "10.0.0.1");
        player.openInventory(gui.buildConfirm(UUID.randomUUID()));
        assertEquals(Material.PLAYER_HEAD, top(player).getItem(ReferralGui.CONFIRM_HEAD_SLOT).getType());
    }
}
