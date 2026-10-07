package net.tfminecraft.activitytf.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.logging.Logger;
import net.tfminecraft.activitytf.utils.Utils;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesCoverageTest {
    @TempDir Path directory;
    @Test void missingAndMalformedPlaceholderRequestsRemainReadableAndAreLogged() throws Exception {
        JavaPlugin plugin=mock(JavaPlugin.class); Logger logger=mock(Logger.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile()); when(plugin.getLogger()).thenReturn(logger);
        Files.writeString(directory.resolve(Messages.FILE),"hello: '&aHello %name%'\n");
        Messages messages=new Messages(plugin); messages.reload();
        assertEquals("absent",messages.get("absent"));
        verify(logger).warning("Missing message 'absent' in messages.yml");
        assertEquals("§aHello %name%",messages.get("hello","%name%"));
        verify(logger).warning("Message 'hello' was given an odd number of placeholder arguments.");
        assertEquals("§aHello Steve",messages.get("hello","%name%","Steve"));
        assertEquals("&aHello %name%",messages.raw("hello")); assertEquals("absent",messages.raw("absent"));
        assertEquals("§bLegacy text",new Utils().colorize("&bLegacy text"));
    }
    @Test void unreadablePackagedDefaultsDoNotDiscardUserMessages() throws Exception {
        JavaPlugin plugin=mock(JavaPlugin.class); Logger logger=mock(Logger.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile()); when(plugin.getLogger()).thenReturn(logger);
        Files.writeString(directory.resolve(Messages.FILE),"hello: local\n");
        when(plugin.getResource(Messages.FILE)).thenAnswer(call->new ByteArrayInputStream("hello: packaged\n".getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException { throw new IOException("resource close failed"); }
        });
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getLogger).thenReturn(logger);
            Messages messages=new Messages(plugin); messages.reload();
            assertEquals("local",messages.get("hello"));
            verify(logger).warning(contains("Failed to read the packaged messages.yml: resource close failed"));
        }
    }
}
