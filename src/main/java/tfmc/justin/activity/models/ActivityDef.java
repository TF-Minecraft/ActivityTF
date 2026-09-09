package tfmc.justin.activity.models;

import org.bukkit.Material;

public record ActivityDef(String id, String display, Material icon, int dailyGoal, int points) {
}
