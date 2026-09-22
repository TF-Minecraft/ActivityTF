package net.tfminecraft.activitytf.hooks;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.models.ActivityDef;
import net.tfminecraft.activitytf.models.PlayerData;
import net.tfminecraft.activitytf.utils.Bar;
import net.tfminecraft.activitytf.utils.Utils;

import java.util.Locale;

public class PlaceholderHook extends PlaceholderExpansion {

    private final ActivityManager manager;

    public PlaceholderHook(ActivityManager manager) {
        this.manager = manager;
    }

    @Override
    public String getIdentifier() {
        return "activity";
    }

    @Override
    public String getAuthor() {
        return "Justin";
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "";
        }

        ActivityConfiguration config = manager.getConfiguration();
        PlayerData data = manager.getStore().peek(player.getUniqueId());

        ActivityConfiguration.Keys keys = config.currentKeys();
        boolean currentWeek = data != null && data.weekKey().equals(keys.week());
        int points = currentWeek ? Math.min(data.points(), config.barMax()) : 0;

        String key = params.toLowerCase(Locale.ROOT);

        switch (key) {
            case "points":
                return String.valueOf(points);
            case "max":
                return String.valueOf(config.barMax());
            case "percent":
                return String.valueOf(points * 100 / config.barMax());
            case "claimable":
                return String.valueOf(currentWeek ? data.claimable(config.milestones()) : 0);
            case "daily_points":
                return String.valueOf(currentWeek && data.dayKey().equals(keys.day())
                    ? Math.min(data.dailyPoints(), config.dailyMax()) : 0);
            case "daily_max":
                return String.valueOf(config.dailyMax());
            case "bar":
                return Utils.colorize(Bar.render(points, config.barMax(), config.barLength()));
            default:
                break;
        }

        if (key.startsWith("done_")) {
            String id = params.substring("done_".length());
            ActivityDef def = config.activity(id);
            if (def == null) {
                return "";
            }
            int today = currentWeek && data.dayKey().equals(keys.day())
                ? def.worth(data.count(def.id())) : 0;
            boolean done = def.dailyCap() > 0 ? today >= def.capPoints() : today > 0;
            return Utils.colorize(config.messages().raw(done ? "placeholder.done" : "placeholder.not-done"));
        }

        return null;
    }
}
