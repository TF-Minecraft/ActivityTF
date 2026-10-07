package net.tfminecraft.activitytf.config;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Bukkit;
import org.bukkit.UnsafeValues;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemChecker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ActivityConfigurationTest {

    private static final Predicate<Material> CRAFTABLE =
        material -> !Set.of(Material.AIR, Material.BEDROCK, Material.WATER).contains(material);

    private final List<Map.Entry<String, String>> paths = new ArrayList<>();

    private String register(Map<Material, String> crafts, String name, String id) {
        return ActivityConfiguration.registerCraft(crafts, paths, name, id, CRAFTABLE);
    }

    private static Map<String, String> professions(String... professionIds) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String professionId : professionIds) {
            map.put(ActivityConfiguration.normalizeProfessionId(professionId), "activity_" + professionId);
        }
        return map;
    }

    @Test
    void aVanillaPathRegistersAsItsMaterial() {
        Map<Material, String> crafts = new HashMap<>();

        assertNull(register(crafts, "v.diamond_block", "craft_diamond_block"));
        assertEquals(Map.of(Material.DIAMOND_BLOCK, "craft_diamond_block"), crafts);
        assertTrue(paths.isEmpty());
    }

    @Test
    void anItemPathIsKeptAsAPathInConfigOrder() {
        Map<Material, String> crafts = new HashMap<>();

        assertNull(register(crafts, "m.material.steel", "craft_steel"));
        assertNull(register(crafts, "m.sword.katana", "craft_katana"));

        assertTrue(crafts.isEmpty());
        assertEquals(List.of(Map.entry("m.material.steel", "craft_steel"),
            Map.entry("m.sword.katana", "craft_katana")), paths);
    }

    @Test
    void aMalformedItemPathIsReportedAndRegistersNothing() {
        Map<Material, String> crafts = new HashMap<>();

        String problem = register(crafts, "m.material", "craft_steel");

        assertTrue(problem != null && problem.contains("Malformed item path"), problem);
        assertTrue(problem.contains("activities.craft_steel.craft"), problem);
        assertTrue(crafts.isEmpty() && paths.isEmpty());
    }

    @Test
    void anItemsAdderCraftKeyIsRefusedInItsOwnWords() {
        Map<Material, String> crafts = new HashMap<>();

        for (String path : new String[]{"ia.tfmc:saucepan", "ia.tfmc.saucepan", "ia.broken"}) {
            String problem = register(crafts, path, "cook_dish");

            assertTrue(problem != null && problem.contains("ItemsAdder item path"), problem);
            assertTrue(problem.contains(path), problem);
            assertTrue(problem.contains("activities.cook_dish.craft"), problem);
            assertFalse(problem.contains("Unsupported item path"), problem);
            assertTrue(problem.contains("nothing will ever feed that activity"), problem);
        }

        assertTrue(crafts.isEmpty() && paths.isEmpty());
    }

    @Test
    void anUnknownVanillaPathIsReported() {
        Map<Material, String> crafts = new HashMap<>();

        String problem = register(crafts, "v.dimaond_block", "craft_diamond_block");

        assertTrue(problem != null && problem.contains("Unknown material"), problem);
        assertTrue(crafts.isEmpty() && paths.isEmpty());
    }

    @Test
    void theFirstActivityClaimingAnItemPathKeepsIt() {
        Map<Material, String> crafts = new HashMap<>();

        assertNull(register(crafts, "m.material.steel", "craft_steel"));
        String problem = register(crafts, "m.material.STEEL", "craft_steel_again");

        assertEquals(List.of(Map.entry("m.material.steel", "craft_steel")), paths);
        assertTrue(problem != null && problem.contains("activity 'craft_steel' already tracks"), problem);
    }

    @Test
    void anItemNameIsRegisteredSilently() {
        Map<Material, String> crafts = new HashMap<>();

        assertNull(register(crafts, "DIAMOND_BLOCK", "craft_diamond_block"));
        assertEquals(Map.of(Material.DIAMOND_BLOCK, "craft_diamond_block"), crafts);
    }

    @Test
    void noCraftKeyRegistersNothing() {
        Map<Material, String> crafts = new HashMap<>();

        assertNull(register(crafts, null, "vote"));
        assertNull(register(crafts, "   ", "vote"));
        assertTrue(crafts.isEmpty());
    }

    @Test
    void anUnknownNameIsRejectedAndNamed() {
        Map<Material, String> crafts = new HashMap<>();

        String problem = register(crafts, "DIMAOND_BLOCK", "craft_diamond_block");

        assertTrue(problem.contains("Unknown material 'DIMAOND_BLOCK'"), problem);
        assertTrue(problem.contains("activities.craft_diamond_block.craft"), problem);
        assertTrue(crafts.isEmpty());
    }

    @Test
    void airIsRejected() {
        Map<Material, String> crafts = new HashMap<>();

        String problem = register(crafts, "AIR", "craft_anything");

        assertTrue(problem.contains("is not an item"), problem);
        assertTrue(crafts.isEmpty());
    }

    @Test
    void aBlockOnlyNameIsRejected() {
        Map<Material, String> crafts = new HashMap<>();

        String problem = register(crafts, "WATER", "craft_water");

        assertTrue(problem.contains("'WATER'"), problem);
        assertTrue(problem.contains("is not an item"), problem);
        assertTrue(crafts.isEmpty());
    }

    @Test
    void aDuplicateMaterialKeepsTheFirstActivity() {
        Map<Material, String> crafts = new HashMap<>();

        assertNull(register(crafts, "ANVIL", "craft_anvil"));
        String problem = register(crafts, "anvil", "craft_anvil_again");

        assertEquals(Map.of(Material.ANVIL, "craft_anvil"), crafts);
        assertTrue(problem.contains("activity 'craft_anvil' already tracks"), problem);
        assertTrue(problem.contains("only 'craft_anvil' will be credited"), problem);
    }

    @Test
    void theRawNameIsSanitisedBeforeItReachesTheLog() {
        Map<Material, String> crafts = new HashMap<>();

        String problem = register(crafts, "DIAMOND\n[INFO]: op Notch", "craft_diamond");

        assertTrue(problem.contains("DIAMOND?[INFO]: op Notch"), problem);
        assertEquals(-1, problem.indexOf('\n'), problem);
    }

    @Test
    void normalizationMatchesMmoCore() {
        assertEquals("mining-expert", ActivityConfiguration.normalizeProfessionId("mining_expert"));
        assertEquals("mining-expert", ActivityConfiguration.normalizeProfessionId("Mining Expert"));
        assertEquals("mining-expert", ActivityConfiguration.normalizeProfessionId("MINING-EXPERT"));
        assertEquals("crafter", ActivityConfiguration.normalizeProfessionId("  Crafter  "));
    }

    @Test
    void normalizationIsIdempotent() {
        String once = ActivityConfiguration.normalizeProfessionId("Mining_Expert");
        assertEquals(once, ActivityConfiguration.normalizeProfessionId(once));
    }

    @Test
    void underscoredConfigIdMatchesDashedMmoCoreId() {
        Map<String, String> professions = professions("mining_expert");
        assertEquals("activity_mining_expert",
            ActivityConfiguration.professionActivity(professions, "mining-expert"));
    }

    @Test
    void lookupIsCaseAndSeparatorInsensitive() {
        Map<String, String> professions = professions("crafter");
        assertEquals("activity_crafter", ActivityConfiguration.professionActivity(professions, "CRAFTER"));
        assertEquals("activity_crafter", ActivityConfiguration.professionActivity(professions, " crafter "));
    }

    @Test
    void unknownBlankAndNullProfessionsAreNotTracked() {
        Map<String, String> professions = professions("crafter");
        assertNull(ActivityConfiguration.professionActivity(professions, "mining"));
        assertNull(ActivityConfiguration.professionActivity(professions, ""));
        assertNull(ActivityConfiguration.professionActivity(professions, "   "));
        assertNull(ActivityConfiguration.professionActivity(professions, null));
        assertNull(ActivityConfiguration.professionActivity(Map.of(), "crafter"));
    }

    @Test
    void duplicateProfessionKeepsTheLastActivity() {
        Map<String, String> professions = new LinkedHashMap<>();
        professions.put(ActivityConfiguration.normalizeProfessionId("crafter"), "first");
        String previous = professions.put(ActivityConfiguration.normalizeProfessionId("CRAFTER"), "second");

        assertEquals("first", previous);
        assertEquals(1, professions.size());
        assertEquals("second", ActivityConfiguration.professionActivity(professions, "crafter"));
    }

    private static List<Integer> caps(int count, int cap) {
        List<Integer> caps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            caps.add(cap);
        }
        return caps;
    }

    @Test
    void theDailyCeilingIsTheSevenLowestCapsNotAllOfThem() {
        assertEquals(7, ActivityConfiguration.dailyCeiling(caps(40, 1), 40, 10));
    }

    @Test
    void theDailyCeilingTakesTheLowestCapsNotTheFirstOnes() {
        List<Integer> caps = new ArrayList<>(List.of(9, 9, 9, 1, 1, 1, 1, 1, 1, 1));
        assertEquals(7, ActivityConfiguration.dailyCeiling(caps, 10, 100));
    }

    @Test
    void theDailyCeilingNeverExceedsDailyMax() {
        assertEquals(10, ActivityConfiguration.dailyCeiling(caps(40, 5), 40, 10));
    }

    @Test
    void tooFewCappedActivitiesFallsBackToDailyMax() {
        assertEquals(10, ActivityConfiguration.dailyCeiling(caps(6, 1), 40, 10));
    }

    @Test
    void fewerActivitiesThanADrawAreStillBoundedByTheirCaps() {
        assertEquals(3, ActivityConfiguration.dailyCeiling(caps(3, 1), 3, 10));
    }

    @Test
    void capsBelowDailyMaxAreNamedAsTheBound() {
        String warning = ActivityConfiguration.unreachableWarning(caps(40, 1), 40, 10, 100);
        assertTrue(warning.contains("bound by per-activity daily-caps)"), warning);
    }

    @Test
    void dailyMaxAloneIsNamedWhenNothingIsCapped() {
        String warning = ActivityConfiguration.unreachableWarning(List.of(), 20, 5, 100);
        assertTrue(warning.contains("bound by bar.daily-max)"), warning);
    }

    @Test
    void capsThatLandExactlyOnDailyMaxNameBoth() {
        String warning = ActivityConfiguration.unreachableWarning(caps(7, 1), 7, 7, 100);
        assertTrue(warning.contains("bound by per-activity daily-caps and bar.daily-max)"), warning);
    }

    @Test
    void theWarningCountsTheDrawItNotSeven() {
        assertTrue(ActivityConfiguration.unreachableWarning(caps(3, 1), 3, 10, 100)
            .startsWith("A day's 3 drawn tasks"));
        assertTrue(ActivityConfiguration.unreachableWarning(caps(40, 1), 40, 10, 100)
            .startsWith("A day's 7 drawn tasks"));
    }

    @Test
    void aReachableFirstRewardWarnsAboutNothing() {
        assertNull(ActivityConfiguration.unreachableWarning(caps(40, 5), 40, 10, 10));
    }

    private static YamlConfiguration yaml(String path, Object value) {
        YamlConfiguration config = new YamlConfiguration();
        config.set(path, value);
        return config;
    }

    @Test
    void theRerollKnobsReadTheValueTheirPathCarries() {
        assertEquals(5, ActivityConfiguration.parseRerollsPerDay(
            yaml(ActivityConfiguration.REROLLS_PER_DAY_PATH, 5)));
        assertEquals(5, ActivityConfiguration.parseRerollMaxPoints(
            yaml(ActivityConfiguration.REROLL_MAX_POINTS_PATH, 5)));
    }

    @Test
    void aNegativeRerollKnobClampsToZero() {
        assertEquals(0, ActivityConfiguration.parseRerollsPerDay(
            yaml(ActivityConfiguration.REROLLS_PER_DAY_PATH, -3)));
        assertEquals(0, ActivityConfiguration.parseRerollMaxPoints(
            yaml(ActivityConfiguration.REROLL_MAX_POINTS_PATH, -3)));
    }

    @Test
    void anUnsetRerollKnobFallsBackToOne() {
        assertEquals(1, ActivityConfiguration.parseRerollsPerDay(new YamlConfiguration()));
        assertEquals(1, ActivityConfiguration.parseRerollMaxPoints(new YamlConfiguration()));
    }

    @Nested
    class FileLoading {
        @TempDir Path directory;

        final YamlConfiguration settings = new YamlConfiguration();
        final Set<String> enabledPlugins = new HashSet<>();
        final List<String> logs = new ArrayList<>();
        MockedStatic<Bukkit> bukkit;
        ActivityConfiguration config;

        @BeforeEach
        void setUp() throws Exception {
            Files.writeString(directory.resolve(Messages.FILE), "test: original\n");
            JavaPlugin plugin = mock(JavaPlugin.class);
            when(plugin.getDataFolder()).thenReturn(directory.toFile());
            when(plugin.getConfig()).thenAnswer(call ->
                YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile()));
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) { logs.add(record.getMessage()); }
                @Override public void flush() { }
                @Override public void close() { }
            });
            when(plugin.getLogger()).thenReturn(logger);
            PluginManager plugins = mock(PluginManager.class);
            when(plugins.isPluginEnabled(anyString())).thenAnswer(call ->
                enabledPlugins.contains(call.getArgument(0)));
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            bukkit.when(Bukkit::getLogger).thenReturn(logger);
            bukkit.when(Bukkit::getUnsafe).thenReturn(mock(UnsafeValues.class));
            config = new ActivityConfiguration(plugin);

            settings.set("bar.vote-share", 0);
            settings.set("bar.milestones", List.of(10, 20));
            settings.set("rewards.pool", List.of(Map.of("display", "Original reward",
                "commands", List.of("say original"))));
            activity("playtime");
        }

        @AfterEach
        void tearDown() {
            if (bukkit != null) bukkit.close();
        }

        void activity(String id) {
            settings.set("activities." + id + ".points", 1);
        }

        void load() throws Exception {
            settings.save(directory.resolve("config.yml").toFile());
            config.load();
        }

        void warned(String fragment) {
            assertTrue(logs.stream().anyMatch(line -> line.contains(fragment)), logs.toString());
        }

        ItemStack item(Material material) {
            ItemStack item = mock(ItemStack.class);
            when(item.getType()).thenReturn(material);
            return item;
        }

        @Test
        void validFileLoadsPublicSettingsAndMessages() throws Exception {
            settings.set("reset.day", " wednesday ");
            settings.set("reset.hour", 17);
            settings.set("gui.title", "&6Activity board");
            settings.set("bar.max", 60);
            settings.set("bar.daily-max", 12);
            settings.set("bar.length", 30);
            settings.set("playtime.afk-minutes", 8);
            settings.set("save-interval-minutes", 3);
            settings.set(ActivityConfiguration.REROLLS_PER_DAY_PATH, 2);
            settings.set(ActivityConfiguration.REROLL_MAX_POINTS_PATH, 7);
            settings.set("sounds.goal-complete", " ENTITY_PLAYER_LEVELUP ");
            settings.set("sounds.bar-complete", "block.note_block.bit");
            settings.set("activities.playtime.display", "Stay awhile");
            settings.set("activities.playtime.every", 5);
            settings.set("activities.playtime.daily-cap", 4);
            settings.set("activities.playtime.material", "v.clock");
            settings.set("activities.playtime.daily-guaranteed", true);

            load();

            assertEquals(DayOfWeek.WEDNESDAY, config.resetDay());
            assertEquals(17, config.resetHour());
            assertEquals("&6Activity board", config.guiTitle());
            assertEquals(60, config.barMax());
            assertEquals(12, config.dailyMax());
            assertEquals(12, config.nonVoteDailyMax());
            assertEquals(30, config.barLength());
            assertEquals(8, config.afkMinutes());
            assertEquals(3, config.saveIntervalMinutes());
            assertEquals(2, config.rerollsPerDay());
            assertEquals(7, config.rerollMaxPoints());
            assertEquals("entity.player.levelup", config.goalCompleteSound());
            assertEquals("block.note_block.bit", config.barCompleteSound());
            assertEquals("original", config.messages().get("test"));
            assertEquals(Material.CLOCK, config.activity("playtime").icon());
            assertEquals("Stay awhile", config.activity("playtime").display());
            assertEquals(5, config.activity("playtime").every());
            assertEquals(4, config.activity("playtime").capPoints());
            assertEquals(List.of("playtime"), config.guaranteed());
            assertEquals("Original reward", config.rewardPool().getFirst().display());
            assertTrue(Files.isRegularFile(directory.resolve(ActivityConfiguration.REWARD_LOCK_FILE)));
            assertFalse(config.rewardsPending());
            assertTrue(logs.stream().noneMatch(line -> line.contains(" - ")), logs.toString());
        }

        @Test
        void malformedEntriesAreReportedWhileUsableActivitiesSurvive() throws Exception {
            settings.set("reset.day", "someday");
            settings.set("reset.hour", 30);
            settings.set("bar.length", 101);
            settings.set("activities.scalar", "not a section");
            settings.set("activities.fraction.points", 1.5);
            settings.set("activities.overflow.points", 2_147_483_648L);
            settings.set("activities.playtime.every", "many");
            settings.set("activities.playtime.daily-cap", 3.5);
            settings.set("activities.playtime.material", "not_a_material");
            activity("malformed_path");
            settings.set("activities.malformed_path.material", "m.material");
            activity("unsupported");
            settings.set("activities.unsupported.craft", "other.diamond");
            activity("air");
            settings.set("activities.air.craft", "AIR");
            activity("water");
            settings.set("activities.water.craft", "WATER");

            load();

            assertEquals(DayOfWeek.MONDAY, config.resetDay());
            assertEquals(23, config.resetHour());
            assertEquals(100, config.barLength());
            assertNull(config.activity("scalar"));
            assertNull(config.activity("fraction"));
            assertNull(config.activity("overflow"));
            assertEquals(1, config.activity("playtime").every());
            assertEquals(0, config.activity("playtime").dailyCap());
            assertEquals(Material.PAPER, config.activity("playtime").icon());
            assertNull(config.activity("malformed_path").iconPath());
            assertNull(config.craftActivity(item(Material.WATER)));
            warned("Unknown reset.day 'someday'");
            warned("activities.fraction.points is not a whole number");
            warned("activities.overflow.points is not a whole number");
            warned("activities.playtime.every is not a whole number");
            warned("activities.playtime.daily-cap is not a whole number");
            warned("Unknown material 'not_a_material'");
            warned("Malformed item path 'm.material'");
            warned("Unsupported item path 'other.diamond'");
            warned("'AIR' at activities.air.craft is not an item");
            warned("'WATER' at activities.water.craft is not an item");
        }

        @Test
        void numericBoundsAndBlankSoundsUseSafeValues() throws Exception {
            settings.set("reset.hour", -2);
            settings.set("bar.max", 0);
            settings.set("bar.daily-max", -1);
            settings.set("bar.length", 0);
            settings.set("playtime.afk-minutes", -4);
            settings.set("save-interval-minutes", 0);
            settings.set("sounds.goal-complete", " ");
            settings.set("click-command-cooldown-millis", 1);
            settings.set("click-commands-per-second", 1000);
            settings.set("click-commands-per-click", "unlimited");

            load();

            assertEquals(0, config.resetHour());
            assertEquals(1, config.barMax());
            assertEquals(1, config.dailyMax());
            assertEquals(1, config.barLength());
            assertEquals(0, config.afkMinutes());
            assertEquals(1, config.saveIntervalMinutes());
            assertNull(config.goalCompleteSound());
            assertNull(config.barCompleteSound());
            assertEquals(50, config.clickCommandCooldownMillis());
            assertEquals(200, config.clickCommandsPerSecond());
            assertEquals(5, config.clickCommandsPerClick());
            assertEquals(List.of(1), config.milestones());
            warned("bar.length");
            warned("click-commands-per-click");
        }

        @Test
        void defaultBukkitSoundNamesAndCustomResourceKeysStayPlayable() throws Exception {
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile());
            settings.set("sounds.goal-complete", bundled.getString("sounds.goal-complete"));
            settings.set("sounds.bar-complete", bundled.getString("sounds.bar-complete"));
            load();
            assertEquals(Sound.ENTITY_EXPERIENCE_ORB_PICKUP.getKey().getKey(), config.goalCompleteSound());
            assertEquals(Sound.UI_TOAST_CHALLENGE_COMPLETE.getKey().getKey(), config.barCompleteSound());

            settings.set("sounds.goal-complete", " custom:theme_win ");
            settings.set("sounds.bar-complete", "block.note_block.bit");
            load();
            assertEquals("custom:theme_win", config.goalCompleteSound());
            assertEquals("block.note_block.bit", config.barCompleteSound());

            settings.set("sounds.goal-complete", "custom_theme_win");
            load();
            assertEquals("custom_theme_win", config.goalCompleteSound());
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(strings = {"level up", "!", "é", "custom:bad key"})
        void malformedSoundNamesDoNotAbortTheRestOfTheReload(String value) throws Exception {
            // Paper converts the key in UnsafeValues.get, which rejects a null NamespacedKey.
            UnsafeValues unsafe = mock(UnsafeValues.class);
            when(unsafe.get(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(org.bukkit.NamespacedKey.class)))
                .thenThrow(new NullPointerException("Paper cannot convert a null sound key"));
            bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
            settings.set("sounds.goal-complete", value);
            settings.set("sounds.bar-complete", value);
            settings.set("bar.max", 123);
            load();
            assertNull(config.goalCompleteSound());
            assertNull(config.barCompleteSound());
            assertEquals(123, config.barMax());
            warned("Invalid sound");
        }

        @Test
        void duplicateFeedMappingsHaveAnExplicitLastOwnerAndResetActionAmounts() throws Exception {
            activity("miner_old");
            settings.set("activities.miner_old.profession", "Mining Skill");
            activity("miner_new");
            settings.set("activities.miner_new.profession", "MINING_SKILL");
            settings.set("activities.miner_new.daily-cap", 5);
            activity("forge_old");
            settings.set("activities.forge_old.station", "forge/sword");
            activity("forge_new");
            settings.set("activities.forge_new.station", "forge");
            settings.set("activities.forge_new.station-actions.forge/sword", 4);
            activity("blocks");
            settings.set("activities.blocks.craft", "v.diamond_block");

            load();

            assertEquals("miner_new", config.professionActivity(" mining skill "));
            assertEquals(new ActivityConfiguration.StationActionCredit("forge_new", 4),
                config.stationAction(" FORGE ", " Sword ").orElseThrow());
            assertEquals(new ActivityConfiguration.StationActionCredit("forge_new", 1),
                config.stationAction("forge", "unlisted").orElseThrow());
            assertEquals("blocks", config.craftActivity(item(Material.DIAMOND_BLOCK)));
            warned("both track profession 'mining-skill' - only 'miner_new'");
            warned("both track station 'forge/sword' - only 'forge_new'");

            settings.set("activities.forge_new", null);
            load();
            assertEquals(new ActivityConfiguration.StationActionCredit("forge_old", 1),
                config.stationAction("forge", "sword").orElseThrow());
            assertTrue(config.stationAction("forge", "unlisted").isEmpty());
        }

        @Test
        void dependencyChangesAreDetectedAndReloadRestoresPluginPaths() throws Exception {
            activity("steel");
            settings.set("activities.steel.material", "m.material.steel");
            settings.set("activities.steel.craft", "m.material.steel");
            activity("custom");
            settings.set("activities.custom.material", "ia.example:icon");

            load();

            assertFalse(config.itemPathsUsable());
            assertFalse(config.itemsAdderUsable());
            assertFalse(config.itemPluginsChanged());
            assertNull(config.activity("steel").iconPath());
            assertEquals("ia.example:icon", config.activity("custom").iconPath());
            assertNull(config.craftActivity(item(Material.IRON_INGOT)));
            warned("TLibs, MMOItems, MythicLib are not enabled");
            warned("ItemsAdder is not enabled");

            enabledPlugins.addAll(Set.of("TLibs", "MMOItems", "MythicLib"));
            assertTrue(config.itemPluginsChanged());
            load();
            assertEquals("m.material.steel", config.activity("steel").iconPath());
            assertTrue(config.itemPathsUsable());
            assertFalse(config.itemPluginsChanged());

            enabledPlugins.add("ItemsAdder");
            assertTrue(config.itemPluginsChanged());
            load();
            assertTrue(config.itemsAdderUsable());
            assertFalse(config.itemPluginsChanged());
            enabledPlugins.remove("MMOItems");
            assertTrue(config.itemPluginsChanged());
        }

        @Test
        void pluginCraftsMatchTheFirstConfiguredPathThroughTheItemApi() throws Exception {
            enabledPlugins.addAll(Set.of("TLibs", "MMOItems", "MythicLib"));
            activity("steel");
            settings.set("activities.steel.craft", "m.material.steel");
            activity("steel_later");
            settings.set("activities.steel_later.craft", "m.material.steel_alt");
            activity("vanilla");
            settings.set("activities.vanilla.craft", "IRON_INGOT");
            load();
            ItemStack sword = item(Material.IRON_SWORD);
            ItemStack vanilla = item(Material.IRON_INGOT);
            ItemStack unmatched = item(Material.DIRT);
            ItemAPI api = mock(ItemAPI.class);
            ItemChecker checker = mock(ItemChecker.class);
            when(api.getChecker()).thenReturn(checker);
            when(checker.checkItemWithPath(sword, "m.material.steel")).thenReturn(true);
            when(checker.checkItemWithPath(sword, "m.material.steel_alt")).thenReturn(true);
            when(checker.checkItemWithPath(vanilla, "m.material.steel")).thenReturn(true);

            try (MockedStatic<TLibs> tlibs = mockStatic(TLibs.class)) {
                tlibs.when(TLibs::getItemAPI).thenReturn(api);
                assertEquals("steel", config.craftActivity(sword));
                assertEquals("vanilla", config.craftActivity(vanilla));
                assertNull(config.craftActivity(unmatched));
            }
        }

        @Test
        void reloadReplacesActivityIndexesAndMessagesButKeepsRewardsForTheWeek() throws Exception {
            settings.set("activities.playtime.craft", "DIAMOND_BLOCK");
            activity("mining");
            settings.set("activities.mining.profession", "mining");
            activity("forge");
            settings.set("activities.forge.station", "forge");
            load();
            String week = config.rewardWeek();

            settings.set("activities", null);
            activity("quest");
            settings.set("rewards.pool", List.of(Map.of("display", "Next reward",
                "commands", List.of("say next"))));
            Files.writeString(directory.resolve(Messages.FILE), "test: reloaded\n");
            load();

            assertEquals(List.of("quest"), config.activities().stream().map(def -> def.id()).toList());
            assertNull(config.craftActivity(item(Material.DIAMOND_BLOCK)));
            assertNull(config.professionActivity("mining"));
            assertTrue(config.stationActivity("forge", null).isEmpty());
            assertEquals("reloaded", config.messages().get("test"));
            assertEquals("Original reward", config.rewardPool().getFirst().display());
            assertEquals(week, config.rewardWeek());
            assertTrue(config.rewardsPending());

            assertTrue(config.applyRewardsNow());
            assertEquals("Next reward", config.rewardPool().getFirst().display());
            assertFalse(config.rewardsPending());
            load();
            assertEquals("Next reward", config.rewardPool().getFirst().display());
            assertFalse(config.rewardsPending());
        }

        @Test
        void removingAllActivitiesClearsEveryPreviouslyLoadedIndex() throws Exception {
            settings.set("activities.playtime.craft", "PAPER");
            settings.set("activities.playtime.daily-guaranteed", true);
            activity("mining");
            settings.set("activities.mining.profession", "mining");
            activity("forge");
            settings.set("activities.forge.station", "forge");
            load();
            settings.set("activities", null);
            settings.set("playtime.afk-minutes", 5);

            load();

            assertTrue(config.activities().isEmpty());
            assertTrue(config.guaranteed().isEmpty());
            assertNull(config.craftActivity(item(Material.PAPER)));
            assertNull(config.professionActivity("mining"));
            assertTrue(config.stationActivity("forge", null).isEmpty());
            warned("there is no 'playtime' activity");
        }

        @Test
        void malformedRewardSectionsCannotCreatePhantomRewards() throws Exception {
            settings.set("rewards", "not a section");
            settings.set("daily-reward.groups", List.of("invalid group"));

            load();

            assertTrue(config.rewardPool().isEmpty());
            assertTrue(config.dailyRewards().isEmpty());
            assertTrue(config.milestoneDrops().isEmpty());
            warned("rewards.pool is empty");
            warned("daily-reward.groups is not a section");
            assertEquals("not a section", YamlConfiguration.loadConfiguration(
                directory.resolve(ActivityConfiguration.REWARD_LOCK_FILE).toFile())
                .getString("rewards-config.rewards"));
        }

        @Test
        void invalidNamedPoolsAndFixedItemsFallBackToTheUsablePool() throws Exception {
            settings.set("rewards.pools.wrong", List.of(Map.of("commands", List.of("say wrong"))));
            settings.set("rewards.pools.pool", List.of(Map.of("commands", List.of("say shadow"))));
            settings.set("rewards.drops.drop_1", "not_a_material 2");
            settings.set("rewards.drops.drop_2.item", "other.invalid");
            settings.set("rewards.drops.drop_2.amount", 1);

            load();

            assertEquals(Set.of("pool"), config.activeRewards().pools().keySet());
            assertTrue(config.milestoneDrops().isEmpty());
            assertEquals("Original reward", config.rewardPool().getFirst().display());
            warned("rewards.pools.wrong must use a pool_<name> name");
            warned("rewards.pools.pool must use a pool_<name> name");
            warned("rewards.drops.drop_1 has no usable item path");
            warned("rewards.drops.drop_2 has no usable item path");

            settings.set("rewards.drops", "not a section");
            load();
            assertTrue(config.applyRewardsNow());
            assertTrue(config.milestoneDrops().isEmpty());
            warned("rewards.drops is not a section");
        }

        @Test
        void impossibleVoteSharesAndWeeklyTargetsExplainTheUnreachablePoints() throws Exception {
            settings.set("bar.vote-share", 90);
            settings.set("bar.milestones", List.of(50));
            activity("vote");
            settings.set("activities.vote.daily-cap", 1);

            load();

            assertEquals(1, config.nonVoteDailyMax());
            warned("'vote' is not daily-guaranteed");
            warned("below the 9 points bar.vote-share keeps for voting");
            warned("This is without voting: bar.vote-share keeps 9 of bar.daily-max for it");

            settings.set("activities.vote", null);
            load();
            warned("there is no 'vote' activity - the share is not applied");
        }

        @Test
        void largeDailyCapsKeepTheConfiguredPercentageWithoutIntegerOverflow() throws Exception {
            settings.set("bar.daily-max", Integer.MAX_VALUE);
            settings.set("bar.vote-share", 50);
            activity("vote");
            settings.set("activities.vote.daily-guaranteed", true);

            load();

            assertEquals(1_073_741_823, config.nonVoteDailyMax());
        }
    }
}
