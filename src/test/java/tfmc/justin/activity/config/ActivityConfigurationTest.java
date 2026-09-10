package tfmc.justin.activity.config;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// The craft: decision. Material#isAir and #isItem both go through the item
// registry, which does not exist headless, so the live server's answer is
// handed in as a predicate - exactly as ActivityConfiguration hands in its
// own. AIR and BEDROCK stand for the two things a live server rejects.
// ====================================
class ActivityConfigurationTest {

    private static final Predicate<Material> CRAFTABLE =
        material -> !Set.of(Material.AIR, Material.BEDROCK, Material.WATER).contains(material);

    private static String register(Map<Material, String> crafts, String name, String id) {
        return ActivityConfiguration.registerCraft(crafts, name, id, CRAFTABLE);
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
}
