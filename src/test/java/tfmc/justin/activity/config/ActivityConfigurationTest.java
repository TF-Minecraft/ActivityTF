package tfmc.justin.activity.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// ====================================
// The profession id normalization and the lookup that uses it. load() itself
// needs a running server, so the map is built here the same way loadActivities
// builds it - through normalizeProfessionId.
// ====================================
class ActivityConfigurationTest {

    private static Map<String, String> professions(String... professionIds) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String professionId : professionIds) {
            map.put(ActivityConfiguration.normalizeProfessionId(professionId), "activity_" + professionId);
        }
        return map;
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
