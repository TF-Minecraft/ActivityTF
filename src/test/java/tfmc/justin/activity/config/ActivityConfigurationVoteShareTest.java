package tfmc.justin.activity.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// bar.vote-share: the clamp on the value read and the non-vote share of
// bar.daily-max it leaves. load() needs a live server, so the parser and the
// arithmetic are driven directly, with the plugin's logger captured.
// ====================================
class ActivityConfigurationVoteShareTest {

    private final List<String> logged = new ArrayList<>();

    private int nonVoteDailyMax(int dailyMax, String voteShareYaml) {
        ActivityConfiguration config = new ActivityConfiguration(TestPlugins.capturing(logged));
        int share = config.parseVoteShare(
            YamlConfiguration.loadConfiguration(new StringReader("bar:\n  vote-share: " + voteShareYaml + "\n")));
        return ActivityConfiguration.nonVoteDailyMax(dailyMax, share);
    }

    @Test
    void theNonVoteShareRoundsDown() {
        assertEquals(3, nonVoteDailyMax(7, "50"));
        assertTrue(logged.isEmpty(), logged.toString());
    }

    @Test
    void aNegativeShareClampsToZero() {
        assertEquals(7, nonVoteDailyMax(7, "-5"));
        assertTrue(logged.stream().anyMatch(line -> line.contains("bar.vote-share -5 is outside 0-100")),
            logged.toString());
    }

    @Test
    void aShareOverAHundredClampsToAHundred() {
        assertEquals(0, nonVoteDailyMax(7, "150"));
        assertTrue(logged.stream().anyMatch(line -> line.contains("bar.vote-share 150 is outside 0-100")),
            logged.toString());
    }
}
