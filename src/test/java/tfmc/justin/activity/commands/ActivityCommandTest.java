package tfmc.justin.activity.commands;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// /activity add is the console intake path (ConditionalEvents and friends), so
// it goes through the same daily-task gate a listener does; only an explicit
// trailing --force skips it. onCommand itself needs a live server to resolve a
// player, so what is checked here is the one argument that decides which of
// the two record paths add takes, plus that the usage strings say so.
// ====================================
class ActivityCommandTest {

    private static String[] add(String... trailing) {
        String[] args = new String[4 + trailing.length];
        args[0] = "add";
        args[1] = "Justin";
        args[2] = "vote";
        args[3] = "1";
        System.arraycopy(trailing, 0, args, 4, trailing.length);
        return args;
    }

    @Test
    void addIsGatedUnlessForceIsAskedFor() {
        assertFalse(ActivityCommand.forced(add()));
        assertTrue(ActivityCommand.forced(add("--force")));
    }

    @Test
    void forceIsCaseInsensitiveAndNothingElseCountsAsIt() {
        assertTrue(ActivityCommand.forced(add("--FORCE")));
        assertFalse(ActivityCommand.forced(add("force")));
        assertFalse(ActivityCommand.forced(add("-f")));
        assertFalse(ActivityCommand.forced(add("")));
    }

    @Test
    void theUsageStringsDocumentForce() {
        String usage = YamlConfiguration
            .loadConfiguration(new File("src/main/resources/messages.yml"))
            .getString("admin.usage", "");
        assertTrue(usage.contains("--force"), usage);

        String pluginUsage = YamlConfiguration
            .loadConfiguration(new File("src/main/resources/plugin.yml"))
            .getString("commands.activity.usage", "");
        assertTrue(pluginUsage.contains("--force"), pluginUsage);
    }
}
