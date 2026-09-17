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

    private static final List<String> SUBCOMMANDS = Arrays.asList("reload", "reset", "add");

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

        if (!sender.hasPermission("activity.admin")) {
            sender.sendMessage(messages().get("admin.no-permission"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload":
                manager.reload();
                sender.sendMessage(messages().get("admin.reloaded"));
                return true;
            case "reset":
                handleReset(sender, args);
                return true;
            case "add":
                handleAdd(sender, args);
                return true;
            default:
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
            return;
        }

        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) {
            return;
        }

        PlayerData data = manager.getStore().get(target.getUniqueId());
        ActivityConfiguration.Keys keys = manager.getConfiguration().currentKeys();
        data.reset(keys.week(), keys.day());
        manager.getStore().markDirty();

        // The resolved name, not what was typed - casing and the cache decide
        // who was actually reset
        sender.sendMessage(messages().get("admin.reset-done", "%player%", name(target, args[1])));
    }

    private void handleAdd(CommandSender sender, String[] args) {
        if (!wellFormedAdd(args)) {
            sender.sendMessage(messages().get("admin.usage"));
            return;
        }

        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) {
            return;
        }

        int count;
        try {
            count = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(messages().get("admin.invalid-number", "%value%", args[3]));
            return;
        }

        // A parseable number that is simply too big is a different mistake
        // from a typo, and saying "not a number" about 5000000 helps nobody
        if (count <= 0 || count > MAX_ADD) {
            sender.sendMessage(messages().get("admin.out-of-range", "%max%", MAX_ADD));
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
    private OfflinePlayer resolve(CommandSender sender, String name) {
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
        if (!sender.hasPermission("activity.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (args.length == 2 && (sub.equals("reset") || sub.equals("add"))) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return filter(names, args[1]);
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
