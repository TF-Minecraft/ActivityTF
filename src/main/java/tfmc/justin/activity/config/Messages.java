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

// ====================================
// Every string the plugin sends to chat, read from messages.yml.
//
// The packaged copy is installed as the defaults, so a key an admin deleted
// still resolves to shipped text rather than showing a raw path in chat.
// ====================================
public class Messages {

    public static final String FILE = "messages.yml";

    private final JavaPlugin plugin;

    // Volatile: /activity reload swaps it on the main thread while
    // PlaceholderAPI can be reading a message off one
    private volatile YamlConfiguration messages;

    // Not loaded here: ActivityConfiguration.load() calls reload() before
    // anything can ask for a message, and parsing the file twice per enable
    // was the only thing the constructor call added
    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ====================================
    // Re-read messages.yml from disk, so /activity reload picks up text edits.
    // Built entirely into a local 'next' and only assigned to the volatile
    // field as the last step, so a reader on another thread never observes it
    // between being loaded and having its defaults applied.
    // ====================================
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

    // ====================================
    // Look up a message and fill in its placeholders.
    // Pairs are given inline: get("admin.add-done", "%count%", n, "%player%", name)
    // ====================================
    public String get(String path, Object... placeholderPairs) {
        String raw = messages.getString(path);
        if (raw == null) {
            // Only reachable if the key is missing from both the live file and
            // the packaged defaults, which means a typo in a call site
            plugin.getLogger().warning("Missing message '" + path + "' in " + FILE);
            return path;
        }

        if (placeholderPairs.length % 2 != 0) {
            plugin.getLogger().warning("Message '" + path + "' was given an odd number of placeholder arguments.");
            return Utils.colorize(raw);
        }

        // ====================================
        // Colour codes are translated before the values go in, so a value that
        // happens to contain '&c' or a hex code lands in chat as literal text
        // instead of recolouring the rest of the line.
        // ====================================
        String message = Utils.colorize(raw);
        for (int index = 0; index < placeholderPairs.length; index += 2) {
            message = message.replace(String.valueOf(placeholderPairs[index]),
                String.valueOf(placeholderPairs[index + 1]));
        }

        return message;
    }

    // ====================================
    // Verbatim lookup: no colour translation, no placeholder substitution and
    // no warning on a miss, where get() logs one. The path is returned as-is
    // when the key resolves to nothing. Callers colorize what they get back.
    // ====================================
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
