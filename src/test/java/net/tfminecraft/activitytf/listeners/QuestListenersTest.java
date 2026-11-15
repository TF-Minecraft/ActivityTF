package net.tfminecraft.activitytf.listeners;

import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.research.event.ResearchCompleteEvent;
import net.tfminecraft.simplefactions.events.PlayerProvinceEnterEvent;
import org.bukkit.Server;
import org.bukkit.entity.Cow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestListenersTest {

    private static Player player(UUID uuid) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        return player;
    }

    @Test
    void breedingCreditsOnlyAPlayerBreeder() {
        ActivityManager manager = mock(ActivityManager.class);
        BreedListener listener = new BreedListener(manager);
        UUID uuid = UUID.randomUUID();
        EntityBreedEvent byPlayer = mock(EntityBreedEvent.class);
        Player breeder = player(uuid);
        when(byPlayer.getBreeder()).thenReturn(breeder);
        EntityBreedEvent byMob = mock(EntityBreedEvent.class);
        when(byMob.getBreeder()).thenReturn(mock(LivingEntity.class));

        listener.onBreed(byPlayer);
        listener.onBreed(byMob);

        verify(manager, times(1)).recordAction(uuid, "breed_animals", 1);
    }

    @Test
    void killingCreditsHostileCreaturesNotSpawnerOnesAnimalsOrUnownedKills() {
        ActivityManager manager = mock(ActivityManager.class);
        CreatureKillListener listener = new CreatureKillListener(manager);
        UUID uuid = UUID.randomUUID();
        Player killer = player(uuid);

        Zombie wild = mock(Zombie.class);
        when(wild.getKiller()).thenReturn(killer);
        Zombie spawned = mock(Zombie.class);
        when(spawned.fromMobSpawner()).thenReturn(true);
        when(spawned.getKiller()).thenReturn(killer);
        Zombie unowned = mock(Zombie.class);
        Cow cow = mock(Cow.class);
        when(cow.getKiller()).thenReturn(killer);

        for (LivingEntity entity : List.of(wild, spawned, unowned, cow)) {
            EntityDeathEvent event = mock(EntityDeathEvent.class);
            when(event.getEntity()).thenReturn(entity);
            listener.onDeath(event);
        }

        verify(manager, times(1)).recordAction(any(), anyString(), anyInt());
        verify(manager).recordAction(uuid, "defeat_creatures", 1);
    }

    @Test
    void travelCreditsEachNewProvinceOncePerDay() {
        ActivityManager manager = mock(ActivityManager.class);
        ActivityConfiguration config = mock(ActivityConfiguration.class);
        when(manager.getConfiguration()).thenReturn(config);
        when(config.currentKeys()).thenReturn(new ActivityConfiguration.Keys("w", "d1"));
        ProvinceTravelListener listener = new ProvinceTravelListener(manager);
        UUID uuid = UUID.randomUUID();
        Player traveller = player(uuid);

        listener.onProvinceEnter(new PlayerProvinceEnterEvent(traveller, 1, null));
        listener.onProvinceEnter(new PlayerProvinceEnterEvent(null, 2, 1));
        listener.onProvinceEnter(new PlayerProvinceEnterEvent(traveller, 2, 1));
        listener.onProvinceEnter(new PlayerProvinceEnterEvent(traveller, 3, 2));
        listener.onProvinceEnter(new PlayerProvinceEnterEvent(traveller, 2, 3));
        verify(manager, times(2)).recordAction(uuid, "travel_provinces", 1);

        when(config.currentKeys()).thenReturn(new ActivityConfiguration.Keys("w", "d2"));
        listener.onProvinceEnter(new PlayerProvinceEnterEvent(traveller, 2, 3));
        verify(manager, times(3)).recordAction(uuid, "travel_provinces", 1);
        assertEquals(1, listener.tracked());
    }

    private static Plugin plugin(PluginManager pluginManager, List<String> warnings) {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        Logger logger = Logger.getLogger("NamedEventHookTest-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        when(plugin.getLogger()).thenReturn(logger);
        return plugin;
    }

    @Test
    void aNamedEventIsHookedAndCreditsItsPlayer() {
        PluginManager pluginManager = mock(PluginManager.class);
        List<String> warnings = new ArrayList<>();
        Plugin plugin = plugin(pluginManager, warnings);
        ActivityManager manager = mock(ActivityManager.class);

        assertTrue(NamedEventHook.register(plugin, getClass().getClassLoader(),
            ResearchCompleteEvent.class.getName(), "research_complete", manager));

        var listener = org.mockito.ArgumentCaptor.forClass(NamedEventHook.class);
        var executor = org.mockito.ArgumentCaptor.forClass(org.bukkit.plugin.EventExecutor.class);
        verify(pluginManager).registerEvent(eq(ResearchCompleteEvent.class), listener.capture(),
            eq(EventPriority.MONITOR), executor.capture(), eq(plugin), eq(true));
        UUID uuid = UUID.randomUUID();
        Player researcher = player(uuid);
        try {
            executor.getValue().execute(listener.getValue(), new ResearchCompleteEvent(researcher));
            executor.getValue().execute(listener.getValue(), new ResearchCompleteEvent(null));
            executor.getValue().execute(listener.getValue(), mock(Event.class));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        verify(manager, times(1)).recordAction(uuid, "research_complete", 1);

        listener.getValue().handle(new ResearchCompleteEvent(researcher, true));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Could not read the player"));
    }

    @Test
    void aMissingOrWrongEventClassIsReportedAndNotHooked() {
        PluginManager pluginManager = mock(PluginManager.class);
        List<String> warnings = new ArrayList<>();
        Plugin plugin = plugin(pluginManager, warnings);

        assertFalse(NamedEventHook.register(plugin, getClass().getClassLoader(), "no.such.Event", "x", null));
        assertFalse(NamedEventHook.register(plugin, getClass().getClassLoader(), String.class.getName(), "x", null));
        assertFalse(NamedEventHook.register(plugin, getClass().getClassLoader(), Event.class.getName(), "x", null));

        assertEquals(3, warnings.size());
        verify(pluginManager, never()).registerEvent(any(), any(), any(), any(), any(), eq(true));
    }
}
