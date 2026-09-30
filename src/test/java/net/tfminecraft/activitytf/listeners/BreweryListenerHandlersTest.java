package net.tfminecraft.activitytf.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreweryListenerHandlersTest {

    // BreweryListenerTest calls the handlers directly, which skips Bukkit's ignoreCancelled filter.
    @Test
    void everyHandlerSkipsCancelledEventsAtMonitor() {
        int handlers = 0;
        for (Method method : BreweryListener.class.getDeclaredMethods()) {
            EventHandler handler = method.getAnnotation(EventHandler.class);
            if (handler == null) {
                continue;
            }
            handlers++;
            assertTrue(handler.ignoreCancelled(), method.getName() + " must ignore cancelled events");
            assertEquals(EventPriority.MONITOR, handler.priority(), method.getName());
        }
        assertTrue(handlers > 0);
    }
}
