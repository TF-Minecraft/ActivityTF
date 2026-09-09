package tfmc.justin.activity.hooks;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.utils.Bar;
import tfmc.justin.activity.utils.Utils;

import java.util.Locale;

// ====================================
// %activity_points%, %activity_percent%, %activity_bar%, %activity_done_<id>%
// Only registered when PlaceholderAPI is enabled - see ActivityPlugin.
// ====================================
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
        // Survives a PlaceholderAPI reload, since the expansion is not a file
        return true;
    }

    // ====================================
    // A read path, and PlaceholderAPI is happy to ask about any player from
    // any thread - so peek() rather than get(): no entry is created for a name
    // that merely appeared in a scoreboard, and nothing is rolled off-tick.
    // The rollover is applied to the answer instead: a stored week that is not
    // the current one reads as an empty bar, a stale day as nothing done yet.
    // ====================================
    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "";
        }

        ActivityConfiguration config = manager.getConfiguration();
        PlayerData data = manager.getStore().peek(player.getUniqueId());

        boolean currentWeek = data != null && data.weekKey().equals(config.currentWeekKey());
        int points = currentWeek ? data.points() : 0;

        // ====================================
        // Only the fixed keywords and the "done_" prefix are matched
        // case-insensitively, via this lowercased copy. The activity id
        // itself is cut out of the original 'params' below, preserving its
        // case, since config keys are case-sensitive everywhere else in this
        // plugin - %activity_done_Vote% must not resolve as "vote".
        // ====================================
        String key = params.toLowerCase(Locale.ROOT);

        switch (key) {
            case "points":
            case "percent":
                return String.valueOf(points);
            case "bar":
                return Utils.colorize(Bar.render(points, config.barLength(), config.barFilledChar(),
                    config.barEmptyChar(), config.barFilledColor(), config.barEmptyColor()));
            default:
                break;
        }

        if (key.startsWith("done_")) {
            String id = params.substring("done_".length());
            ActivityDef def = config.activity(id);
            if (def == null) {
                return "";
            }
            boolean done = currentWeek
                && data.dayKey().equals(config.currentDayKey())
                && data.count(def.id()) >= def.dailyGoal();
            return Utils.colorize(config.messages().raw(done ? "placeholder.done" : "placeholder.not-done"));
        }

        return null;
    }
}
