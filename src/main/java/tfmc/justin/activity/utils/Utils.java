package tfmc.justin.activity.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.md_5.bungee.api.ChatColor;

public class Utils {

    public static String colorize(String msg) {
        Matcher match = Pattern.compile("#[a-fA-F0-9]{6}").matcher(msg);
        while (match.find()) {
            String color = msg.substring(match.start(), match.end());
            msg = msg.replace(color, String.valueOf(ChatColor.of(color)));
            match = Pattern.compile("#[a-fA-F0-9]{6}").matcher(msg);
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    // ====================================
    // A player-supplied name on its way into the log. A name carrying a
    // newline or an ANSI escape can forge log lines or repaint the console,
    // so every control character is replaced before it is written.
    //
    // Not \p{Cntrl}, which is ASCII only: a Bedrock name can carry U+2028 or
    // the right-to-left override U+202E, which break a log line just as well.
    // ====================================
    public static String safeForLog(String name) {
        return name == null ? "null" : name.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "?");
    }

    // ====================================
    // The same value going into an ACTIVITY-AUDIT line, where it sits beside
    // other key=value pairs. safeForLog already stops a newline or an escape
    // from forging a whole line, but it leaves spaces and '=' alone - and a
    // Geyser name, or a configured command, carries both. Unquoted, a value
    // like 'Steve result=done' lands mid-line and defeats any parser reading
    // the last value of a key. A backslash or a quote inside the value is
    // escaped, so the closing quote cannot be forged either.
    // ====================================
    public static String quotedForLog(String value) {
        return "\"" + safeForLog(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
