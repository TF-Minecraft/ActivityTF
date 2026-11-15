package net.tfminecraft.activitytf.store;

import net.tfminecraft.activitytf.models.PlayerData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStorePendingTest {

    @Test
    void heldCreditSurvivesASaveAndUnknownActivitiesAreDropped() throws Exception {
        UUID uuid = UUID.randomUUID();
        PlayerData data = new PlayerData("2026-11-16", "2026-11-16");
        data.addPending("vote", 3);
        data.addPending("gone", 1);

        YamlConfiguration saved = PlayerStore.snapshot(Map.of(uuid, data));
        assertTrue(saved.contains("players." + uuid + ".pending"), "a player with only held credit was not saved");

        YamlConfiguration reloaded = new YamlConfiguration();
        reloaded.loadFromString(saved.saveToString());
        PlayerData read = PlayerStore.readEntry(reloaded.getConfigurationSection("players"), uuid.toString(), 50, 10,
            Set.of("vote")::contains);

        assertEquals(Map.of("vote", 3), read.pending());
    }

    @Test
    void nothingHeldWritesNoPendingSection() {
        UUID uuid = UUID.randomUUID();
        PlayerData data = new PlayerData("w", "d");
        data.addPoints(1, 50);

        YamlConfiguration saved = PlayerStore.snapshot(Map.of(uuid, data));

        assertFalse(saved.contains("players." + uuid + ".pending"));
    }
}
