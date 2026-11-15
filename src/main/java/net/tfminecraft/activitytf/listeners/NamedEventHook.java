package net.tfminecraft.activitytf.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import net.tfminecraft.activitytf.managers.ActivityManager;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Credits an activity for a plugin event found by class name, so ActivityTF needs no build dependency on that
 * plugin. The event must have a public {@code getPlayer()} that returns the player to credit.
 */
public final class NamedEventHook implements Listener {

    private final ActivityManager manager;
    private final Class<? extends Event> type;
    private final Method player;
    private final String activityId;
    private final Logger logger;

    private NamedEventHook(ActivityManager manager, Class<? extends Event> type, Method player, String activityId,
                           Logger logger) {
        this.manager = manager;
        this.type = type;
        this.player = player;
        this.activityId = activityId;
        this.logger = logger;
    }

    /**
     * Registers the hook. Returns false (and logs why) when {@code eventClass} cannot be loaded from
     * {@code loader}, is not an event, or has no {@code getPlayer()}.
     */
    public static boolean register(Plugin plugin, ClassLoader loader, String eventClass, String activityId,
                                   ActivityManager manager) {
        Class<? extends Event> type;
        Method getter;
        try {
            type = Class.forName(eventClass, true, loader).asSubclass(Event.class);
            getter = type.getMethod("getPlayer");
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            plugin.getLogger().warning("Could not hook " + eventClass + " for activity '" + activityId + "': " + e);
            return false;
        }
        NamedEventHook hook = new NamedEventHook(manager, type, getter, activityId, plugin.getLogger());
        plugin.getServer().getPluginManager().registerEvent(type, hook, EventPriority.MONITOR, hook.executor(),
            plugin, true);
        return true;
    }

    EventExecutor executor() {
        return (listener, event) -> handle(event);
    }

    void handle(Event event) {
        if (!type.isInstance(event)) {
            return;
        }
        Object who;
        try {
            who = player.invoke(event);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("Could not read the player of " + type.getName() + " for activity '" + activityId
                + "': " + e);
            return;
        }
        if (who instanceof Player credited) {
            manager.recordAction(credited.getUniqueId(), activityId, 1);
        }
    }
}
