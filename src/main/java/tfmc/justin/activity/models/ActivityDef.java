package tfmc.justin.activity.models;

import org.bukkit.Material;

import java.util.List;

public record ActivityDef(String id, String display, Material icon, String iconPath, int every, int points,
                          int dailyCap, List<String> clickCommands, List<String> description) {

    public ActivityDef {
        clickCommands = clickCommands == null ? List.of() : List.copyOf(clickCommands);
        description = description == null ? List.of() : List.copyOf(description);
    }

    public ActivityDef(String id, String display, Material icon, String iconPath, int every, int points,
                       int dailyCap) {
        this(id, display, icon, iconPath, every, points, dailyCap, List.of(), List.of());
    }

    public int worth(int count) {
        int raw = rawWorth(count);
        return dailyCap > 0 ? Math.min(raw, capPoints()) : raw;
    }

    public int capPoints() {
        return (int) Math.min(Integer.MAX_VALUE, (long) dailyCap * points);
    }

    public int completions(int count) {
        int raw = count / every;
        return dailyCap > 0 ? Math.min(raw, dailyCap) : raw;
    }

    public int rawWorth(int count) {
        return (int) Math.min(Integer.MAX_VALUE, (long) (count / every) * points);
    }
}
