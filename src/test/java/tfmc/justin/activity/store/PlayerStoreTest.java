package tfmc.justin.activity.store;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import tfmc.justin.activity.models.PlayerData;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ====================================
// PlayerStore's parsing/snapshot rules are pure logic over a
// ConfigurationSection / PlayerData, but PlayerStore itself needs a live
// JavaPlugin + ActivityConfiguration to construct, which this suite cannot
// build. Both rules are therefore exercised through the package-private
// statics the instance methods delegate to:
//
//   - readEntry(ConfigurationSection root, String key, int barMax), returning
//     null for a missing section or a key that is not a UUID
//   - snapshot(Map<UUID, PlayerData>)
// ====================================
class PlayerStoreTest {

    private static final int BAR_MAX = 20;

    @Test
    void pointsAreFlooredAtZeroAndCappedAtBarMax() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("points", 999);
        entry.set("claimed-points", 0);

        PlayerData data = PlayerStore.readEntry(root, id.toString(), BAR_MAX);
        assertEquals(BAR_MAX, data.points());

        UUID negativeId = UUID.randomUUID();
        ConfigurationSection negative = root.createSection(negativeId.toString());
        negative.set("points", -5);
        negative.set("claimed-points", 0);
        PlayerData negativeData = PlayerStore.readEntry(root, negativeId.toString(), BAR_MAX);
        assertEquals(0, negativeData.points());
    }

    @Test
    void claimedPointsIsFlooredAtZeroAndCappedAtPoints() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("points", 10);
        entry.set("claimed-points", 999);

        PlayerData data = PlayerStore.readEntry(root, id.toString(), BAR_MAX);
        assertEquals(10, data.claimedPoints());

        UUID negId = UUID.randomUUID();
        ConfigurationSection negEntry = root.createSection(negId.toString());
        negEntry.set("points", 10);
        negEntry.set("claimed-points", -5);
        PlayerData negData = PlayerStore.readEntry(root, negId.toString(), BAR_MAX);
        assertEquals(0, negData.claimedPoints());
    }

    @Test
    void everyDailyValueIsFlooredAtZero() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        UUID id = UUID.randomUUID();
        ConfigurationSection entry = root.createSection(id.toString());
        entry.set("points", 5);
        entry.set("claimed-points", 0);
        entry.set("daily.vote", -3);
        entry.set("daily.quest", 2);

        PlayerData data = PlayerStore.readEntry(root, id.toString(), BAR_MAX);
        assertEquals(0, data.count("vote"));
        assertEquals(2, data.count("quest"));
    }

    @Test
    void malformedUuidKeyIsSkippedNotFatal() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");
        ConfigurationSection entry = root.createSection("not-a-uuid");
        entry.set("points", 5);

        PlayerData data = PlayerStore.readEntry(root, "not-a-uuid", BAR_MAX);
        assertNull(data);
    }

    @Test
    void aMissingSectionIsSkipped() {
        ConfigurationSection root = new YamlConfiguration().createSection("players");

        assertNull(PlayerStore.readEntry(root, UUID.randomUUID().toString(), BAR_MAX));
    }

    @Test
    void emptyEntryIsOmittedFromTheSnapshot() {
        UUID id = UUID.randomUUID();
        PlayerData empty = new PlayerData("2026-09-07", "2026-09-09");

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, empty));
        assertFalse(yaml.contains("players." + id));
    }

    @Test
    void nonZeroEntryIsWrittenWithAllExpectedKeys() {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(5, "2026-09-07", "2026-09-09", 0, Map.of("vote", 1));

        YamlConfiguration yaml = PlayerStore.snapshot(Map.of(id, data));
        String path = "players." + id;
        assertTrue(yaml.isSet(path + ".points"));
        assertTrue(yaml.isSet(path + ".week"));
        assertTrue(yaml.isSet(path + ".day"));
        assertTrue(yaml.isSet(path + ".claimed-points"));
        assertTrue(yaml.isSet(path + ".daily"));
    }
}
