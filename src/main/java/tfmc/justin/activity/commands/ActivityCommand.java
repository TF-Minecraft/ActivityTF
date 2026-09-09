package tfmc.justin.activity.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import tfmc.justin.activity.config.Messages;
import tfmc.justin.activity.gui.ActivityGui;
import tfmc.justin.activity.managers.ActivityManager;
import tfmc.justin.activity.models.ActivityDef;
import tfmc.justin.activity.models.PlayerData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

// ====================================
// /activity opens the GUI; the subcommands are admin-only.
// 'add' doubles as the intake path for ConditionalEvents and anything else
// that can dispatch a console command.
// ====================================
public class ActivityCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList("reload", "reset", "add");

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
        data.reset(manager.getConfiguration().currentWeekKey(), manager.getConfiguration().currentDayKey());
        manager.getStore().markDirty();

        // The resolved name, not what was typed - casing and the cache decide
        // who was actually reset
        sender.sendMessage(messages().get("admin.reset-done", "%player%", name(target, args[1])));
    }

    private void handleAdd(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(messages().get("admin.usage"));
            return;
        }

        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) {
            return;
        }

        ActivityDef def = manager.getConfiguration().activity(args[2]);
        if (def == null) {
            sender.sendMessage(messages().get("admin.unknown-activity", "%activity%", args[2]));
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

        UUID uuid = target.getUniqueId();
        if (!manager.recordAction(uuid, def.id(), count)) {
            sender.sendMessage(messages().get("admin.unknown-activity", "%activity%", args[2]));
            return;
        }

        sender.sendMessage(messages().get("admin.add-done",
            "%count%", count, "%activity%", def.id(), "%player%", name(target, args[1])));
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
