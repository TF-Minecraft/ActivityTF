package tfmc.justin.activity.config;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// The two optional activity keys that load() parses out of config.yml.
//
// craft: Material#isAir and #isItem both go through the item registry, which
// does not exist headless, so the live server's answer is handed in as a
// predicate - exactly as ActivityConfiguration hands in its own. AIR and
// BEDROCK stand for the two things a live server rejects.
//
// profession: the id normalization and the lookup that uses it. load() itself
// needs a running server, so the map is built here the same way loadActivities
// builds it - through normalizeProfessionId.
// ====================================
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

    // A typo must not start feeding some other activity, and must say so
    @Test
    void anUnknownNameIsRejectedAndNamed() {
        Map<Material, String> crafts = new HashMap<>();

        String problem = register(crafts, "DIMAOND_BLOCK", "craft_diamond_block");

        assertTrue(problem.contains("Unknown material 'DIMAOND_BLOCK'"), problem);
        assertTrue(problem.contains("activities.craft_diamond_block.craft"), problem);
        assertTrue(crafts.isEmpty());
    }

    // AIR is what the special recipes (firework rockets, banner copies, map
    // extending) report as their result, so accepting it would hand one
    // activity every one of them
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

    // One material feeds one activity: the second claim is unreachable, so it
    // loses and gets said out loud
    @Test
    void aDuplicateMaterialKeepsTheFirstActivity() {
        Map<Material, String> crafts = new HashMap<>();

        assertNull(register(crafts, "ANVIL", "craft_anvil"));
        String problem = register(crafts, "anvil", "craft_anvil_again");

        assertEquals(Map.of(Material.ANVIL, "craft_anvil"), crafts);
        assertTrue(problem.contains("activity 'craft_anvil' already tracks"), problem);
        assertTrue(problem.contains("only 'craft_anvil' will be credited"), problem);
    }

    // The raw config string reaches a log line, so a name carrying a newline
    // or an escape must not be able to forge one
    @Test
    void theRawNameIsSanitisedBeforeItReachesTheLog() {
        Map<Material, String> crafts = new HashMap<>();

        String problem = register(crafts, "DIAMOND\n[INFO]: op Notch", "craft_diamond");

        assertTrue(problem.contains("DIAMOND?[INFO]: op Notch"), problem);
        assertEquals(-1, problem.indexOf('\n'), problem);
    }

    @Test
    void normalizationMatchesMmoCore() {
        // MMOCore: id = rawId.toLowerCase().replace("_", "-").replace(" ", "-")
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

    // The whole point of MEDIUM 1: a config file written the way config.yml
    // tells admins to write it must still find MMOCore's dashed id
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

    // Documented behaviour: two activities on one profession, last one wins
    @Test
    void duplicateProfessionKeepsTheLastActivity() {
        Map<String, String> professions = new LinkedHashMap<>();
        professions.put(ActivityConfiguration.normalizeProfessionId("crafter"), "first");
        String previous = professions.put(ActivityConfiguration.normalizeProfessionId("CRAFTER"), "second");

        assertEquals("first", previous);
        assertEquals(1, professions.size());
        assertEquals("second", ActivityConfiguration.professionActivity(professions, "crafter"));
    }
}
