package tfmc.justin.activity.listeners;

import io.lumine.mythic.lib.api.event.skill.SkillCastEvent;
import io.lumine.mythic.lib.skill.Skill;
import io.lumine.mythic.lib.skill.result.SkillResult;
import io.lumine.mythic.lib.skill.trigger.TriggerType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;

// ====================================
// MythicLib fires SkillCastEvent synchronously on the main thread for every
// skill MMOCore runs. Not cancellable. Only constructed when MMOCore is
// enabled - see ActivityPlugin; MMOCore hard-depends on MythicLib, so the
// event type is there whenever MMOCore is.
//
// Two filters, both taken from TFMCCore's own skill stat:
//   - CAST/API only. Every other TriggerType is a passive that fires off
//     attacking, being hit, sneaking or a timer - the player never chose it.
//   - the result must be successful, so a cast stopped by a cooldown, missing
//     mana or a failed condition pays nothing.
// ====================================
public class SkillCastListener implements Listener {

    private final ActivityManager manager;

    public SkillCastListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSkillCast(SkillCastEvent event) {
        Skill cast = event.getCast();
        if (cast == null) {
            return;
        }

        TriggerType trigger = cast.getTrigger();
        if (trigger != TriggerType.CAST && trigger != TriggerType.API) {
            return;
        }

        SkillResult result = event.getResult();
        if (result == null || !result.isSuccessful()) {
            return;
        }

        if (event.getPlayer() == null) {
            return;
        }

        manager.recordAction(event.getPlayer().getUniqueId(), "skill_cast", 1);
    }
}
