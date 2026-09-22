package net.tfminecraft.activitytf.listeners;

import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.managers.TestManagers;
import net.tfminecraft.activitytf.models.ActivityDef;
import net.tfminecraft.archaeo.events.FindRecoveredEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ArchaeologyListenerTest {

    @Test
    void migratedEventCreditsARevealedArchaeologyTask() {
        ActivityManager manager = TestManagers.manager(
                new ActivityDef("archaeology_find", "archaeology_find", Material.BRUSH, null, 2, 1, 0));
        UUID uuid = UUID.randomUUID();
        assertNotNull(manager.reveal(uuid, 0).revealedId());
        Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return uuid;
                    throw new UnsupportedOperationException(method.getName());
                });

        new ArchaeologyListener(manager).onFindRecovered(new FindRecoveredEvent(player, null));

        assertEquals(1, manager.tasks(uuid).count("archaeology_find"));
    }

    @Test
    void aNullPlayerIsIgnored() {
        assertDoesNotThrow(() -> new ArchaeologyListener(null)
                .onFindRecovered(new FindRecoveredEvent(null, null)));
    }
}
