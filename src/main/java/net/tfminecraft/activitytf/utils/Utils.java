package net.tfminecraft.activitytf.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.md_5.bungee.api.ChatColor;

public class Utils {

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static String colorize(String msg) {
        Matcher match = Pattern.compile("#[a-fA-F0-9]{6}").matcher(msg);
        while (match.find()) {
            String color = msg.substring(match.start(), match.end());
            msg = msg.replace(color, String.valueOf(ChatColor.of(color)));
            match = Pattern.compile("#[a-fA-F0-9]{6}").matcher(msg);
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    /** The server's time zone as players read it ("UTC"); the day resets at midnight in this zone. */
    public static String serverZone() {
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        return zone.getDisplayName(zone.inDaylightTime(new java.util.Date()), java.util.TimeZone.SHORT,
            java.util.Locale.ROOT);
    }

    public static String safeForLog(String name) {
        return name == null ? "null" : name.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "?");
    }

    public static String quotedForLog(String value) {
        return "\"" + safeForLog(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
