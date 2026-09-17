package tfmc.justin.activity.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import tfmc.justin.activity.config.ActivityConfiguration;
import tfmc.justin.activity.config.Messages;
import tfmc.justin.activity.gui.ActivityGui;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;
import tfmc.justin.activity.models.Recorded;
import tfmc.justin.activity.utils.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

// ====================================
// /activity opens the GUI; the subcommands are admin-only.
// 'add' doubles as the intake path for ConditionalEvents and anything else
// that can dispatch a console command, so it goes through the same daily-task
// gate a listener does: nothing is credited unless the activity is one of the
// player's revealed tasks today. A trailing --force skips the gate, for
// testing and for correcting a player by hand.
// ====================================
public class ActivityCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
        Arrays.asList("reload", "check", "reset", "givereroll", "add");

    // What 'check' alone gets: the read-only half of the command
    private static final List<String> READ_ONLY_SUBCOMMANDS = List.of("check");

    // The one optional trailing argument 'add' takes
    private static final String FORCE = "--force";

    // Well above any real day's activity, low enough that no arithmetic
    // downstream can be pushed anywhere near overflowing
    private static final int MAX_ADD = 1_000_000;

    private final ActivityManager manager;
    private final ActivityGui gui;

    public ActivityCommand(ActivityManager manager, ActivityGui gui) {
        this.manager = manager;
        this.gui = gui;
    }

    // Fetched per use rather than cached - /activity reload swaps the file
    private Messages messages() {
        return manager.getConfiguration().messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            openGui(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        // ====================================
        // An unrecognised subcommand is a usage error, not a permission one:
        // permissionFor() maps it to activity.admin, which used to hide the
        // usage line from exactly the check-only sender who mistyped. A sender
        // holding neither permission still learns nothing.
        // ====================================
        if (!SUBCOMMANDS.contains(sub)) {
            boolean anything = sender.hasPermission("activity.admin") || sender.hasPermission("activity.check");
            sender.sendMessage(messages().get(anything ? "admin.usage" : "admin.no-permission"));
            return true;
        }

        if (!sender.hasPermission(permissionFor(sub))) {
            sender.sendMessage(messages().get("admin.no-permission"));
            // A refused mutating attempt is logged too: someone probing for
            // what they are allowed to run must not do it silently. 'check' is
            // read-only and stays out of the log whichever way it ends.
            if (!sub.equals("check")) {
                audit(sender, "action=" + sub + " result=denied");
            }
            return true;
        }

        switch (sub) {
            case "reload":
                manager.reload();
                sender.sendMessage(messages().get("admin.reloaded"));
                audit(sender, "action=reload result=done");
                return true;
            case "check":
                handleCheck(sender, args);
                return true;
            case "reset":
                handleReset(sender, args);
                return true;
            case "givereroll":
                handleGiveReroll(sender, args);
                return true;
            case "add":
                handleAdd(sender, args);
                return true;
            default:
                // Unreachable: sub is one of SUBCOMMANDS, checked above, and
                // every case here matches one of them. Kept because the
                // compiler cannot prove that and requires this switch
                // statement to return on every path.
                sender.sendMessage(messages().get("admin.usage"));
                return true;
        }
    }

    private void openGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages().get("admin.players-only"));
            return;
        }
        if (!player.hasPermission("activity.use")) {
            player.sendMessage(messages().get("admin.no-permission"));
            return;
        }
        player.openInventory(gui.build(player));
    }

    private void handleReset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(messages().get("admin.usage"));
            audit(sender, "action=reset result=usage");
            return;
        }

        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) {
            audit(sender, "action=reset typed=" + quoted(args[1]) + " result=unknown-player");
            return;
        }

        PlayerData data = manager.getStore().get(target.getUniqueId());
        ActivityConfiguration.Keys keys = manager.getConfiguration().currentKeys();
        data.reset(keys.week(), keys.day());
        manager.getStore().markDirty();

        // The resolved name, not what was typed - casing and the cache decide
        // who was actually reset
        sender.sendMessage(messages().get("admin.reset-done", "%player%", name(target, args[1])));
        audit(sender, "action=reset " + who(target, args[1]) + " result=done");
    }

    // ====================================
    // /activity givereroll <player> gives one consumed reroll back - it does
    // NOT reroll anything, which is why it is not called 'reroll'.
    //
    // peek() rather than rolled(): a player with no row, and a player whose
    // stored day is not today, have both used no rerolls today - and asking
    // about them must neither put them in the file nor perform the rollover
    // as a side effect, which used to wipe a stale player's day while the
    // audit line said 'noop'. It is also what 'check' reports for them, so
    // the two commands cannot contradict each other.
    // ====================================
    private void handleGiveReroll(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(messages().get("admin.usage"));
            audit(sender, "action=givereroll result=usage");
            return;
        }

        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) {
            audit(sender, "action=givereroll typed=" + quoted(args[1]) + " result=unknown-player");
            return;
        }

        PlayerData data = manager.getStore().peek(target.getUniqueId());
        boolean today = isToday(data, manager.getConfiguration().currentKeys());
        int before = today ? data.rerolls() : 0;
        boolean given = today && data.refundReroll();
        if (given) {
            manager.getStore().markDirty();
        }

        int after = today ? data.rerolls() : 0;
        sender.sendMessage(given
            ? messages().get("admin.reroll-given", "%before%", before, "%after%", after,
                "%player%", name(target, args[1]))
            : messages().get("admin.reroll-none-used", "%player%", name(target, args[1])));
        audit(sender, "action=givereroll " + who(target, args[1]) + " rerolls=" + before + "->" + after
            + " result=" + (given ? "done" : "noop"));
    }

    // Whether this row's stored day is the one a rollover would leave it on.
    // A stale week implies a stale day: roll() wipes the day either way.
    private static boolean isToday(PlayerData data, ActivityConfiguration.Keys keys) {
        return data != null && data.weekKey().equals(keys.week()) && data.dayKey().equals(keys.day());
    }

    // ====================================
    // /activity check <player>: everything staff needs to answer "why did I
    // not get a point", and nothing else. peek() is the read-only lookup - it
    // neither creates a row for a player who has none nor rolls the one it
    // finds.
    //
    // The rollover is applied to the answer instead, the way PlaceholderHook
    // does it: a stored week that is not the current one has nothing on the
    // bar, a stale day has nothing done today and no draw. Printing the raw
    // stored numbers under a "Today" label described a day the player's next
    // login will wipe - and contradicted /activity givereroll, which reports
    // the same stale player as having used no rerolls. The stored keys are
    // still printed, and said to be stale, because a stale pair is itself the
    // answer to some reports.
    // ====================================
    private void handleCheck(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(messages().get("admin.usage"));
            return;
        }

        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) {
            return;
        }

        String player = name(target, args[1]);
        PlayerData data = manager.getStore().peek(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(messages().get("admin.check-no-data", "%player%", player));
            return;
        }

        ActivityConfiguration config = manager.getConfiguration();
        // One clock reading for both keys, so they cannot straddle a midnight
        // tick and disagree about which day this week it is
        ActivityConfiguration.Keys keys = config.currentKeys();
        boolean currentWeek = data.weekKey().equals(keys.week());
        boolean today = isToday(data, keys);
        // Clamped: peek() never runs the clamp rolled() does, so a lowered
        // bar.max would otherwise print "60/50"
        int points = currentWeek ? Math.min(data.points(), config.barMax()) : 0;
        int claimed = currentWeek ? Math.min(data.claimedPoints(), config.barMax()) : 0;

        sender.sendMessage(messages().get("admin.check-header", "%player%", player));
        if (!today) {
            sender.sendMessage(messages().get("admin.check-stale",
                "%week%", data.weekKey(), "%day%", data.dayKey()));
        }
        sender.sendMessage(messages().get("admin.check-points", "%points%", points,
            "%max%", config.barMax(), "%claimed%", claimed));
        // Clamped like the weekly points above: peek() never runs the
        // clamp rolled() does, so a lowered daily.max would otherwise print
        // "15/10" until the row is next touched.
        int dailyPoints = today ? Math.min(data.dailyPoints(), config.dailyMax()) : 0;
        sender.sendMessage(messages().get("admin.check-daily", "%points%", dailyPoints,
            "%max%", config.dailyMax()));
        sender.sendMessage(messages().get("admin.check-rerolls", "%used%", today ? data.rerolls() : 0,
            "%max%", config.rerollsPerDay()));
        sender.sendMessage(messages().get("admin.check-keys", "%week%", data.weekKey(),
            "%day%", data.dayKey()));

        List<String> tasks = today ? data.tasks() : List.of();
        if (tasks.isEmpty()) {
            sender.sendMessage(messages().get("admin.check-no-tasks"));
            return;
        }

        for (int slot = 0; slot < tasks.size(); slot++) {
            String id = tasks.get(slot);
            ActivityDef def = config.activity(id);
            // An id a reload has dropped still sits in the draw until the
            // player is next touched, so it is named by its raw id
            String display = def == null ? id : def.display();
            int count = data.count(id);
            // The display name goes in last: it is config text, and
            // substitution walks the pairs in order, so nothing in it can
            // stand in for a placeholder that has not been filled yet
            sender.sendMessage(data.isRevealed(id)
                ? messages().get("admin.check-task-revealed", "%slot%", slot + 1, "%count%", count,
                    "%points%", def == null ? 0 : def.worth(count), "%activity%", display)
                : messages().get("admin.check-task-hidden", "%slot%", slot + 1, "%activity%", display));
        }
    }

    // ====================================
    // One line per admin action that changed something, at INFO, after the
    // fact and carrying what came of it - a line in the log means it really
    // happened. 'check' is read-only and writes none; 'reload' does, because
    // "who swapped the config out from under us" is the same question.
    // Names are sanitised: they reach here from chat.
    // ====================================
    private void audit(CommandSender sender, String what) {
        manager.logger().info("ACTIVITY-AUDIT sender=" + quoted(sender.getName()) + " " + what);
    }

    private String who(OfflinePlayer target, String typed) {
        return "target=" + quoted(name(target, typed)) + " uuid=" + target.getUniqueId();
    }

    // ====================================
    // A value that came from a player goes into the line quoted. safeForLog
    // already stops a newline or an escape from forging a whole line, but it
    // leaves spaces and '=' alone - and a Geyser name, or a command block
    // renamed by a player, reaches here carrying both. Unquoted, a name like
    // 'Steve result=done' lands mid-line and defeats any parser reading the
    // last value of a key. A backslash or a quote inside the value is escaped,
    // so the closing quote cannot be forged either. uuid= needs none of this:
    // it cannot be anything but a uuid.
    // ====================================
    private static String quoted(String value) {
        return "\"" + Utils.safeForLog(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ====================================
    // The one read-only subcommand has its own permission; activity.admin
    // implies it through plugin.yml, so nothing an admin could do before
    // needs a config change. Package-private for the test.
    // ====================================
    static String permissionFor(String sub) {
        return sub.equals("check") ? "activity.check" : "activity.admin";
    }

    private void handleAdd(CommandSender sender, String[] args) {
        if (!wellFormedAdd(args)) {
            sender.sendMessage(messages().get("admin.usage"));
            audit(sender, "action=add result=usage");
            return;
        }

        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) {
            audit(sender, "action=add typed=" + quoted(args[1]) + " result=unknown-player");
            return;
        }

        int count;
        try {
            count = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(messages().get("admin.invalid-number", "%value%", args[3]));
            audit(sender, "action=add " + who(target, args[1]) + " result=invalid-number");
            return;
        }

        // A parseable number that is simply too big is a different mistake
        // from a typo, and saying "not a number" about 5000000 helps nobody
        if (count <= 0 || count > MAX_ADD) {
            sender.sendMessage(messages().get("admin.out-of-range", "%max%", MAX_ADD));
            audit(sender, "action=add " + who(target, args[1]) + " result=out-of-range");
            return;
        }

        Outcome outcome = add(target.getUniqueId(), args[2], count, forced(args));
        sender.sendMessage(switch (outcome) {
            case ADDED, CAPPED_ACTIVITY, CAPPED_DAILY, CAPPED_WEEKLY -> messages().get(outcome.messageKey(),
                "%count%", count, "%activity%", args[2], "%player%", name(target, args[1]));
            case NOT_A_TASK -> messages().get(outcome.messageKey(),
                "%activity%", args[2], "%player%", name(target, args[1]));
            case UNKNOWN_ACTIVITY -> messages().get(outcome.messageKey(), "%activity%", args[2]);
        });
        audit(sender, "action=add " + who(target, args[1]) + " activity=" + quoted(args[2])
            + " count=" + count + " force=" + forced(args) + " result=" + outcome);
    }

    // ====================================
    // What a /activity add did, and the message that says so. Every way an
    // add can come to nothing gets its own line: a task the player has not
    // revealed today (or an offline player with no row at all) records
    // nothing, and a spent daily budget, a full weekly bar or an activity
    // that has already given all it can today record the count but credit no
    // points - all of which used to be reported as a plain success.
    // ====================================
    enum Outcome {
        ADDED("admin.add-done"),
        NOT_A_TASK("admin.add-not-a-task"),
        CAPPED_ACTIVITY("admin.add-capped-activity"),
        CAPPED_DAILY("admin.add-capped-daily"),
        CAPPED_WEEKLY("admin.add-capped-weekly"),
        UNKNOWN_ACTIVITY("admin.unknown-activity");

        private final String messageKey;

        Outcome(String messageKey) {
            this.messageKey = messageKey;
        }

        String messageKey() {
            return messageKey;
        }
    }

    // ====================================
    // The add decision itself, split out so it can be tested without a
    // server. Gated like any listener unless --force was asked for, so a
    // console intake path cannot quietly hand out points for a task the
    // player never drew or revealed. The record path itself is what decides
    // between an unknown id, a refused gate and a cap - this only names the
    // message for what it reports.
    // ====================================
    Outcome add(UUID uuid, String activityId, int count, boolean force) {
        Recorded recorded = force
            ? manager.recordActionUngated(uuid, activityId, count)
            : manager.recordAction(uuid, activityId, count);

        return switch (recorded) {
            case RECORDED -> Outcome.ADDED;
            case NOT_A_TASK -> Outcome.NOT_A_TASK;
            case ACTIVITY_CAP -> Outcome.CAPPED_ACTIVITY;
            case DAILY_MAX -> Outcome.CAPPED_DAILY;
            case WEEKLY_MAX -> Outcome.CAPPED_WEEKLY;
            case UNKNOWN -> Outcome.UNKNOWN_ACTIVITY;
        };
    }

    // ====================================
    // add takes exactly player, activity, count and at most the one trailing
    // --force. Anything else in that position - '--froce', 'force', a stray
    // word after it - used to parse as a plain gated add, so a typo silently
    // changed what the command did. Package-private for the test.
    // ====================================
    static boolean wellFormedAdd(String[] args) {
        return args.length == 4 || (args.length == 5 && forced(args));
    }

    // ====================================
    // Whether 'add' was asked to skip the daily-task gate. Exact match: the
    // one argument that changes what the command does is not worth leaving
    // ambiguous, so '--FORCE' is a typo like any other and falls out of
    // wellFormedAdd as a usage error rather than quietly skipping the gate.
    // Package-private so it can be tested.
    // ====================================
    static boolean forced(String[] args) {
        return args.length > 4 && args[4].equals(FORCE);
    }

    // ====================================
    // Online first, then the user cache. Bukkit.getOfflinePlayer(String) is
    // never used: it blocks the server on a Mojang lookup and, failing that,
    // invents a UUID - so a typo would create an entry for a player that does
    // not exist and report success.
    // ====================================
    OfflinePlayer resolve(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null || !cached.hasPlayedBefore()) {
            sender.sendMessage(messages().get("admin.unknown-player", "%player%", name));
            return null;
        }
        return cached;
    }

    private String name(OfflinePlayer target, String typed) {
        return target.getName() == null ? typed : target.getName();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("activity.admin");
        if (!admin && !sender.hasPermission("activity.check")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filter(admin ? SUBCOMMANDS : READ_ONLY_SUBCOMMANDS, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        // A check-only sender is completed for 'check' and nothing else, so
        // the completer never hints at a subcommand it would be refused
        if (!sender.hasPermission(permissionFor(sub))) {
            return Collections.emptyList();
        }

        if (args.length == 2
            && (sub.equals("reset") || sub.equals("add") || sub.equals("check") || sub.equals("givereroll"))) {
            return filter(onlineNames(), args[1]);
        }

        if (args.length == 5 && sub.equals("add")) {
            return filter(List.of(FORCE), args[4]);
        }

        if (args.length == 3 && sub.equals("add")) {
            List<String> ids = new ArrayList<>();
            for (ActivityDef def : manager.getConfiguration().activities()) {
                ids.add(def.id());
            }
            return filter(ids, args[2]);
        }

        return Collections.emptyList();
    }

    // Package-private so the completer can be driven without a server
    List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private List<String> filter(List<String> options, String prefix) {
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                matches.add(option);
            }
        }
        return matches;
    }
}
