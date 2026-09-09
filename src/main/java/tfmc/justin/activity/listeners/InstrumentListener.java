package tfmc.justin.activity.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.events.InstrumentPlayEvent;

// ====================================
// MusicalInstruments fires InstrumentPlayEvent synchronously on the main
// thread. Only constructed when MusicalInstruments is enabled - see
// ActivityPlugin.
// ====================================
public class InstrumentListener implements Listener {

    private final ActivityManager manager;

    public InstrumentListener(ActivityManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInstrumentPlay(InstrumentPlayEvent event) {
        // The source plugin builds the event itself; a null player would
        // only be a bug there, but it must not take this listener down
        if (event.getPlayer() == null) {
            return;
        }

        manager.recordAction(event.getPlayer().getUniqueId(), "instrument", 1);
    }
}
