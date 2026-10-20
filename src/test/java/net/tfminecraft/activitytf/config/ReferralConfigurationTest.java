package net.tfminecraft.activitytf.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.tfminecraft.activitytf.config.ReferralConfiguration.Act;
import net.tfminecraft.activitytf.config.ReferralConfiguration.Milestone;
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
        assertEquals(10, config.maxInvitees());
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
    void actsAreSortedAndTheLatestStartedOneIsUsed() throws Exception {
        ReferralConfiguration config = config("""
            acts:
              act_2:
                starts: "2026-11-01"
                milestones: [{hours: 1, new-player: pool_x}]
              act_1:
                display: "Act I"
                starts: "2026-10-01"
                rare-limit: -1
                milestones: [{hours: 1, new-player: pool_x}]
            """);
        assertEquals(List.of("act_1", "act_2"), config.acts().stream().map(Act::id).toList());
        assertNull(config.act(LocalDateTime.of(2026, 9, 30, 0, 0)));
        assertEquals("act_1", config.act(LocalDateTime.of(2026, 10, 1, 0, 0)).id());
        assertEquals("Act I", config.act(LocalDateTime.of(2026, 10, 31, 0, 0)).display());
        assertEquals(-1, config.act(LocalDateTime.of(2026, 10, 31, 0, 0)).rareLimit());
        Act second = config.act(LocalDateTime.of(2026, 11, 2, 0, 0));
        assertEquals("act_2", second.display());
        assertEquals(2, second.rareLimit());
        assertTrue(warned("draws from 'pool_x'"));
    }

    @Test
    void brokenActsAreReportedAndSkipped() throws Exception {
        ReferralConfiguration config = config("""
            acts:
              flat: 3
              nostart:
                milestones: [{hours: 1, new-player: pool_x}]
              badstart:
                starts: tomorrow
                milestones: [{hours: 1, new-player: pool_x}]
              empty:
                starts: "2026-10-01"
            """);
        assertTrue(config.acts().isEmpty());
        assertTrue(warned("acts.flat is not a section"));
        assertTrue(warned("acts.nostart has no usable 'starts' date"));
        assertTrue(warned("acts.badstart.starts 'tomorrow' is not a date"));
        assertTrue(warned("acts.empty has no usable milestone"));
    }

    @Test
    void milestonesAreValidatedSortedAndCapped() throws Exception {
        StringBuilder many = new StringBuilder();
        for (int hours = 9; hours >= 1; hours--) {
            many.append("      - {hours: ").append(hours).append(", new-player: pool_x}\n");
        }
        ReferralConfiguration config = config("""
            acts:
              act_1:
                starts: "2026-10-01"
                milestones:
                  - {hours: 0, new-player: pool_x}
                  - {hours: 20000, new-player: pool_x}
                  - {hours: x, new-player: pool_x}
                  - {hours: 2}
                  - {hours: 9, new-player: pool_x}
            """ + many);
        List<Milestone> milestones = config.acts().get(0).milestones();
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), milestones.stream().map(Milestone::hours).toList());
        assertEquals(60, milestones.get(0).minutes());
        assertTrue(warned("milestones[0] needs 'hours' between 1 and 10000"));
        assertTrue(warned("milestones[1] needs 'hours'"));
        assertTrue(warned("milestones[2] needs 'hours'"));
        assertTrue(warned("milestones[3] pays neither player"));
        assertTrue(warned("repeats 9h"));
        assertTrue(warned("has 9 milestones - only the first 7"));
    }

    @Test
    void rewardsArePoolNamesPoolBlocksOrFixedEntries() throws Exception {
        ReferralConfiguration config = config("""
            pools:
              pool_mine:
                - {display: "Gem", items: [{item: DIAMOND, amount: 2}]}
              notapool:
                - {items: [{item: DIAMOND}]}
            acts:
              act_1:
                starts: "2026-10-01"
                milestones:
                  - hours: 1
                    rare: true
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
        List<Milestone> milestones = config.acts().get(0).milestones();
        Milestone first = milestones.get(0);
        assertTrue(first.rare());
        assertEquals("pool_mine", first.newPlayer().pool());
        assertEquals("Pool Mine", first.newPlayer().display());
        assertNull(first.newPlayer().fixed());
        assertEquals("Gift", first.inviter().display());
        assertEquals("EMERALD", first.inviter().icon());
        assertEquals(1, config.pool("pool_mine").size());
        assertTrue(warned("pools.notapool must use a pool_<name> name"));

        Milestone second = milestones.get(1);
        assertFalse(second.rare());
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
        assertEquals(pool, config.pool("pool_act1"));
        assertTrue(config.pool("pool_none").isEmpty());
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
        assertEquals(5, config.acts().get(0).milestones().size());
        assertEquals(List.of(1, 3, 8, 20, 45), config.acts().get(0).milestones().stream().map(Milestone::hours).toList());
        assertTrue(config.acts().get(0).milestones().get(4).rare());
        assertTrue(config.ignoredWorld("character_selection"));
        assertTrue(logged.isEmpty(), "shipped referral.yml warns: " + logged);
    }
}
