package net.tfminecraft.activitytf.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import net.md_5.bungee.api.ChatColor;
import net.tfminecraft.activitytf.config.ActivityConfiguration;
import net.tfminecraft.activitytf.config.Messages;
import net.tfminecraft.activitytf.gui.ActivityGui;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.models.ActivityDef;
import net.tfminecraft.activitytf.models.PlayerData;
import net.tfminecraft.activitytf.models.RecordResult;
import net.tfminecraft.activitytf.utils.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ActivityCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
        Arrays.asList("reload", "check", "reset", "givereroll", "add", "addpoints");

    private static final List<String> READ_ONLY_SUBCOMMANDS = List.of("check");

    private static final String FORCE = "--force";

    private static final int MAX_ADD = 1_000_000;

    private static final String ADD_DONE_POINTS = "admin.add-done-points";

    private final ActivityManager manager;
    private final ActivityGui gui;

    public ActivityCommand(ActivityManager manager, ActivityGui gui) {
        this.manager = manager;
        this.gui = gui;
    }

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
        if (!SUBCOMMANDS.contains(sub)) {
            boolean anything = sender.hasPermission("activity.admin") || sender.hasPermission("activity.check");
            if (anything) {
                usage(sender);
            } else {
                sender.sendMessage(messages().get("admin.no-permission"));
            }
            return true;
        }

        if (!sender.hasPermission(permissionFor(sub))) {
            sender.sendMessage(messages().get("admin.no-permission"));
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
            case "addpoints":
                handleAddPoints(sender, args);
                return true;
            default:
                usage(sender);
                return true;
        }
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(messages().get("admin.usage"));
        sender.sendMessage(messages().get("admin.usage-addpoints"));
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
        Inventory inventory = gui.build(player);
        manager.claimDailyReward(player);
        player.openInventory(inventory);
    }

    private void handleReset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
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

        sender.sendMessage(messages().get("admin.reset-done", "%player%", name(target, args[1])));
        audit(sender, "action=reset " + who(target, args[1]) + " result=done");
    }

    private void handleGiveReroll(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
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

    private static boolean isToday(PlayerData data, ActivityConfiguration.Keys keys) {
        return data != null && data.weekKey().equals(keys.week()) && data.dayKey().equals(keys.day());
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private void handleCheck(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
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
        ActivityConfiguration.Keys keys = config.currentKeys();
        boolean currentWeek = data.weekKey().equals(keys.week());
        boolean today = isToday(data, keys);
        int points = currentWeek ? Math.min(data.points(), config.barMax()) : 0;
        int claimed = currentWeek ? Math.min(data.claimedPoints(), config.barMax()) : 0;

        sender.sendMessage(messages().get("admin.check-header", "%player%", player));
        if (!today) {
            sender.sendMessage(messages().get("admin.check-stale",
                "%week%", data.weekKey(), "%day%", data.dayKey()));
        }
        sender.sendMessage(messages().get("admin.check-points", "%points%", points,
            "%max%", config.barMax(), "%claimed%", claimed));
        int dailyPoints = today ? Math.min(data.dailyPoints(), config.dailyMax()) : 0;
        sender.sendMessage(messages().get("admin.check-daily", "%points%", dailyPoints,
            "%max%", config.dailyMax()));
        sender.sendMessage(messages().get("admin.check-rerolls", "%used%", today ? data.rerolls() : 0,
            "%max%", config.rerollsPerDay()));
        sender.sendMessage(messages().get(today && data.dailyRewardClaimed()
            ? "admin.check-daily-reward-claimed" : "admin.check-daily-reward-unclaimed"));
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
            String display = def == null ? id
                : ChatColor.stripColor(Utils.colorize(def.display()));
            int count = data.count(id);
            sender.sendMessage(data.isRevealed(id)
                ? messages().get("admin.check-task-revealed", "%slot%", slot + 1, "%count%", count,
                    "%points%", def == null ? 0 : def.rawWorth(count), "%activity%", display)
                : messages().get("admin.check-task-hidden", "%slot%", slot + 1, "%activity%", display));
        }
    }

    private void audit(CommandSender sender, String what) {
        manager.logger().info("ACTIVITY-AUDIT sender=" + quoted(sender.getName()) + " " + what);
    }

    private String who(OfflinePlayer target, String typed) {
        return "target=" + quoted(name(target, typed)) + " uuid=" + target.getUniqueId();
    }

    private static String quoted(String value) {
        return Utils.quotedForLog(value);
    }

    static String permissionFor(String sub) {
        return sub.equals("check") ? "activity.check" : "activity.admin";
    }

    private void handleAdd(CommandSender sender, String[] args) {
        if (!wellFormedAdd(args)) {
            usage(sender);
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

        if (count <= 0 || count > MAX_ADD) {
            sender.sendMessage(messages().get("admin.out-of-range", "%max%", MAX_ADD));
            audit(sender, "action=add " + who(target, args[1]) + " result=out-of-range");
            return;
        }

        Added added = add(target.getUniqueId(), args[2], count, forced(args));
        Outcome outcome = added.outcome();
        sender.sendMessage(switch (outcome) {
            case ADDED, CAPPED_ACTIVITY, CAPPED_DAILY, CAPPED_VOTE_SHARE, CAPPED_WEEKLY, CLAMPED_WEEKLY ->
                messages().get(
                    outcome == Outcome.ADDED && added.points() > 0 ? ADD_DONE_POINTS : outcome.messageKey(),
                    "%count%", count, "%activity%", args[2], "%player%", name(target, args[1]),
                    "%points%", added.points(), "%max%", manager.getConfiguration().barMax());
            case NOT_A_TASK -> messages().get(outcome.messageKey(),
                "%activity%", args[2], "%player%", name(target, args[1]));
            case UNKNOWN_ACTIVITY -> messages().get(outcome.messageKey(), "%activity%", args[2]);
        });
        audit(sender, "action=add " + who(target, args[1]) + " activity=" + quoted(args[2])
            + " count=" + count + " force=" + forced(args) + " points=" + added.points()
            + " result=" + outcome);
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private void handleAddPoints(CommandSender sender, String[] args) {
        if (args.length != 3) {
            usage(sender);
            audit(sender, "action=addpoints result=usage");
            return;
        }

        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) {
            audit(sender, "action=addpoints typed=" + quoted(args[1]) + " result=unknown-player");
            return;
        }

        int points;
        try {
            points = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            points = 0;
        }
        if (points <= 0) {
            sender.sendMessage(messages().get("admin.addpoints-invalid",
                "%value%", ChatColor.stripColor(Utils.colorize(args[2]))));
            audit(sender, "action=addpoints " + who(target, args[1]) + " value=" + quoted(args[2])
                + " result=invalid-number");
            return;
        }

        RecordResult result = manager.recordPoints(target.getUniqueId(), points);
        boolean all = result.pointsAwarded() == points;
        sender.sendMessage(messages().get(all ? "admin.addpoints-done" : "admin.addpoints-clamped",
            "%points%", result.pointsAwarded(), "%requested%", points,
            "%max%", manager.getConfiguration().barMax(), "%player%", name(target, args[1])));
        audit(sender, "action=addpoints " + who(target, args[1]) + " requested=" + points
            + " points=" + result.pointsAwarded() + " result=" + result.outcome());
    }

    enum Outcome {
        ADDED("admin.add-done"),
        NOT_A_TASK("admin.add-not-a-task"),
        CAPPED_ACTIVITY("admin.add-capped-activity"),
        CAPPED_DAILY("admin.add-capped-daily"),
        CAPPED_VOTE_SHARE("admin.add-capped-vote-share"),
        CAPPED_WEEKLY("admin.add-capped-weekly"),
        CLAMPED_WEEKLY("admin.add-clamped-weekly"),
        UNKNOWN_ACTIVITY("admin.unknown-activity");

        private final String messageKey;

        Outcome(String messageKey) {
            this.messageKey = messageKey;
        }

        String messageKey() {
            return messageKey;
        }
    }

    Added add(UUID uuid, String activityId, int count, boolean force) {
        RecordResult result = manager.recordAdmin(uuid, activityId, count, force);

        Outcome outcome = switch (result.outcome()) {
            case RECORDED -> Outcome.ADDED;
            case NOT_A_TASK -> Outcome.NOT_A_TASK;
            case ACTIVITY_CAP -> Outcome.CAPPED_ACTIVITY;
            case DAILY_MAX -> Outcome.CAPPED_DAILY;
            case VOTE_SHARE -> Outcome.CAPPED_VOTE_SHARE;
            case WEEKLY_MAX -> Outcome.CAPPED_WEEKLY;
            case WEEKLY_CLAMPED -> Outcome.CLAMPED_WEEKLY;
            case UNKNOWN -> Outcome.UNKNOWN_ACTIVITY;
        };
        return new Added(outcome, result.pointsAwarded());
    }

    record Added(Outcome outcome, int points) {
    }

    static boolean wellFormedAdd(String[] args) {
        return args.length == 4 || (args.length == 5 && forced(args));
    }

    static boolean forced(String[] args) {
        return args.length > 4 && args[4].equals(FORCE);
    }

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
        if (!sender.hasPermission(permissionFor(sub))) {
            return Collections.emptyList();
        }

        if (args.length == 2
            && (sub.equals("reset") || sub.equals("add") || sub.equals("addpoints") || sub.equals("check")
                || sub.equals("givereroll"))) {
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
