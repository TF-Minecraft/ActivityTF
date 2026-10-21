package net.tfminecraft.activitytf.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.activitytf.gui.ReferralGui;
import net.tfminecraft.activitytf.managers.ReferralFixture;
import net.tfminecraft.activitytf.managers.ReferralFixture.TestPlayer;
import net.tfminecraft.activitytf.managers.ReferralManager.Link;
import net.tfminecraft.activitytf.models.ReferralData;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferralCommandTest {

    private ReferralFixture fixture;

    @BeforeEach
    void start() throws Exception {
        fixture = ReferralFixture.start();
    }

    @AfterEach
    void stop() throws Exception {
        fixture.stop();
    }

    private static List<String> said(org.bukkit.command.CommandSender sender) {
        List<String> lines = new ArrayList<>();
        if (sender instanceof PlayerMock player) {
            for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
                lines.add(line);
            }
        } else {
            ConsoleCommandSenderMock console = (ConsoleCommandSenderMock) sender;
            for (String line = console.nextMessage(); line != null; line = console.nextMessage()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private boolean run(org.bukkit.command.CommandSender sender, String line) {
        return fixture.server.dispatchCommand(sender, line);
    }

    private ReferralData data(PlayerMock player) {
        return fixture.referrals.getStore().peek(player.getUniqueId());
    }

    @Test
    void playersOpenTheMenuAndTypeTheirInviter() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        assertTrue(run(player, "referral"));
        assertInstanceOf(ReferralGui.Marker.class, player.getOpenInventory().getTopInventory().getHolder());
        assertTrue(run(player, "referrals invitedby Inviter"));
        assertInstanceOf(ReferralGui.Marker.class, player.getOpenInventory().getTopInventory().getHolder());
        assertEquals(27, player.getOpenInventory().getTopInventory().getSize());
        said(player);
        run(player, "referral nonsense");
        assertTrue(said(player).stream().anyMatch(line -> line.contains("Usage")));
        run(player, "referral invitedby");
        assertTrue(said(player).stream().noneMatch(line -> line.contains("Staff")));
        player.setOp(true);
        run(player, "referral nonsense");
        assertTrue(said(player).stream().anyMatch(line -> line.contains("Staff")));
        assertNull(data(player).invitedBy());
        assertEquals(Link.LINKED, fixture.referrals.link(player, inviter.getUniqueId()));
    }

    @Test
    void theConsoleAndPlayersWithoutPermissionAreTurnedAway() {
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        var console = fixture.server.getConsoleSender();
        run(console, "referral");
        assertTrue(said(console).stream().anyMatch(line -> line.contains("Players only")));
        player.permissions.setPermission("activity.referral", false);
        said(player);
        run(player, "referral");
        assertTrue(said(player).stream().anyMatch(line -> line.contains("No permission")));
        run(player, "referral info Newbie");
        assertTrue(said(player).stream().anyMatch(line -> line.contains("No permission")));
    }

    @Test
    void staffCanInspectUnlinkAndAdjustPlaytime() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        assertEquals(Link.LINKED, fixture.referrals.link(player, inviter.getUniqueId()));
        var console = fixture.server.getConsoleSender();

        run(console, "referral info Newbie");
        assertTrue(said(console).stream().anyMatch(line -> line.contains("Newbie") && line.contains("Inviter")
            && line.replaceAll("§.", "").contains("counted for: -")));
        fixture.referrals.getStore().peek(player.getUniqueId()).setRareCountedFor(inviter.getUniqueId());
        run(console, "referral info Newbie");
        assertTrue(said(console).stream().anyMatch(line -> line.replaceAll("§.", "").contains("counted for: Inviter")));
        run(console, "referral info Inviter");
        assertTrue(said(console).stream().anyMatch(line -> line.contains("Newbie (0h)")));
        run(console, "referral info nobody");
        assertTrue(said(console).stream().anyMatch(line -> line.contains("Nobody called")));
        run(console, "referral info");
        assertTrue(said(console).stream().anyMatch(line -> line.contains("Staff")));

        run(console, "referral addtime Newbie 90");
        assertEquals(90, data(player).minutes());
        run(console, "referral addtime Newbie -200");
        assertEquals(0, data(player).minutes());
        said(console);
        run(console, "referral addtime Newbie lots");
        run(console, "referral addtime Newbie 0");
        run(console, "referral addtime Newbie 100001");
        assertEquals(3, said(console).stream().filter(line -> line.contains("Staff")).count());
        run(console, "referral addtime nobody 5");
        run(console, "referral addtime Newbie 300");
        assertEquals(inviter.getUniqueId(), data(player).rareReachedWith(), "added time past the goal is noted");

        run(console, "referral unlink Newbie");
        assertNull(data(player).invitedBy());
        assertNull(data(player).rareReachedWith());
        said(console);
        run(console, "referral unlink Newbie");
        assertTrue(said(console).stream().anyMatch(line -> line.contains("not linked")));
        run(console, "referral unlink nobody");
        said(console);
        run(console, "referral allowip Newbie Inviter");
        assertTrue(said(console).stream().anyMatch(line -> line.contains("may now link")));
        assertTrue(data(player).ipPartners().contains(inviter.getUniqueId()));
        run(console, "referral allowip Newbie Inviter");
        assertTrue(said(console).stream().anyMatch(line -> line.contains("held to the same-connection check")));
        run(console, "referral allowip nobody Inviter");
        run(console, "referral allowip Newbie nobody");
        assertTrue(said(console).stream().filter(line -> line.contains("Nobody called")).count() == 2);
        run(console, "referral allowip Newbie Newbie");
        assertTrue(said(console).stream().anyMatch(line -> line.contains("Staff")));
        run(console, "referral allowip Newbie");
        assertTrue(said(console).stream().anyMatch(line -> line.contains("Staff")));
        assertTrue(fixture.logs.stream().anyMatch(line -> line.contains("REFERRAL-AUDIT") && line.contains("action=unlink")));
    }

    @Test
    void tabCompletionOffersSubcommandsAndVisibleNames() {
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        fixture.join("Inviter", "10.0.0.1");
        ReferralCommand command = (ReferralCommand) fixture.plugin.getCommand("referral").getExecutor();
        var bukkit = fixture.plugin.getCommand("referral");
        assertEquals(List.of("invitedby"), command.onTabComplete(player, bukkit, "referral", new String[] {""}));
        assertEquals(List.of("Inviter"), command.onTabComplete(player, bukkit, "referral", new String[] {"invitedby", "inv"}));
        assertEquals(List.of(), command.onTabComplete(player, bukkit, "referral", new String[] {"info", ""}));
        assertEquals(List.of(), command.onTabComplete(player, bukkit, "referral", new String[] {"invitedby", "x", ""}));
        assertEquals(List.of(), command.onTabComplete(player, bukkit, "referral", new String[] {}));
        var console = fixture.server.getConsoleSender();
        assertEquals(List.of("invitedby", "info", "unlink", "addtime", "allowip"),
            command.onTabComplete(console, bukkit, "referral", new String[] {""}));
        assertEquals(2, command.onTabComplete(console, bukkit, "referral", new String[] {"unlink", ""}).size());
        assertEquals(2, command.onTabComplete(console, bukkit, "referral", new String[] {"allowip", "a", ""}).size());
        assertEquals(List.of(), command.onTabComplete(player, bukkit, "referral", new String[] {"allowip", "a", ""}));
        assertEquals(List.of(), command.onTabComplete(console, bukkit, "referral", new String[] {"info", "a", ""}));
    }
}
