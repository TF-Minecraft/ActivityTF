package tfmc.justin.activity.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
