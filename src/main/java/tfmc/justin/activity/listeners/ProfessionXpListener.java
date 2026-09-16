package tfmc.justin.activity.listeners;

import net.Indyuce.mmocore.api.event.PlayerExperienceGainEvent;
import net.Indyuce.mmocore.experience.Profession;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tfmc.justin.activity.managers.ActivityManager;

import java.util.UUID;

// ====================================
// Feeds MMOCore profession XP into whichever activity declares
// 'profession: <id>' in config.yml. Only constructed when MMOCore is enabled
// - see ActivityPlugin.
//
// MONITOR + ignoreCancelled: the XP only counts once every other plugin has
// had its say and the gain is actually going through.
//
// Fractional XP is carried between gains so a boosted 5.5 xp gain is not
// repeatedly rounded down to 5 - see FractionCarry.
// ====================================
public class ProfessionXpListener implements Listener {

    private final ActivityManager manager;
    private final FractionCarry carry = new FractionCarry();

    public ProfessionXpListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExperienceGain(PlayerExperienceGainEvent event) {
        // Without a profession this is main-class/character XP, which no
        // activity tracks
        if (!event.hasProfession()) {
            return;
        }

        Profession profession = event.getProfession();
        if (profession == null) {
            return;
        }

        String activityId = manager.getConfiguration().professionActivity(profession.getId());
        if (activityId == null) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        int amount = carry.add(uuid, activityId, event.getExperience());
        if (amount > 0) {
            manager.recordAction(uuid, activityId, amount);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        carry.forget(event.getPlayer().getUniqueId());
    }
}
