package tfmc.justin.activity.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import tfmc.justin.activity.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public class Messages {

    public static final String FILE = "messages.yml";

    private final JavaPlugin plugin;

    private volatile YamlConfiguration messages;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) {
            plugin.saveResource(FILE, false);
        }

        YamlConfiguration next = YamlConfiguration.loadConfiguration(file);

        YamlConfiguration packaged = loadPackaged();
        if (packaged != null) {
            next.setDefaults(packaged);
        }

        messages = next;
    }

    public String get(String path, Object... placeholderPairs) {
        String raw = messages.getString(path);
        if (raw == null) {
            plugin.getLogger().warning("Missing message '" + path + "' in " + FILE);
            return path;
        }

        if (placeholderPairs.length % 2 != 0) {
            plugin.getLogger().warning("Message '" + path + "' was given an odd number of placeholder arguments.");
            return Utils.colorize(raw);
        }

        String message = Utils.colorize(raw);
        for (int index = 0; index < placeholderPairs.length; index += 2) {
            message = message.replace(String.valueOf(placeholderPairs[index]),
                String.valueOf(placeholderPairs[index + 1]));
        }

        return message;
    }

    public String raw(String path) {
        String value = messages.getString(path);
        return value == null ? path : value;
    }

    private YamlConfiguration loadPackaged() {
        try (InputStream stream = plugin.getResource(FILE)) {
            if (stream == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to read the packaged " + FILE + ": " + e.getMessage());
            return null;
        }
    }

}
