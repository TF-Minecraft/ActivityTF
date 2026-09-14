package tfmc.justin.activity.listeners;

import me.Plugins.SimpleFactions.War.battle.events.BattleEndedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

import java.util.Set;
import java.util.UUID;

// ====================================
// SimpleFactions fires BattleEndedEvent once per battle, carrying every
// participant rather than a single player, so each one is credited. Not
// cancellable. Only constructed when SimpleFactions is enabled - see
// ActivityPlugin.
//
// hasWinner() mirrors TFMCCore's own battle stat: a battle that ended with no
// winning side is not one worth counting.
//
// recordAction takes a UUID and tolerates an offline player, so a participant
// who logged off before the battle ended still gets credited.
// ====================================
public class BattleListener implements Listener {

    private final ActivityManager manager;

    public BattleListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBattleEnded(BattleEndedEvent event) {
        if (!event.hasWinner()) {
            return;
        }

        Set<UUID> participants = event.getParticipantIds();
        if (participants == null) {
            return;
        }

        for (UUID participant : participants) {
            if (participant != null) {
                manager.recordAction(participant, "battle_joined", 1);
            }
        }
    }
}
