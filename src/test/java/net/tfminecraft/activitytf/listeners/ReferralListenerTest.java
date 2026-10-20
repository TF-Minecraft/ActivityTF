package net.tfminecraft.activitytf.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.tfminecraft.activitytf.managers.ReferralFixture;
import net.tfminecraft.activitytf.managers.ReferralFixture.TestPlayer;

import java.time.Duration;
import java.util.HashSet;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferralListenerTest {

    private ReferralFixture fixture;
    private ReferralListener listener;

    @BeforeEach
    void start() throws Exception {
        fixture = ReferralFixture.start();
        listener = new ReferralListener(fixture.referrals);
    }

    @AfterEach
    void stop() throws Exception {
        fixture.stop();
    }

    private void afkThen(TestPlayer player, Consumer<TestPlayer> action) {
        fixture.clock.addAndGet(Duration.ofMinutes(10).toMillis());
        assertFalse(fixture.counts(player));
        action.accept(player);
        assertTrue(fixture.counts(player));
    }

    @Test
    void joiningAndQuittingTrackTheAccount() {
        TestPlayer player = fixture.join("Alice", "10.0.0.1");
        assertNotNull(fixture.referrals.getStore().peek(player.getUniqueId()), "MockBukkit fires the join");
        assertFalse(fixture.counts(player), "joining is not activity");
        fixture.referrals.noteActive(player.getUniqueId());
        assertTrue(fixture.counts(player));
        player.disconnect();
        assertFalse(fixture.counts(player));
    }

    @Test
    void chatCommandsAndBuildingCountAsBeingThere() {
        TestPlayer player = fixture.join("Alice", "10.0.0.1");
        afkThen(player, p -> listener.onChat(new AsyncChatEvent(true, p, new HashSet<>(), (source, name, message, viewer) -> message,
            Component.text("hi"), Component.text("hi"), null)));
        afkThen(player, p -> listener.onCommand(new PlayerCommandPreprocessEvent(p, "/referral info")));
        fixture.clock.addAndGet(Duration.ofMinutes(10).toMillis());
        listener.onCommand(new PlayerCommandPreprocessEvent(player, "/nosuchcommand x"));
        listener.onCommand(new PlayerCommandPreprocessEvent(player, "/"));
        assertFalse(fixture.counts(player), "an unknown command is not activity");
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        afkThen(player, p -> listener.onBreak(new BlockBreakEvent(block, p)));
        afkThen(player, p -> listener.onPlace(new BlockPlaceEvent(block, block.getState(), block,
            new ItemStack(Material.STONE), p, true, EquipmentSlot.HAND)));
        listener.onJoin(new org.bukkit.event.player.PlayerJoinEvent(player, Component.empty()));
        listener.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player, Component.empty(),
            org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertFalse(fixture.counts(player));
    }
}
