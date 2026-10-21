package net.tfminecraft.activitytf.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import net.tfminecraft.activitytf.config.Messages;
import net.tfminecraft.activitytf.gui.ReferralGui;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.managers.ReferralManager;
import net.tfminecraft.activitytf.models.ReferralData;
import net.tfminecraft.activitytf.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /referral [invitedby <name>], plus info/unlink/addtime for staff. */
public class ReferralCommand implements CommandExecutor, TabCompleter {

    static final String USE = "activity.referral";
    static final String ADMIN = "activity.referral.admin";

    private static final List<String> PLAYER_SUBCOMMANDS = List.of("invitedby");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("info", "unlink", "addtime", "allowip");
    private static final int MAX_ADD_MINUTES = 100_000;

    private final ActivityManager manager;
    private final ReferralGui gui;

    public ReferralCommand(ActivityManager manager, ReferralGui gui) {
        this.manager = manager;
        this.gui = gui;
    }

    private Messages messages() {
        return manager.getConfiguration().messages();
    }

    private ReferralManager referrals() {
        return manager.referrals();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (ADMIN_SUBCOMMANDS.contains(sub)) {
            if (!sender.hasPermission(ADMIN)) {
                sender.sendMessage(messages().get("admin.no-permission"));
                return true;
            }
            switch (sub) {
                case "info" -> info(sender, args);
                case "unlink" -> unlink(sender, args);
                case "allowip" -> allowIp(sender, args);
                default -> addTime(sender, args);
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages().get("admin.players-only"));
            return true;
        }
        if (!player.hasPermission(USE)) {
            player.sendMessage(messages().get("admin.no-permission"));
            return true;
        }
        if (sub.isEmpty()) {
            gui.open(player);
        } else if (sub.equals("invitedby") && args.length == 2) {
            gui.confirmTyped(player, args[1]);
        } else {
            player.sendMessage(messages().get("referral.usage"));
            if (player.hasPermission(ADMIN)) {
                player.sendMessage(messages().get("referral.usage-admin"));
            }
        }
        return true;
    }

    private UUID target(CommandSender sender, String[] args, int length) {
        if (args.length != length) {
            sender.sendMessage(messages().get("referral.usage-admin"));
            return null;
        }
        UUID uuid = referrals().find(args[1]);
        if (uuid == null) {
            sender.sendMessage(messages().get("referral.admin.unknown", "%name%", args[1]));
        }
        return uuid;
    }

    private void info(CommandSender sender, String[] args) {
        UUID uuid = target(sender, args, 2);
        if (uuid == null) {
            return;
        }
        ReferralData data = referrals().getStore().peek(uuid);
        ReferralData inviter = data.invitedBy() == null ? null : referrals().getStore().peek(data.invitedBy());
        List<String> invited = new ArrayList<>();
        for (UUID invitee : referrals().invitees(uuid)) {
            ReferralData other = referrals().getStore().peek(invitee);
            invited.add(other.name() + " (" + other.minutes() / 60 + "h)");
        }
        sender.sendMessage(messages().get("referral.admin.info", "%name%", data.name(), "%minutes%", data.minutes(),
            "%hours%", data.minutes() / 60, "%inviter%", inviter == null ? "-" : inviter.name(),
            "%claimed%", data.claimed(), "%invited%", invited.isEmpty() ? "-" : String.join(", ", invited),
            "%completed%", referrals().completedInvitees(uuid).size(), "%rare%", data.rareClaimed(),
            "%counted-for%", name(data.rareCountedFor())));
    }

    private String name(UUID uuid) {
        ReferralData data = uuid == null ? null : referrals().getStore().peek(uuid);
        return data == null ? "-" : data.name();
    }

    private void unlink(CommandSender sender, String[] args) {
        UUID uuid = target(sender, args, 2);
        if (uuid == null) {
            return;
        }
        boolean done = referrals().unlink(uuid);
        sender.sendMessage(messages().get(done ? "referral.admin.unlinked" : "referral.admin.not-linked",
            "%name%", args[1]));
        audit(sender, "action=unlink target=" + uuid + " result=" + (done ? "done" : "not-linked"));
    }

    private void allowIp(CommandSender sender, String[] args) {
        UUID one = target(sender, args, 3);
        if (one == null) {
            return;
        }
        UUID other = referrals().find(args[2]);
        if (other == null) {
            sender.sendMessage(messages().get("referral.admin.unknown", "%name%", args[2]));
            return;
        }
        if (one.equals(other)) {
            sender.sendMessage(messages().get("referral.usage-admin"));
            return;
        }
        boolean allowed = referrals().toggleIpPair(one, other);
        sender.sendMessage(messages().get(allowed ? "referral.admin.ip-pair-on" : "referral.admin.ip-pair-off",
            "%name%", args[1], "%other%", args[2]));
        audit(sender, "action=allowip target=" + one + " other=" + other + " allowed=" + allowed + " result=done");
    }

    private void addTime(CommandSender sender, String[] args) {
        UUID uuid = target(sender, args, 3);
        if (uuid == null) {
            return;
        }
        int minutes;
        try {
            minutes = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            minutes = 0;
        }
        if (minutes == 0 || Math.abs(minutes) > MAX_ADD_MINUTES) {
            sender.sendMessage(messages().get("referral.usage-admin"));
            return;
        }
        ReferralData data = referrals().getStore().peek(uuid);
        int before = data.minutes();
        data.setMinutes((int) Math.min(Integer.MAX_VALUE, (long) before + minutes));
        referrals().getStore().markDirty();
        referrals().noteCompletions();
        sender.sendMessage(messages().get("referral.admin.time-added", "%name%", data.name(),
            "%minutes%", data.minutes()));
        audit(sender, "action=addtime target=" + uuid + " minutes=" + before + "->" + data.minutes() + " result=done");
    }

    private void audit(CommandSender sender, String what) {
        manager.logger().info("REFERRAL-AUDIT sender=" + Utils.quotedForLog(sender.getName()) + " " + what);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(PLAYER_SUBCOMMANDS);
            if (sender.hasPermission(ADMIN)) {
                options.addAll(ADMIN_SUBCOMMANDS);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("invitedby")
            || sender.hasPermission(ADMIN) && ADMIN_SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT)))
            || args.length == 3 && args[0].equalsIgnoreCase("allowip") && sender.hasPermission(ADMIN)) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!(sender instanceof Player player) || player.canSee(online)) {
                    options.add(online.getName());
                }
            }
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
