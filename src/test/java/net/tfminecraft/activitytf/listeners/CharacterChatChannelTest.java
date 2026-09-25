package net.tfminecraft.activitytf.listeners;

import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.managers.TestManagers;
import net.tfminecraft.activitytf.models.ActivityDef;
import net.tfminecraft.rpcharacters.chat.CharacterChatEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterChatChannelTest {

    private static final String MESSAGE = "all the ingredients were good";
    private final UUID uuid = UUID.randomUUID();
    private ActivityManager manager;
    private CharacterChatListener listener;
    private Player sender;

    @BeforeEach
    void setUp() {
        TestManagers.bukkit();
        manager = TestManagers.manager(new ActivityDef("ic_chat", "Roleplay Chat", Material.PAPER,
                null, 50, 1, 0));
        manager.tasks(uuid).reveal(0);
        listener = new CharacterChatListener(manager);
        sender = TestManagers.player(uuid, Set.of(), new ArrayList<>());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ooc", "OOC", "looc", "gooc", "fooc", "pooc", "admin", "helper", "unknown"})
    void nonRoleplayChatDoesNotCountOrPolluteRoleplayHistory(String channel) {
        for (boolean command : new boolean[] {false, true}) {
            listener.onCharacterChat(event(channel, command));
        }
        assertEquals(0, manager.tasks(uuid).count("ic_chat"));
        assertEquals(0, listener.historySize(uuid));

        listener.onCharacterChat(event("rp", false));
        assertEquals(1, manager.tasks(uuid).count("ic_chat"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rp", "RP", "whisper", "shout", "yell", "action", "scene", "dm"})
    void roleplayChatStillCountsAndRejectsRepeats(String channel) {
        listener.onCharacterChat(event(channel, false));
        assertEquals(1, manager.tasks(uuid).count("ic_chat"));

        listener.onCharacterChat(event(channel, true));
        assertEquals(1, manager.tasks(uuid).count("ic_chat"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rp", "whisper", "shout", "yell", "action", "scene", "dm"})
    void roleplayCommandsStillCount(String channel) {
        listener.onCharacterChat(event(channel, true));
        assertEquals(1, manager.tasks(uuid).count("ic_chat"));
    }

    private CharacterChatEvent event(String channel, boolean command) {
        return new CharacterChatEvent(sender, null, channel, MESSAGE, "Test", Set.of(sender), false, command);
    }
}
