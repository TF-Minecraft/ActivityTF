package net.tfminecraft.activitytf.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.activitytf.gui.ReferralGui;
import net.tfminecraft.activitytf.managers.ReferralFixture;
import net.tfminecraft.activitytf.managers.ReferralFixture.TestPlayer;
import net.tfminecraft.activitytf.managers.ReferralManager;
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
        run(player, "referral help");
        assertTrue(said(player).stream().anyMatch(line -> line.contains("Usage")), "players get their own usage");
    }

    @Test
    void staffAreToldWhenAChangeCannotBeSaved() throws Exception {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        assertEquals(Link.LINKED, fixture.referrals.link(player, inviter.getUniqueId()));
        var console = fixture.server.getConsoleSender();
        java.nio.file.Path tmp = fixture.plugin.getDataFolder().toPath().resolve("referrals.yml.tmp");
        java.nio.file.Files.createDirectories(tmp);
        java.nio.file.Files.writeString(tmp.resolve("keep"), "x");
        run(console, "referral settime Newbie 300");
        assertTrue(saidLine(console, "could not be saved"));
        run(console, "referral hold Newbie");
        assertTrue(saidLine(console, "could not be saved"));
        run(console, "referral markclaimed Newbie all both");
        assertTrue(saidLine(console, "could not be saved"));
        run(console, "referral rare markclaimed Inviter all");
        assertTrue(saidLine(console, "could not be saved"));
        run(console, "referral unlink Newbie");
        assertTrue(saidLine(console, "could not be saved"));
        run(console, "referral allowip Newbie Inviter");
        assertTrue(saidLine(console, "could not be saved"));
        assertEquals(inviter.getUniqueId(), data(player).invitedBy());
        run(console, "referral link Newbie Newbie");
        said(console);
        java.nio.file.Files.delete(tmp.resolve("keep"));
        java.nio.file.Files.delete(tmp);
        assertEquals(0, data(player).minutes());
        assertTrue(fixture.logs.stream().anyMatch(line -> line.contains("action=settime") && line.contains("result=save-failed")));
        assertTrue(fixture.logs.stream().anyMatch(line -> line.contains("action=hold") && line.contains("result=save-failed")));
        assertTrue(fixture.logs.stream().anyMatch(line -> line.contains("action=unlink") && line.contains("result=save-failed")));
        assertTrue(fixture.logs.stream().anyMatch(line -> line.contains("action=allowip") && line.contains("result=save-failed")));
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
        assertTrue(said(console).stream().anyMatch(line -> line.contains("Newbie (0h, claimed [])")));
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
        assertEquals(List.of("invitedby", "help", "info", "link", "unlink", "addtime", "settime", "unclaim",
            "markclaimed", "rare", "hold", "release", "allowip"),
            command.onTabComplete(console, bukkit, "referral", new String[] {""}));
        assertEquals(List.of(), command.onTabComplete(console, bukkit, "referral", new String[] {"help", ""}));
        assertEquals(2, command.onTabComplete(console, bukkit, "referral", new String[] {"link", "a", ""}).size());
        assertEquals(List.of(), command.onTabComplete(console, bukkit, "referral", new String[] {"link", "a", "b", ""}));
        assertEquals(List.of("1", "3", "5", "all"),
            command.onTabComplete(console, bukkit, "referral", new String[] {"unclaim", "Newbie", ""}));
        assertEquals(List.of("new", "inviter", "both"),
            command.onTabComplete(console, bukkit, "referral", new String[] {"markclaimed", "Newbie", "1", ""}));
        assertEquals(List.of(), command.onTabComplete(console, bukkit, "referral", new String[] {"unclaim", "a", "1", "new", ""}));
        assertEquals(2, command.onTabComplete(console, bukkit, "referral", new String[] {"unclaim", ""}).size());
        assertEquals(List.of("unclaim", "markclaimed", "exclude", "include"),
            command.onTabComplete(console, bukkit, "referral", new String[] {"rare", ""}));
        assertEquals(2, command.onTabComplete(console, bukkit, "referral", new String[] {"rare", "exclude", ""}).size());
        assertEquals(List.of("1", "2", "all"),
            command.onTabComplete(console, bukkit, "referral", new String[] {"rare", "unclaim", "Inviter", ""}));
        assertEquals(List.of(), command.onTabComplete(console, bukkit, "referral", new String[] {"rare", "include", "Inviter", ""}));
        assertEquals(List.of(), command.onTabComplete(console, bukkit, "referral", new String[] {"rare", "unclaim", "a", "1", ""}));
        assertEquals(1, command.onTabComplete(console, bukkit, "referral", new String[] {"hold", "N"}).size());
        assertEquals(2, command.onTabComplete(console, bukkit, "referral", new String[] {"unlink", ""}).size());
        assertEquals(2, command.onTabComplete(console, bukkit, "referral", new String[] {"allowip", "a", ""}).size());
        assertEquals(List.of(), command.onTabComplete(player, bukkit, "referral", new String[] {"allowip", "a", ""}));
        assertEquals(List.of(), command.onTabComplete(console, bukkit, "referral", new String[] {"info", "a", ""}));
    }

    private boolean saidLine(org.bukkit.command.CommandSender sender, String text) {
        return said(sender).stream().anyMatch(line -> line.replaceAll("§.", "").contains(text));
    }

    @Test
    void staffHelpListsEveryCommand() {
        var console = fixture.server.getConsoleSender();
        run(console, "referral help");
        List<String> lines = said(console);
        assertTrue(lines.size() >= 10, "help: " + lines);
        for (String sub : List.of("info", "link", "unlink", "addtime", "settime", "unclaim", "markclaimed",
            "rare unclaim", "rare exclude", "hold", "allowip")) {
            assertTrue(lines.stream().anyMatch(line -> line.contains("/referral " + sub)), sub);
        }
    }

    @Test
    void staffLinkAndSetTime() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        var console = fixture.server.getConsoleSender();
        run(console, "referral link Newbie Inviter");
        assertTrue(saidLine(console, "Linked Newbie to Inviter"));
        assertEquals(inviter.getUniqueId(), data(player).invitedBy());
        run(console, "referral link Newbie Inviter");
        assertTrue(saidLine(console, "already linked"));
        run(console, "referral link Newbie Newbie");
        assertTrue(saidLine(console, "can't invite itself"));
        run(console, "referral link Inviter Newbie");
        assertTrue(saidLine(console, "loop"));
        run(console, "referral link Newbie nobody");
        assertTrue(saidLine(console, "Nobody called nobody"));
        run(console, "referral link nobody Inviter");
        assertTrue(saidLine(console, "Nobody called nobody"));
        run(console, "referral link Newbie");
        assertTrue(saidLine(console, "Staff"));
        assertTrue(fixture.logs.stream().anyMatch(line -> line.contains("action=staff-link") && line.contains("result=loop")));

        run(console, "referral settime Newbie 300");
        assertEquals(300, data(player).minutes());
        assertEquals(inviter.getUniqueId(), data(player).rareReachedWith());
        run(console, "referral settime Newbie 0");
        assertEquals(0, data(player).minutes());
        said(console);
        run(console, "referral settime Newbie -1");
        run(console, "referral settime Newbie lots");
        run(console, "referral settime Newbie 1000001");
        assertEquals(3, said(console).stream().filter(line -> line.contains("Staff")).count());
        assertEquals(0, data(player).minutes());
        run(console, "referral settime nobody 5");
        assertTrue(saidLine(console, "Nobody called"));
        assertTrue(fixture.logs.stream().anyMatch(line -> line.contains("action=settime") && line.contains("300->0")));
    }

    @Test
    void staffUnclaimAndMarkMilestones() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        assertEquals(Link.LINKED, fixture.referrals.link(player, inviter.getUniqueId()));
        var console = fixture.server.getConsoleSender();
        run(console, "referral markclaimed Newbie all both");
        assertTrue(saidLine(console, "5 milestone claim(s) marked (all, both)"));
        assertEquals(java.util.Set.of(1, 3, 5), data(player).claimed());
        assertEquals(java.util.Set.of(1, 5), data(player).inviterClaimed());
        run(console, "referral unclaim Newbie 1 inviter");
        assertTrue(saidLine(console, "1 claim(s) removed (1h, inviter)"));
        assertEquals(java.util.Set.of(5), data(player).inviterClaimed());
        run(console, "referral unclaim Newbie all new");
        assertTrue(data(player).claimed().isEmpty());
        run(console, "referral markclaimed Newbie 7 new");
        assertTrue(saidLine(console, "no 7h milestone"));
        said(console);
        run(console, "referral unclaim Newbie 1 everyone");
        run(console, "referral unclaim Newbie soon new");
        run(console, "referral unclaim Newbie -3 new");
        run(console, "referral unclaim Newbie 1");
        assertEquals(4, said(console).stream().filter(line -> line.contains("Staff")).count());
        run(console, "referral unclaim nobody 1 new");
        assertTrue(saidLine(console, "Nobody called"));
        assertTrue(fixture.logs.stream().anyMatch(line -> line.contains("action=markclaimed")
            && line.contains("milestone=all") && line.contains("changed=5")));
    }

    @Test
    void staffRareStepsAndExclusions() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        assertEquals(Link.LINKED, fixture.referrals.link(player, inviter.getUniqueId()));
        var console = fixture.server.getConsoleSender();
        run(console, "referral rare markclaimed Inviter 2");
        assertTrue(saidLine(console, "1 rare-track step(s) marked (2)"));
        run(console, "referral rare markclaimed Inviter 3");
        assertTrue(saidLine(console, "no rare-track step for 3 players"));
        run(console, "referral rare unclaim Inviter all");
        assertTrue(saidLine(console, "1 rare-track step(s) and ties removed (all)"));
        assertTrue(data(inviter).rareClaimed().isEmpty());
        said(console);
        run(console, "referral rare unclaim Inviter none");
        run(console, "referral rare unclaim Inviter");
        run(console, "referral rare");
        run(console, "referral rare shuffle Inviter 1");
        run(console, "referral rare exclude");
        assertEquals(5, said(console).stream().filter(line -> line.contains("Staff")).count());

        run(console, "referral rare exclude Newbie");
        assertTrue(saidLine(console, "no longer counts"));
        assertTrue(data(player).rareExcluded());
        run(console, "referral rare exclude Newbie");
        assertTrue(saidLine(console, "nothing changed"));
        run(console, "referral info Newbie");
        assertTrue(saidLine(console, "flags: rare-excluded"));
        run(console, "referral rare include Newbie");
        assertTrue(saidLine(console, "counts on rare tracks again"));
        run(console, "referral rare include nobody");
        assertTrue(saidLine(console, "Nobody called"));
        run(console, "referral rare unclaim nobody all");
        assertTrue(saidLine(console, "Nobody called"));
        assertTrue(fixture.logs.stream().anyMatch(line -> line.contains("action=rare-exclude")));
    }

    @Test
    void staffHoldAndRelease() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer player = fixture.join("Newbie", "10.0.0.2");
        assertEquals(Link.LINKED, fixture.referrals.link(player, inviter.getUniqueId()));
        data(player).setMinutes(300);
        var console = fixture.server.getConsoleSender();
        run(console, "referral hold Newbie");
        assertTrue(saidLine(console, "Newbie is on hold"));
        run(console, "referral hold Newbie");
        assertTrue(saidLine(console, "nothing changed"));
        run(console, "referral info Inviter");
        assertTrue(saidLine(console, "rare track: 0 (-)"));
        run(console, "referral info Newbie");
        assertTrue(saidLine(console, "flags: held"));
        assertEquals(ReferralManager.Claim.HELD, fixture.referrals.claimOwn(player, 1));
        run(console, "referral release Newbie");
        assertTrue(saidLine(console, "Newbie is released"));
        run(console, "referral release Newbie");
        assertTrue(saidLine(console, "nothing changed"));
        run(console, "referral info Inviter");
        assertTrue(saidLine(console, "rare track: 1 (Newbie)"));
        run(console, "referral hold");
        assertTrue(saidLine(console, "Staff"));
        assertTrue(fixture.logs.stream().anyMatch(line -> line.contains("action=release") && line.contains("result=unchanged")));
    }
}
