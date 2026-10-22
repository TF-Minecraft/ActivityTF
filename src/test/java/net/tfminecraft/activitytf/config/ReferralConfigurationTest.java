package net.tfminecraft.activitytf.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.tfminecraft.activitytf.config.ReferralConfiguration.Act;
import net.tfminecraft.activitytf.config.ReferralConfiguration.Milestone;
import net.tfminecraft.activitytf.config.ReferralConfiguration.RareStep;
import net.tfminecraft.activitytf.models.RewardEntry;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferralConfigurationTest {

    @TempDir
    Path directory;

    private final List<String> logged = new ArrayList<>();

    private ReferralConfiguration config(String yaml) throws Exception {
        JavaPlugin plugin = TestPlugins.capturing(logged);
        ActivityConfiguration activity = new ActivityConfiguration(plugin);
        ReferralConfiguration config = new ReferralConfiguration(plugin, activity);
        YamlConfiguration source = new YamlConfiguration();
        source.loadFromString(yaml);
        config.load(source);
        return config;
    }

    private boolean warned(String text) {
        return logged.stream().anyMatch(line -> line.contains(text));
    }

    @Test
    void anEmptyFileUsesTheDefaultsAndSaysNothingCanBeClaimed() throws Exception {
        ReferralConfiguration config = config("");
        assertTrue(config.enabled());
        assertNull(config.seasonStart());
        assertEquals("", config.seasonKey());
        assertEquals(14, config.openDays());
        assertEquals(7, config.newAccountDays());
        assertEquals(0, config.maxInvitees(), "no limit by default");
        assertEquals(3, config.maxLinksPerDay());
        assertTrue(config.ipCheck());
        assertEquals(5, config.afkMinutes());
        assertEquals(3, config.moveBlocks());
        assertFalse(config.ignoredWorld("world"));
        assertFalse(config.ignoredWorld(null));
        assertTrue(config.acts().isEmpty());
        assertNull(config.act(LocalDateTime.now()));
        assertTrue(warned("no usable act"));
        assertSame(config.activity().messages(), config.messages());
    }

    @Test
    void valuesAreReadAndClamped() throws Exception {
        ReferralConfiguration config = config("""
            enabled: false
            season: {start: "2026-10-01T06:10", open-days: -1}
            link: {new-account-days: -2, max-invitees: -3, max-links-per-day: -4, same-ip-check: false}
            playtime: {afk-minutes: 0, move-blocks: -1, ignored-worlds: [" Character_Selection "]}
            """);
        assertFalse(config.enabled());
        assertEquals(LocalDateTime.of(2026, 10, 1, 6, 10), config.seasonStart());
        assertEquals("2026-10-01T06:10", config.seasonKey());
        assertEquals(0, config.openDays());
        assertEquals(0, config.newAccountDays());
        assertEquals(0, config.maxInvitees());
        assertEquals(0, config.maxLinksPerDay());
        assertFalse(config.ipCheck());
        assertEquals(1, config.afkMinutes());
        assertEquals(1, config.moveBlocks());
        assertTrue(config.ignoredWorld("character_selection"));
        assertFalse(warned("no usable act"), "a disabled file is not reported");
    }

    @Test
    void aBadSeasonDateIsReportedAndKeepsThePreviousOne() throws Exception {
        ReferralConfiguration config = config("season: {start: soon}");
        assertNull(config.seasonStart());
        assertTrue(warned("season.start 'soon' is not a date"));
        YamlConfiguration source = new YamlConfiguration();
        source.loadFromString("season: {start: \"2026-10-01\"}");
        config.load(source);
        source.loadFromString("season: {start: \"2026-1O-01\"}");
        config.load(source);
        assertEquals("2026-10-01T00:00", config.seasonKey());
        source.loadFromString("season: {start: \"\"}");
        config.load(source);
        assertEquals("", config.seasonKey());
    }

    @Test
    void actsAreSortedTheLatestStartedOneIsUsedAndTheyShareTheMilestones() throws Exception {
        ReferralConfiguration config = config("""
            milestones: [{hours: 1, new-player: pool_x}]
            acts:
              act_2:
                starts: "2026-11-01"
                pools:
                  pool_x: [{display: "Two", items: [{item: GOLD_INGOT}]}]
              act_1:
                display: "Act I"
                starts: "2026-10-01"
                rare-limit: -1
            rare-track: [{players: 1, reward: pool_x}]
            """);
        assertEquals(List.of("act_1", "act_2"), config.acts().stream().map(Act::id).toList());
        assertNull(config.act(LocalDateTime.of(2026, 9, 30, 0, 0)));
        Act first = config.act(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertEquals("act_1", first.id());
        assertEquals("Act I", first.display());
        assertTrue(warned("acts.act_1.rare-limit is no longer used"));
        Act second = config.act(LocalDateTime.of(2026, 11, 2, 0, 0));
        assertEquals("act_2", second.display());
        assertSame(config.milestones(), first.milestones());
        assertSame(config.milestones(), second.milestones());
        assertEquals(1, config.rareTrack().size());
        assertSame(config.rareTrack(), first.rareTrack());
        assertSame(config.rareTrack(), second.rareTrack());
        assertEquals("Two", config.pool(second, "pool_x").get(0).display());
        assertTrue(config.pool(first, "pool_x").isEmpty());
        assertTrue(warned("acts.act_1 has no usable pool 'pool_x'"), "act_1 has no loot for its 1h milestone");
        assertFalse(warned("acts.act_2 has no usable pool"));
    }

    @Test
    void anActsOwnPoolWinsOverTheSharedOneWhichWinsOverTheActivityOne() throws Exception {
        ReferralConfiguration config = config("""
            milestones: [{hours: 1, new-player: pool_x, inviter: {items: [{item: STONE}]}}]
            pools:
              pool_x: [{display: "Shared", items: [{item: IRON_INGOT}]}]
            acts:
              act_1:
                starts: "2026-10-01"
                pools:
                  pool_x: [{display: "Own", items: [{item: DIAMOND}]}]
                  notapool: [{items: [{item: DIAMOND}]}]
              act_2:
                starts: "2026-11-01"
            """);
        assertEquals("Own", config.pool(config.acts().get(0), "pool_x").get(0).display());
        assertEquals("Shared", config.pool(config.acts().get(1), "pool_x").get(0).display());
        assertEquals("Shared", config.pool(null, "pool_x").get(0).display());
        assertTrue(config.pool(config.acts().get(1), "pool_y").isEmpty());
        assertTrue(warned("acts.act_1.pools.notapool must use a pool_<name> name"));
        assertFalse(warned("has no usable pool"));
    }

    @Test
    void brokenActsAreReportedAndSkipped() throws Exception {
        ReferralConfiguration config = config("""
            milestones: [{hours: 1, new-player: {items: [{item: STONE}]}}]
            acts:
              flat: 3
              nostart:
                display: x
              badstart:
                starts: tomorrow
            """);
        assertTrue(config.acts().isEmpty());
        assertTrue(warned("acts.flat is not a section"));
        assertTrue(warned("acts.nostart has no usable 'starts' date"));
        assertTrue(warned("acts.badstart.starts 'tomorrow' is not a date"));
        assertTrue(warned("no usable act"));
        assertFalse(warned("no usable entry under 'milestones'"));
    }

    @Test
    void milestonesAreValidatedSortedAndNotCapped() throws Exception {
        StringBuilder many = new StringBuilder();
        for (int hours = 12; hours >= 1; hours--) {
            many.append("  - {hours: ").append(hours).append(", new-player: pool_x}\n");
        }
        ReferralConfiguration config = config("""
            milestones:
              - {hours: 0, new-player: pool_x}
              - {hours: 20000, new-player: pool_x}
              - {hours: x, new-player: pool_x}
              - {hours: 2}
              - {hours: 9, new-player: pool_x}
            """ + many);
        List<Milestone> milestones = config.milestones();
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), milestones.stream().map(Milestone::hours).toList());
        assertEquals(60, milestones.get(0).minutes());
        assertEquals(12 * 60, config.completionMinutes(), "the last milestone counts for the rare track");
        assertTrue(warned("milestones[0] needs 'hours' between 1 and 10000"));
        assertTrue(warned("milestones[1] needs 'hours'"));
        assertTrue(warned("milestones[2] needs 'hours'"));
        assertTrue(warned("milestones[3] pays neither player"));
        assertTrue(warned("repeats 9h"));
    }

    @Test
    void rareStepsAreValidatedSortedAndCapped() throws Exception {
        StringBuilder many = new StringBuilder();
        for (int players = 30; players >= 1; players--) {
            many.append("  - {players: ").append(players).append(", reward: pool_x}\n");
        }
        ReferralConfiguration config = config("""
            link: {max-invitees: 10}
            milestones:
              - {hours: 24, new-player: pool_x, rare: true}
            rare-track:
              - {players: 0, reward: pool_x}
              - {players: 2000, reward: pool_x}
              - {players: 4}
              - {players: 5, reward: 7}
              - {players: 3, reward: {display: "Gems", items: [{item: DIAMOND, amount: 2}]}}
            """ + many);
        List<RareStep> steps = config.rareTrack();
        assertEquals(ReferralConfiguration.MAX_RARE_STEPS, steps.size());
        assertEquals(List.of(1, 2, 3), steps.subList(0, 3).stream().map(RareStep::players).toList());
        assertEquals("Gems", steps.get(2).reward().display(), "the first of a repeated count wins");
        assertEquals(List.of(new RewardEntry.Item("DIAMOND", 2)), steps.get(2).reward().fixed().items());
        assertEquals("pool_x", steps.get(0).reward().pool());
        assertEquals(24 * 60, config.completionMinutes());
        assertTrue(warned("rare-track[0] needs 'players' between 1 and 1000"));
        assertTrue(warned("rare-track[1] needs 'players'"));
        assertTrue(warned("rare-track[2] has no usable 'reward'"));
        assertTrue(warned("rare-track[3].reward is not a pool name"));
        assertTrue(warned("repeats 3 players"));
        assertTrue(warned("rare-track steps - only the first 28"));
        assertTrue(warned("milestones[0].rare is no longer used"));
        assertTrue(warned("step for 11 players can never be claimed: link.max-invitees is 10"));
        assertFalse(warned("step for 10 players can never"));
    }

    @Test
    void withoutMilestonesNothingCanBeClaimedAndTheOldLayoutIsNamed() throws Exception {
        ReferralConfiguration config = config("""
            acts:
              act_1:
                starts: "2026-10-01"
                milestones: [{hours: 1, new-player: pool_x}]
            """);
        assertTrue(warned("no usable entry under 'milestones'"));
        assertTrue(warned("acts.act_1.milestones is the old layout"));
        assertEquals(1, config.acts().size());
        assertNull(config.act(LocalDateTime.of(2026, 10, 2, 0, 0)), "no milestones, so no act to claim in");
        assertEquals(0, config.completionMinutes());
    }

    @Test
    void missingPoolsAreReportedOncePerActAndNotWhenDisabled() throws Exception {
        config("""
            milestones: [{hours: 1, new-player: pool_x, inviter: pool_x}]
            rare-track: [{players: 1, reward: pool_x}, {players: 2, reward: pool_y}]
            acts: {act_1: {starts: "2026-10-01", pools: {pool_x: []}}}
            """);
        assertEquals(1, logged.stream().filter(line -> line.contains("has no usable pool 'pool_x'")).count());
        assertTrue(warned("pool 'pool_y' (nor does pools or the activity rewards) - the 2-player rare step"));
        logged.clear();
        config("""
            enabled: false
            milestones: [{hours: 1, new-player: pool_x}]
            acts: {act_1: {starts: "2026-10-01"}}
            """);
        assertFalse(warned("has no usable pool"));
    }

    @Test
    void rewardsArePoolNamesPoolBlocksOrFixedEntries() throws Exception {
        ReferralConfiguration config = config("""
            pools:
              pool_mine:
                - {display: "Gem", items: [{item: DIAMOND, amount: 2}]}
              notapool:
                - {items: [{item: DIAMOND}]}
            milestones:
              - hours: 1
                new-player: pool_mine
                inviter: {pool: pool_mine, display: "Gift", icon: EMERALD}
              - hours: 2
                new-player: {items: [{item: IRON_INGOT, amount: 3}]}
                inviter: {display: "Hi", commands: ["say %player%"], icon: "x.bad"}
              - hours: 3
                new-player: {display: " ", commands: ["say hi"]}
                inviter: "DIAMOND"
              - hours: 4
                new-player: {display: "Nothing"}
                inviter: 5
            """);
        List<Milestone> milestones = config.milestones();
        Milestone first = milestones.get(0);
        assertEquals("pool_mine", first.newPlayer().pool());
        assertEquals("Pool Mine", first.newPlayer().display());
        assertNull(first.newPlayer().fixed());
        assertEquals("Gift", first.inviter().display());
        assertEquals("EMERALD", first.inviter().icon());
        assertEquals(1, config.pool(null, "pool_mine").size());
        assertTrue(warned("pools.notapool must use a pool_<name> name"));

        Milestone second = milestones.get(1);
        assertEquals("Iron Ingot", second.newPlayer().display());
        assertEquals(List.of(new RewardEntry.Item("IRON_INGOT", 3)), second.newPlayer().fixed().items());
        assertEquals("Hi", second.inviter().display());
        assertNull(second.inviter().icon());
        assertTrue(warned("icon 'x.bad' is not an item path"));

        Milestone third = milestones.get(2);
        assertEquals("Reward", third.newPlayer().display());
        assertNull(third.inviter());
        assertTrue(warned("milestones[2].inviter is not a pool name or a reward block"));
        assertEquals(3, milestones.size(), "the fourth pays nobody and is dropped");
        assertTrue(warned("milestones[3].new-player has no commands and no items"));
    }

    @Test
    void anUnknownPoolFallsBackToTheActivityPools() throws Exception {
        ReferralConfiguration config = config("");
        Field field = ActivityConfiguration.class.getDeclaredField("rewardPools");
        field.setAccessible(true);
        List<RewardEntry> pool = List.of(new RewardEntry(1, "x", List.of(), List.of(new RewardEntry.Item("DIAMOND", 1))));
        field.set(config.activity(), Map.of("pool_act1", pool));
        assertEquals(pool, config.pool(null, "pool_act1"));
        assertTrue(config.pool(null, "pool_none").isEmpty());
    }

    @Test
    void loadReadsReferralYmlAndWritesTheDefaultWhenMissing() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        ReferralConfiguration config = new ReferralConfiguration(plugin, new ActivityConfiguration(plugin));
        config.load();
        verify(plugin).saveResource(ReferralConfiguration.FILE, false);

        Files.writeString(directory.resolve(ReferralConfiguration.FILE), "enabled: false\n");
        JavaPlugin second = mock(JavaPlugin.class);
        when(second.getDataFolder()).thenReturn(directory.toFile());
        ReferralConfiguration again = new ReferralConfiguration(second, new ActivityConfiguration(second));
        again.load();
        verify(second, never()).saveResource(ReferralConfiguration.FILE, false);
        assertFalse(again.enabled());
        assertTrue(new File(directory.toFile(), ReferralConfiguration.FILE).isFile());
    }

    @Test
    void theShippedReferralYmlLoadsCleanly() throws Exception {
        ReferralConfiguration config = config(Files.readString(Path.of("src/main/resources/referral.yml")));
        assertEquals(1, config.acts().size());
        assertEquals(List.of(1, 4, 12, 24), config.milestones().stream().map(Milestone::hours).toList());
        assertEquals(24 * 60, config.completionMinutes());
        assertEquals(List.of(2, 4, 6, 8, 10), config.rareTrack().stream().map(RareStep::players).toList());
        assertEquals(0, config.maxInvitees());
        Act act = config.acts().get(0);
        int tin = 0;
        for (RareStep step : config.rareTrack()) {
            List<RewardEntry> pool = config.pool(act, step.reward().pool());
            assertEquals(1, pool.size(), "every step is a one-entry act pool, so it hands over specific items");
            assertEquals(pool.get(0).items().get(0).amount(), pool.get(0).items().get(1).amount(), "tin and fragments 1:1");
            tin += pool.get(0).items().get(0).amount();
        }
        assertEquals(16, tin, "the whole track adds up to 16 of each");
        int ignitium = 0;
        for (Milestone milestone : config.milestones()) {
            List<RewardEntry> pool = config.pool(act, milestone.inviter().pool());
            ignitium += pool.get(0).items().stream().filter(item -> item.path().equals("m.materials.ignitium"))
                .mapToInt(RewardEntry.Item::amount).sum();
        }
        assertEquals(32, ignitium, "the inviter's milestones add up to half a stack");
        assertEquals("Steel Hatchet", ActivityConfiguration.itemName(config.pool(act, "pool_ref_tools").get(0).items().get(1)));
        assertEquals("Steel Pickaxe", ActivityConfiguration.itemName(config.pool(act, "pool_ref_tools").get(0).items().get(0)));
        assertEquals("c.dry_ration(type=dry_ration;quality=1)",
            config.pool(act, "pool_ref_tools").get(0).items().get(4).path());
        assertTrue(config.ignoredWorld("character_selection"));
        assertTrue(logged.isEmpty(), "shipped referral.yml warns: " + logged);
    }
}
