package net.tfminecraft.activitytf.managers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.activitytf.managers.ReferralFixture.TestPlayer;
import net.tfminecraft.activitytf.managers.ReferralManager.Claim;
import net.tfminecraft.activitytf.managers.ReferralManager.Link;
import net.tfminecraft.activitytf.managers.ReferralManager.State;
import net.tfminecraft.activitytf.models.ReferralData;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferralManagerTest {

    private ReferralFixture fixture;
    private ReferralManager referrals;

    @BeforeEach
    void start() throws Exception {
        fixture = ReferralFixture.start();
        referrals = fixture.referrals;
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

    private ReferralData data(PlayerMock player) {
        return referrals.getStore().peek(player.getUniqueId());
    }

    private void linked(TestPlayer invitee, TestPlayer inviter) {
        assertEquals(Link.LINKED, referrals.link(invitee, inviter.getUniqueId()));
    }

    private void tmpBlocked(boolean blocked) throws Exception {
        Path tmp = fixture.plugin.getDataFolder().toPath().resolve("referrals.yml.tmp");
        if (blocked) {
            Files.createDirectories(tmp);
            Files.writeString(tmp.resolve("keep"), "x");
        } else {
            Files.delete(tmp.resolve("keep"));
            Files.delete(tmp);
        }
    }

    @Test
    void aMinuteCountsOnlyForSomeoneWhoMovedOrActedRecently() {
        TestPlayer player = fixture.join("Alice", "10.0.0.1");
        fixture.tick();
        assertEquals(0, data(player).minutes(), "joining alone is not activity");
        referrals.noteActive(player.getUniqueId());
        fixture.tick();
        assertEquals(1, data(player).minutes());

        fixture.clock.addAndGet(Duration.ofMinutes(5).toMillis());
        fixture.tick();
        assertEquals(1, data(player).minutes(), "standing still for afk-minutes stops the count");

        referrals.noteActive(player.getUniqueId());
        fixture.tick();
        assertEquals(2, data(player).minutes(), "chat or a command counts as being there");

        fixture.clock.addAndGet(Duration.ofMinutes(6).toMillis());
        player.setLocation(player.getLocation().add(4, 0, 0));
        fixture.tick();
        assertEquals(3, data(player).minutes(), "walking counts as being there");

        player.idle = Duration.ofMinutes(5);
        fixture.tick();
        assertEquals(3, data(player).minutes(), "a client that went silent does not count");

        player.idle = Duration.ZERO;
        referrals.onQuit(player.getUniqueId());
        fixture.tick();
        assertEquals(3, data(player).minutes(), "the first sample without an anchor only sets it");
    }

    @Test
    void aSmallStepOrAnotherWorldIsHandledAsTheMovementAnchor() {
        TestPlayer player = fixture.join("Alice", "10.0.0.1");
        fixture.clock.addAndGet(Duration.ofMinutes(6).toMillis());
        player.setLocation(player.getLocation().add(1, 0, 0));
        fixture.tick();
        assertEquals(0, data(player).minutes(), "a one-block shuffle is not movement");

        player.setLocation(new Location(fixture.server.getWorld("character_selection"), 0, 64, 0));
        fixture.tick();
        assertEquals(0, data(player).minutes(), "nothing counts in an ignored world");
        assertFalse(fixture.counts(player));
    }

    @Test
    void nothingCountsWhenDisabledOrBeforeTheSeason() throws Exception {
        TestPlayer player = fixture.join("Alice", "10.0.0.1");
        referrals.noteActive(player.getUniqueId());
        fixture.writeReferral(ReferralFixture.CONFIG.replace("enabled: true", "enabled: false"));
        referrals.reload();
        assertFalse(fixture.counts(player));

        fixture.writeReferral(ReferralFixture.CONFIG.replace("2026-10-15", "2026-10-25"));
        referrals.reload();
        assertFalse(fixture.counts(player), "before season.start nothing counts");

        fixture.writeReferral(ReferralFixture.CONFIG.replace("start: \"2026-10-15\"", "start: \"\""));
        referrals.reload();
        assertTrue(fixture.counts(player), "no season start counts all the time");
        referrals.onQuit(player.getUniqueId());
        assertFalse(fixture.counts(player), "no recent activity known after quitting");
    }

    @Test
    void joiningRecordsTheNameAHashedConnectionAndTheEarliestFirstJoin() {
        TestPlayer player = fixture.join("Alice", "10.0.0.1");
        ReferralData data = data(player);
        assertEquals("Alice", data.name());
        assertEquals(1, data.ips().size());
        assertFalse(data.ips().iterator().next().contains("10.0.0.1"));
        assertEquals(fixture.clock.get(), data.firstSeen());

        player.firstPlayed = fixture.clock.get() - 1000;
        referrals.onJoin(player);
        assertEquals(fixture.clock.get() - 1000, data.firstSeen());
        player.firstPlayed = fixture.clock.get() + 1000;
        referrals.onJoin(player);
        assertEquals(fixture.clock.get() - 1000, data.firstSeen(), "a later first join never moves it forward");

        TestPlayer noFirst = fixture.join("Bob", null);
        noFirst.address = null;
        noFirst.firstPlayed = 0;
        data(noFirst).ips().clear();
        referrals.getStore().peek(noFirst.getUniqueId()).setFirstSeen(0);
        referrals.onJoin(noFirst);
        assertEquals(fixture.clock.get(), data(noFirst).firstSeen());
        assertTrue(data(noFirst).ips().isEmpty());
    }

    @Test
    void joiningWithSomethingToClaimSaysSo() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer invitee = fixture.join("Invitee", "10.0.0.2");
        linked(invitee, inviter);
        data(invitee).setMinutes(60);
        said(invitee);
        said(inviter);
        referrals.onJoin(invitee);
        assertTrue(said(invitee).stream().anyMatch(line -> line.contains("REFERRAL REWARD READY")));
        referrals.onJoin(inviter);
        assertTrue(said(inviter).stream().anyMatch(line -> line.contains("REFERRAL REWARD READY")));

        data(invitee).claimed().add(1);
        data(invitee).inviterClaimed().add(1);
        referrals.onJoin(invitee);
        referrals.onJoin(inviter);
        assertTrue(said(invitee).isEmpty());
        assertTrue(said(inviter).isEmpty());
    }

    @Test
    void linkingIsOpenInTheSeasonsFirstDaysThenOnlyForNewAccounts() {
        TestPlayer old = fixture.join("Old", "10.0.0.1");
        old.firstPlayed = fixture.clock.get() - Duration.ofDays(60).toMillis();
        data(old).setFirstSeen(old.firstPlayed);
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.2");
        assertNull(referrals.linkable(old.getUniqueId()), "day 5 of the season: anyone");

        fixture.advanceDays(10);
        assertEquals(Link.CLOSED, referrals.linkable(old.getUniqueId()));
        assertEquals(Link.CLOSED, referrals.link(old, inviter.getUniqueId()));
        assertEquals(Link.CLOSED, referrals.linkable(UUID.randomUUID()));

        TestPlayer fresh = fixture.join("Fresh", "10.0.0.3");
        assertNull(referrals.linkable(fresh.getUniqueId()));
        fixture.advanceDays(7);
        assertEquals(Link.CLOSED, referrals.linkable(fresh.getUniqueId()));
    }

    @Test
    void aLinkIsSavedOnceAndCanNeverBeChangedByThePlayer() throws Exception {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer invitee = fixture.join("Invitee", "10.0.0.2");
        TestPlayer other = fixture.join("Other", "10.0.0.3");
        linked(invitee, inviter);
        assertEquals(inviter.getUniqueId(), data(invitee).invitedBy());
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(
            new File(fixture.plugin.getDataFolder(), "referrals.yml"));
        assertEquals(inviter.getUniqueId().toString(),
            saved.getString("players." + invitee.getUniqueId() + ".invited-by"));
        assertEquals(Link.ALREADY_LINKED, referrals.link(invitee, other.getUniqueId()));
        assertTrue(fixture.logs.stream().anyMatch(line -> line.contains("REFERRAL-AUDIT") && line.contains("result=done")));
    }

    @Test
    void selfUnknownLoopsAndSharedConnectionsAreRefused() {
        TestPlayer a = fixture.join("Aa", "10.0.0.1");
        TestPlayer b = fixture.join("Bb", "10.0.0.2");
        TestPlayer c = fixture.join("Cc", "10.0.0.3");
        TestPlayer sameIp = fixture.join("Twin", "10.0.0.1");
        assertEquals(Link.SELF, referrals.link(a, a.getUniqueId()));
        assertEquals(Link.UNKNOWN, referrals.link(a, UUID.randomUUID()));
        assertEquals(Link.UNKNOWN, referrals.link(a, null));
        assertEquals(Link.SAME_IP, referrals.link(sameIp, a.getUniqueId()));

        linked(b, a);
        linked(c, b);
        assertEquals(Link.LOOP, referrals.link(a, b.getUniqueId()), "B was invited by A");
        assertEquals(Link.LOOP, referrals.link(a, c.getUniqueId()), "C was invited by B, who A invited");
        assertTrue(referrals.leadsTo(c.getUniqueId(), a.getUniqueId()));
        assertFalse(referrals.leadsTo(a.getUniqueId(), c.getUniqueId()));
    }

    @Test
    void theSameIpCheckCanBeTurnedOff() throws Exception {
        fixture.writeReferral(ReferralFixture.CONFIG.replace("same-ip-check: true", "same-ip-check: false"));
        referrals.reload();
        TestPlayer a = fixture.join("Aa", "10.0.0.1");
        TestPlayer twin = fixture.join("Twin", "10.0.0.1");
        linked(twin, a);
        data(twin).setMinutes(60);
        twin.setLocation(new Location(fixture.server.getWorld("world"), 0, 64, 0));
        assertEquals(Claim.CLAIMED, referrals.claimOwn(twin, 1));
    }

    @Test
    void anInviterCanBeCappedPerSeasonAndPerDay() throws Exception {
        fixture.writeReferral(ReferralFixture.CONFIG.replace("max-links-per-day: 0", "max-links-per-day: 1"));
        referrals.reload();
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        linked(fixture.join("One", "10.0.0.2"), inviter);
        TestPlayer two = fixture.join("Two", "10.0.0.3");
        assertEquals(Link.INVITER_BUSY, referrals.link(two, inviter.getUniqueId()));
        fixture.advanceDays(1);
        two.firstPlayed = fixture.clock.get();
        linked(two, inviter);

        fixture.writeReferral(ReferralFixture.CONFIG.replace("max-invitees: 0", "max-invitees: 2"));
        referrals.reload();
        TestPlayer three = fixture.join("Three", "10.0.0.4");
        assertEquals(Link.INVITER_FULL, referrals.link(three, inviter.getUniqueId()));
        assertEquals(List.of(fixture.server.getPlayerExact("One").getUniqueId(), two.getUniqueId()),
            referrals.invitees(inviter.getUniqueId()));
    }

    @Test
    void linkingIsRefusedWhenOffOrWhenItCannotBeSaved() throws Exception {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer invitee = fixture.join("Invitee", "10.0.0.2");
        tmpBlocked(true);
        assertEquals(Link.FAILED, referrals.link(invitee, inviter.getUniqueId()));
        assertNull(data(invitee).invitedBy(), "a failed save leaves no link behind");
        tmpBlocked(false);

        fixture.writeReferral(ReferralFixture.CONFIG.replace("enabled: true", "enabled: false"));
        referrals.reload();
        assertEquals(Link.DISABLED, referrals.link(invitee, inviter.getUniqueId()));
    }

    @Test
    void ownMilestonesPayOnceOnTheCharacterAfterEnoughPlaytime() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer invitee = fixture.join("Invitee", "10.0.0.2");
        assertEquals(Claim.NOT_LINKED, referrals.claimOwn(invitee, 1));
        linked(invitee, inviter);
        assertEquals(Claim.LOCKED, referrals.claimOwn(invitee, 1));
        data(invitee).setMinutes(60);
        assertEquals(Claim.NO_REWARD, referrals.claimOwn(invitee, 2));

        invitee.setLocation(new Location(fixture.server.getWorld("character_selection"), 0, 64, 0));
        assertEquals(Claim.WRONG_WORLD, referrals.claimOwn(invitee, 1));
        invitee.setLocation(new Location(fixture.server.getWorld("world"), 0, 64, 0));

        said(invitee);
        assertEquals(Claim.CLAIMED, referrals.claimOwn(invitee, 1));
        assertTrue(invitee.getInventory().contains(Material.BREAD, 4));
        assertTrue(said(invitee).stream().anyMatch(line -> line.contains("Starter kit")));
        assertEquals(Claim.ALREADY_CLAIMED, referrals.claimOwn(invitee, 1));
        assertEquals(State.CLAIMED, referrals.ownState(data(invitee), ReferralManager.milestone(referrals.act(), 1)));

        data(invitee).setMinutes(300);
        assertEquals(Claim.CLAIMED, referrals.claimOwn(invitee, 5));
        assertTrue(invitee.getInventory().contains(Material.EMERALD, 2));
        assertTrue(said(invitee).stream().anyMatch(line -> line.contains("Rare emerald")));
    }

    @Test
    void claimsAreHeldWhenTheAccountsLaterShareAConnection() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer invitee = fixture.join("Invitee", "10.0.0.2");
        linked(invitee, inviter);
        data(invitee).setMinutes(60);
        data(invitee).addIp(data(inviter).ips().iterator().next());
        assertEquals(Claim.SAME_IP, referrals.claimOwn(invitee, 1));
        assertEquals(Claim.SAME_IP, referrals.claimForInvitee(inviter, invitee.getUniqueId(), 1));
    }

    @Test
    void ownClaimsRefuseWhenOffUnsavedOrUnpaid() throws Exception {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer invitee = fixture.join("Invitee", "10.0.0.2");
        linked(invitee, inviter);
        data(invitee).setMinutes(60);

        tmpBlocked(true);
        assertEquals(Claim.FAILED, referrals.claimOwn(invitee, 1));
        assertFalse(data(invitee).claimed().contains(1), "a failed save takes the claim back");
        assertFalse(invitee.getInventory().contains(Material.BREAD));
        tmpBlocked(false);

        fixture.writeReferral(ReferralFixture.CONFIG.replace("- item: BREAD", "- item: m.missing.item"));
        referrals.reload();
        assertEquals(Claim.FAILED, referrals.claimOwn(invitee, 1));
        assertFalse(data(invitee).claimed().contains(1), "nothing handed over leaves it claimable");

        fixture.writeReferral(ReferralFixture.CONFIG.replace("enabled: true", "enabled: false"));
        referrals.reload();
        assertEquals(Claim.DISABLED, referrals.claimOwn(invitee, 1));
        assertEquals(Claim.DISABLED, referrals.claimForInvitee(inviter, invitee.getUniqueId(), 1));
    }

    @Test
    void anEmptyPoolLeavesTheMilestoneClaimableAndIsReportedOnce() throws Exception {
        fixture.writeReferral(ReferralFixture.CONFIG.replace("pool: pool_ref_rare", "pool: pool_missing"));
        referrals.reload();
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer invitee = fixture.join("Invitee", "10.0.0.2");
        linked(invitee, inviter);
        data(invitee).setMinutes(300);
        assertEquals(Claim.UNCONFIGURED, referrals.claimOwn(invitee, 5));
        assertEquals(Claim.UNCONFIGURED, referrals.claimOwn(invitee, 5));
        assertEquals(1, fixture.logs.stream().filter(line -> line.contains("pool_missing") && line.contains("stays claimable")).count());
    }

    @Test
    void inviterClaimsFollowEachInviteesPlaytime() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer first = fixture.join("First", "10.0.0.2");
        TestPlayer second = fixture.join("Second", "10.0.0.3");
        TestPlayer stranger = fixture.join("Stranger", "10.0.0.4");
        linked(first, inviter);
        linked(second, inviter);

        assertEquals(Claim.NOT_YOURS, referrals.claimForInvitee(stranger, first.getUniqueId(), 1));
        assertEquals(Claim.NOT_YOURS, referrals.claimForInvitee(inviter, UUID.randomUUID(), 1));
        assertEquals(Claim.NO_REWARD, referrals.claimForInvitee(inviter, first.getUniqueId(), 3));
        assertEquals(Claim.LOCKED, referrals.claimForInvitee(inviter, first.getUniqueId(), 1));
        assertTrue(referrals.claimAllForInvitee(inviter, first.getUniqueId()).isEmpty());

        data(first).setMinutes(300);
        data(second).setMinutes(300);
        assertEquals(List.of(Claim.CLAIMED, Claim.CLAIMED), referrals.claimAllForInvitee(inviter, first.getUniqueId()));
        assertTrue(inviter.getInventory().contains(Material.GOLD_NUGGET, 3));
        assertTrue(inviter.getInventory().contains(Material.EMERALD, 2));
        assertEquals(Claim.ALREADY_CLAIMED, referrals.claimForInvitee(inviter, first.getUniqueId(), 1));

        assertEquals(List.of(Claim.CLAIMED, Claim.CLAIMED), referrals.claimAllForInvitee(inviter, second.getUniqueId()),
            "every invitee pays their own milestones");
        assertEquals(State.CLAIMED, referrals.inviterState(data(second), ReferralManager.milestone(referrals.act(), 5)));
        assertTrue(referrals.claimAllForInvitee(inviter, UUID.randomUUID()).isEmpty());
    }

    @Test
    void aFailedInviterPayoutStaysClaimable() throws Exception {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer invitee = fixture.join("Invitee", "10.0.0.2");
        linked(invitee, inviter);
        data(invitee).setMinutes(300);
        data(invitee).inviterClaimed().add(1);
        tmpBlocked(true);
        assertEquals(List.of(Claim.FAILED), referrals.claimAllForInvitee(inviter, invitee.getUniqueId()));
        tmpBlocked(false);
        assertFalse(data(invitee).inviterClaimed().contains(5));
    }

    @Test
    void theRareTrackCountsInviteesWhoReachedTheLastMilestone() throws Exception {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer first = fixture.join("First", "10.0.0.2");
        TestPlayer second = fixture.join("Second", "10.0.0.3");
        linked(first, inviter);
        linked(second, inviter);
        assertEquals(List.of(), referrals.completedInvitees(inviter.getUniqueId()));
        assertEquals(Claim.TOO_FEW, referrals.claimRare(inviter, 1));
        assertEquals(Claim.NO_REWARD, referrals.claimRare(inviter, 9));
        assertFalse(referrals.hasClaimable(inviter.getUniqueId()));

        data(first).setMinutes(299);
        assertEquals(List.of(), referrals.completedInvitees(inviter.getUniqueId()), "one minute short of 5h");
        data(first).setMinutes(300);
        assertEquals(List.of(first.getUniqueId()), referrals.completedInvitees(inviter.getUniqueId()));
        data(first).inviterClaimed().addAll(List.of(1, 5));
        assertTrue(referrals.hasClaimable(inviter.getUniqueId()), "the 1-player step is ready");

        inviter.setLocation(new Location(fixture.server.getWorld("character_selection"), 0, 64, 0));
        assertEquals(Claim.WRONG_WORLD, referrals.claimRare(inviter, 1));
        inviter.setLocation(new Location(fixture.server.getWorld("world"), 0, 64, 0));
        tmpBlocked(true);
        assertEquals(Claim.FAILED, referrals.claimRare(inviter, 1));
        tmpBlocked(false);
        assertTrue(data(inviter).rareClaimed().isEmpty(), "a failed save takes the step back");
        assertNull(data(first).rareCountedFor(), "and frees the invitee again");

        assertEquals(Claim.CLAIMED, referrals.claimRare(inviter, 1));
        assertTrue(inviter.getInventory().contains(Material.EMERALD, 2));
        assertEquals(inviter.getUniqueId(), data(first).rareCountedFor());
        assertEquals(Claim.ALREADY_CLAIMED, referrals.claimRare(inviter, 1));
        assertEquals(Claim.TOO_FEW, referrals.claimRare(inviter, 2));
        assertFalse(referrals.hasClaimable(inviter.getUniqueId()));

        data(second).setMinutes(600);
        data(second).addIp(data(inviter).ips().iterator().next());
        assertEquals(1, referrals.completedInvitees(inviter.getUniqueId()).size(), "a shared connection does not count");
        assertEquals(Claim.TOO_FEW, referrals.claimRare(inviter, 2));
        referrals.toggleIpPair(inviter.getUniqueId(), second.getUniqueId());
        assertEquals(2, referrals.completedInvitees(inviter.getUniqueId()).size());
        assertEquals(Claim.CLAIMED, referrals.claimRare(inviter, 2));
        assertTrue(inviter.getInventory().contains(Material.DIAMOND, 1));
        assertEquals(State.CLAIMED, referrals.rareState(data(inviter), 2,
            ReferralManager.rareStep(referrals.act(), 2)));
        assertEquals(List.of(1, 2), List.copyOf(data(inviter).rareClaimed()));
        assertNull(ReferralManager.rareStep(null, 1));
        assertEquals(List.of(), referrals.completedInvitees(UUID.randomUUID()));
        assertTrue(fixture.logs.stream().anyMatch(line -> line.contains("at=\"referral rare 2 players\"")
            && line.contains("result=done")));
    }

    @Test
    void anInviteeCountsForOneRareTrackOnlyEvenAfterARelink() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer other = fixture.join("Other", "10.0.0.2");
        TestPlayer invitee = fixture.join("Invitee", "10.0.0.3");
        linked(invitee, inviter);
        data(invitee).setMinutes(300);
        assertEquals(Claim.CLAIMED, referrals.claimRare(inviter, 1));
        assertTrue(referrals.unlink(invitee.getUniqueId()));
        linked(invitee, other);
        assertEquals(List.of(), referrals.completedInvitees(other.getUniqueId()));
        assertEquals(Claim.TOO_FEW, referrals.claimRare(other, 1));
        assertTrue(referrals.unlink(invitee.getUniqueId()));
        linked(invitee, inviter);
        assertEquals(List.of(invitee.getUniqueId()), referrals.completedInvitees(inviter.getUniqueId()),
            "it still counts for the inviter it counted for");
    }

    @Test
    void aLaterMilestoneRaisesTheGoalOnlyForInviteesWhoHadNotReachedIt() throws Exception {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer counted = fixture.join("Counted", "10.0.0.2");
        TestPlayer waiting = fixture.join("Waiting", "10.0.0.3");
        linked(counted, inviter);
        linked(waiting, inviter);
        data(counted).setMinutes(300);
        data(waiting).setMinutes(299);
        fixture.tick();
        assertEquals(inviter.getUniqueId(), data(counted).rareReachedWith(), "reaching the goal notes the inviter");
        assertNull(data(counted).rareCountedFor(), "only a claim ties it for good");
        assertNull(data(waiting).rareReachedWith());
        data(waiting).setMinutes(300);

        fixture.writeReferral(ReferralFixture.CONFIG.replace("""
              - hours: 5
            """, """
              - hours: 9
                new-player: {items: [{item: DIAMOND}]}
              - hours: 5
            """));
        referrals.reload();
        assertEquals(9 * 60, referrals.getConfiguration().completionMinutes());
        assertEquals(List.of(counted.getUniqueId(), waiting.getUniqueId()), referrals.completedInvitees(inviter.getUniqueId()),
            "both had reached 5h before the reload, so both keep counting");

        TestPlayer late = fixture.join("Late", "10.0.0.4");
        linked(late, inviter);
        data(late).setMinutes(300);
        referrals.noteCompletions();
        assertNull(data(late).rareReachedWith(), "5h is no longer the goal");
        assertEquals(2, referrals.completedInvitees(inviter.getUniqueId()).size());
        data(late).setMinutes(9 * 60);
        fixture.tick();
        assertEquals(inviter.getUniqueId(), data(late).rareReachedWith());
        assertEquals(3, referrals.completedInvitees(inviter.getUniqueId()).size());
    }

    @Test
    void aStaffFixOfAWrongLinkMovesAnUnclaimedInviteeToTheRightInviter() throws Exception {
        TestPlayer wrong = fixture.join("Wrong", "10.0.0.1");
        TestPlayer right = fixture.join("Right", "10.0.0.2");
        TestPlayer invitee = fixture.join("Invitee", "10.0.0.3");
        data(invitee).setMinutes(300);
        linked(invitee, wrong);
        assertEquals(wrong.getUniqueId(), data(invitee).rareReachedWith());
        assertTrue(referrals.unlink(invitee.getUniqueId()));
        assertNull(data(invitee).rareReachedWith(), "unlinking clears it");
        linked(invitee, right);
        assertEquals(List.of(invitee.getUniqueId()), referrals.completedInvitees(right.getUniqueId()));
        assertEquals(right.getUniqueId(), data(invitee).rareReachedWith());

        fixture.writeReferral(ReferralFixture.CONFIG.replace("""
              - hours: 5
            """, """
              - hours: 9
                new-player: {items: [{item: DIAMOND}]}
              - hours: 5
            """));
        referrals.reload();
        assertEquals(List.of(invitee.getUniqueId()), referrals.completedInvitees(right.getUniqueId()),
            "it reached the old goal with this inviter");
    }

    @Test
    void linkingAnAccountAlreadyPastTheGoalNotesItAtOnce() {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer veteran = fixture.join("Veteran", "10.0.0.2");
        data(veteran).setMinutes(600);
        linked(veteran, inviter);
        assertEquals(inviter.getUniqueId(), data(veteran).rareReachedWith());
    }

    @Test
    void theRareTrackRefusesWhenOffOrUnreadable() throws Exception {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        fixture.writeReferral(ReferralFixture.CONFIG.replace("enabled: true", "enabled: false"));
        referrals.reload();
        assertEquals(Claim.DISABLED, referrals.claimRare(inviter, 1));
        fixture.writeReferral(ReferralFixture.CONFIG);
        referrals.reload();
        Files.writeString(fixture.plugin.getDataFolder().toPath().resolve("referrals.yml"), "players: [unclosed");
        referrals.getStore().load(referrals.getConfiguration().seasonKey());
        assertEquals(Claim.FAILED, referrals.claimRare(inviter, 1));
    }

    @Test
    void beforeTheFirstActNothingIsClaimable() throws Exception {
        fixture.writeReferral(ReferralFixture.CONFIG.replace("starts: \"2026-10-01\"", "starts: \"2026-11-01\""));
        referrals.reload();
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer invitee = fixture.join("Invitee", "10.0.0.2");
        linked(invitee, inviter);
        assertNull(referrals.act());
        assertFalse(referrals.hasClaimable(invitee.getUniqueId()));
        assertFalse(referrals.hasClaimable(UUID.randomUUID()));
        assertEquals(Claim.NO_REWARD, referrals.claimOwn(invitee, 1));
        assertTrue(referrals.claimAllForInvitee(inviter, invitee.getUniqueId()).isEmpty());
        assertNull(ReferralManager.milestone(null, 1));
    }

    @Test
    void claimsAreRefusedWhileTheStoreIsUnreadable() throws Exception {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer invitee = fixture.join("Invitee", "10.0.0.2");
        Files.writeString(fixture.plugin.getDataFolder().toPath().resolve("referrals.yml"), "players: [unclosed");
        referrals.getStore().load(referrals.getConfiguration().seasonKey());
        assertFalse(referrals.getStore().isLoaded());
        assertEquals(Claim.FAILED, referrals.claimOwn(invitee, 1));
        assertEquals(Claim.FAILED, referrals.claimForInvitee(inviter, invitee.getUniqueId(), 1));
        referrals.getStore().get(inviter.getUniqueId(), "Inviter");
        assertEquals(Link.FAILED, referrals.link(invitee, inviter.getUniqueId()));
    }

    @Test
    void namesUnlinkingAndANewSeason() throws Exception {
        TestPlayer inviter = fixture.join("Inviter", "10.0.0.1");
        TestPlayer invitee = fixture.join("Invitee", "10.0.0.2");
        assertEquals(inviter.getUniqueId(), referrals.find("inviter"));
        assertEquals(inviter.getUniqueId(), referrals.find("Inviter"));
        assertNull(referrals.find("nobody"));
        linked(invitee, inviter);
        assertTrue(referrals.unlink(invitee.getUniqueId()));
        assertFalse(referrals.unlink(invitee.getUniqueId()));
        assertFalse(referrals.unlink(UUID.randomUUID()));
        assertNull(referrals.linkable(invitee.getUniqueId()));

        linked(invitee, inviter);
        fixture.writeReferral(ReferralFixture.CONFIG.replace("2026-10-15", "2026-10-18"));
        referrals.reload();
        assertNull(data(invitee).invitedBy(), "a new season starts with no links");
        assertEquals(0, data(invitee).minutes());
        File[] archived = fixture.plugin.getDataFolder().listFiles((dir, name) -> name.startsWith("referrals-2026-10-15"));
        assertNotNull(archived);
        assertEquals(1, archived.length);
        assertTrue(Files.readString(archived[0].toPath()).contains(inviter.getUniqueId().toString()));

        referrals.reload();
        assertEquals(1, fixture.plugin.getDataFolder().listFiles((dir, name) -> name.startsWith("referrals-")).length);
    }

    @Test
    void timersSaveAndShutdownWritesTheFile() {
        TestPlayer player = fixture.join("Alice", "10.0.0.1");
        referrals.noteActive(player.getUniqueId());
        fixture.server.getScheduler().performTicks(5 * 60 * 20L);
        fixture.server.getScheduler().waitAsyncTasksFinished();
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(
            new File(fixture.plugin.getDataFolder(), "referrals.yml"));
        assertEquals(5, saved.getInt("players." + player.getUniqueId() + ".minutes"));
        data(player).setMinutes(42);
        fixture.server.getPluginManager().disablePlugin(fixture.plugin);
        saved = YamlConfiguration.loadConfiguration(new File(fixture.plugin.getDataFolder(), "referrals.yml"));
        assertEquals(42, saved.getInt("players." + player.getUniqueId() + ".minutes"));
        referrals.shutdown();
    }

    @Test
    void settingTheFirstSeasonStartOrATypoKeepsWhatWasCounted() throws Exception {
        fixture.writeReferral(ReferralFixture.CONFIG.replace("start: \"2026-10-15\"", "start: \"\""));
        referrals.reload();
        TestPlayer player = fixture.join("Alice", "10.0.0.1");
        data(player).setMinutes(77);
        fixture.writeReferral(ReferralFixture.CONFIG);
        referrals.reload();
        assertEquals("2026-10-15T00:00", referrals.getStore().season());
        assertEquals(77, data(player).minutes(), "a first start date adopts the counted minutes");
        fixture.writeReferral(ReferralFixture.CONFIG.replace("2026-10-15", "2026-1O-15"));
        referrals.reload();
        assertEquals("2026-10-15T00:00", referrals.getConfiguration().seasonKey(), "a typo keeps the old start");
        assertEquals(77, data(player).minutes());
    }

    @Test
    void aBlankStartBetweenTwoDatesStillStartsANewSeason() throws Exception {
        TestPlayer player = fixture.join("Alice", "10.0.0.1");
        data(player).setMinutes(77);
        fixture.writeReferral(ReferralFixture.CONFIG.replace("start: \"2026-10-15\"", "start: \"\""));
        referrals.reload();
        assertEquals(77, data(player).minutes());
        fixture.writeReferral(ReferralFixture.CONFIG.replace("2026-10-15", "2026-10-16"));
        referrals.reload();
        assertEquals(0, data(player).minutes(), "the last real start was 2026-10-15");
    }

    @Test
    void aBrokenFileIsReadAgainOnReloadAndOnlinePlayersAreRecorded() throws Exception {
        TestPlayer player = fixture.join("Alice", "10.0.0.1");
        Path file = fixture.plugin.getDataFolder().toPath().resolve("referrals.yml");
        Files.writeString(file, "players: [unclosed");
        referrals.getStore().load(referrals.getConfiguration().seasonKey());
        assertFalse(referrals.getStore().isLoaded());
        Files.writeString(file, "season: \"2026-10-15T00:00\"\nsalt: abc\n");
        referrals.reload();
        assertTrue(referrals.getStore().isLoaded());
        assertEquals(1, data(player).ips().size(), "the online player was recorded with their connection");
        assertEquals(fixture.clock.get(), data(player).lastSeen());
    }

    @Test
    void staffCanExemptAnAccountFromTheSameConnectionCheck() {
        TestPlayer a = fixture.join("Aa", "10.0.0.1");
        TestPlayer twin = fixture.join("Twin", "10.0.0.1");
        assertEquals(Link.SAME_IP, referrals.link(twin, a.getUniqueId()));
        assertTrue(referrals.toggleIpPair(twin.getUniqueId(), a.getUniqueId()));
        linked(twin, a);
        data(twin).setMinutes(60);
        assertEquals(Claim.CLAIMED, referrals.claimOwn(twin, 1));
        assertEquals(Claim.CLAIMED, referrals.claimForInvitee(a, twin.getUniqueId(), 1));
        TestPlayer alt = fixture.join("Alt", "10.0.0.1");
        assertEquals(Link.SAME_IP, referrals.link(alt, a.getUniqueId()), "the pairing covers only that pair");
        assertFalse(referrals.toggleIpPair(a.getUniqueId(), twin.getUniqueId()));
        assertTrue(data(a).ipPartners().isEmpty() && data(twin).ipPartners().isEmpty());
        assertTrue(referrals.sharedConnection(a.getUniqueId(), data(a), twin.getUniqueId(), data(twin)));
        assertFalse(referrals.sharedConnection(a.getUniqueId(), data(a), alt.getUniqueId(),
            data(fixture.join("Other", "10.0.0.7"))));
    }

    @Test
    void aNameResolvesToTheAccountSeenMostRecently() {
        TestPlayer old = fixture.join("Shared", "10.0.0.1");
        UUID oldId = old.getUniqueId();
        old.disconnect();
        UUID newer = UUID.randomUUID();
        referrals.getStore().get(newer, "shared").setLastSeen(fixture.clock.get() + 5);
        assertEquals(newer, referrals.find("SHARED"));
        referrals.getStore().peek(oldId).setLastSeen(fixture.clock.get() + 10);
        assertEquals(oldId, referrals.find("shared"));
    }

    @Test
    void pacingBackAndForthIsCaughtByTheEverySecondSample() {
        TestPlayer player = fixture.join("Pacer", "10.0.0.1");
        Location start = player.getLocation().clone();
        fixture.server.getScheduler().performTicks(20);
        player.setLocation(start.clone().add(6, 0, 0));
        fixture.server.getScheduler().performTicks(20);
        player.setLocation(start);
        fixture.server.getScheduler().performTicks(1200 - 40);
        assertEquals(1, data(player).minutes(), "back at the same spot on the minute still counts");
        assertEquals("x1 Abyssalite", ReferralManager.plainText("#50d990x1 &7Abyssalite\u00a7r"));
    }

    @Test
    void theClockReadsAsServerTime() {
        assertEquals(ReferralFixture.NOW, referrals.now());
        assertEquals(fixture.clock.get(), referrals.millis());
        assertTrue(referrals.seasonStarted());
    }
}
