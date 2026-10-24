package net.tfminecraft.activitytf.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagesLinesTest {

    @TempDir
    Path directory;

    @Test
    void listMessagesAreColouredLineByLineWithPlaceholders() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        Files.writeString(directory.resolve(Messages.FILE), "rules:\n  - '&aOne %n%'\n  - 'Two'\n");
        Messages messages = new Messages(plugin);
        messages.reload();
        assertEquals(List.of("§aOne 5", "Two"), messages.lines("rules", "%n%", 5));
        assertEquals(List.of("§aOne %n%", "Two"), messages.lines("rules", "%n%"));
        assertEquals(List.of(), messages.lines("absent"));
    }
}
