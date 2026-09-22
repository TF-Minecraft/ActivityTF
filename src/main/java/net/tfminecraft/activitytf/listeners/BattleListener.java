package net.tfminecraft.activitytf.listeners;

import net.tfminecraft.simplefactions.war.battle.events.BattleEndedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.tfminecraft.activitytf.managers.ActivityManager;

import java.util.Set;
import java.util.UUID;

public class BattleListener implements Listener {

    private final ActivityManager manager;

    public BattleListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBattleEnded(BattleEndedEvent event) {
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
