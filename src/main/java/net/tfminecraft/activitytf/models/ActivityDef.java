package net.tfminecraft.activitytf.models;

import org.bukkit.Material;

import java.util.List;

/**
 * One configured activity. {@code requires} lists permissions of which a player needs any one to be dealt
 * this task (empty = anyone). Only tasks with {@code requiresReveal} must be clicked in the menu before they
 * count; every other drawn task is revealed and counts straight away.
 */
public record ActivityDef(String id, String display, Material icon, String iconPath, int every, int points,
                          int dailyCap, List<String> clickCommands, List<String> description,
                          List<String> requires, boolean requiresReveal) {

    public ActivityDef {
        clickCommands = clickCommands == null ? List.of() : List.copyOf(clickCommands);
        description = description == null ? List.of() : List.copyOf(description);
        requires = requires == null ? List.of() : List.copyOf(requires);
    }

    public ActivityDef(String id, String display, Material icon, String iconPath, int every, int points,
                       int dailyCap, List<String> clickCommands, List<String> description) {
        this(id, display, icon, iconPath, every, points, dailyCap, clickCommands, description, List.of(),
            defaultRequiresReveal(id));
    }

    public ActivityDef(String id, String display, Material icon, String iconPath, int every, int points,
                       int dailyCap) {
        this(id, display, icon, iconPath, every, points, dailyCap, List.of(), List.of());
    }

    /** Voting must be clicked unless a config says otherwise, so votes from someone who never logs in earn nothing. */
    public static boolean defaultRequiresReveal(String id) {
        return "vote".equals(id);
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
