package net.tfminecraft.activitytf.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import net.tfminecraft.activitytf.config.Messages;
import net.tfminecraft.activitytf.config.ReferralConfiguration.Act;
import net.tfminecraft.activitytf.config.ReferralConfiguration.Milestone;
import net.tfminecraft.activitytf.config.ReferralConfiguration.RareStep;
import net.tfminecraft.activitytf.gui.ReferralGui;
import net.tfminecraft.activitytf.managers.ActivityManager;
import net.tfminecraft.activitytf.managers.ReferralManager;
import net.tfminecraft.activitytf.managers.ReferralManager.Link;
import net.tfminecraft.activitytf.managers.ReferralManager.Side;
import net.tfminecraft.activitytf.models.ReferralData;
import net.tfminecraft.activitytf.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /referral [invitedby <name>], plus the staff moderation subcommands (see /referral help). */
public class ReferralCommand implements CommandExecutor, TabCompleter {

    static final String USE = "activity.referral";
    static final String ADMIN = "activity.referral.admin";

    private static final List<String> PLAYER_SUBCOMMANDS = List.of("invitedby");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("help", "info", "link", "unlink", "addtime",
        "settime", "unclaim", "markclaimed", "rare", "hold", "release", "allowip");
    private static final List<String> RARE_ACTIONS = List.of("unclaim", "markclaimed", "exclude", "include");
    private static final List<String> SIDES = List.of("new", "inviter", "both");
    private static final int MAX_ADD_MINUTES = 100_000;
    private static final int MAX_SET_MINUTES = 1_000_000;

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
        if (sub.equals("help") && !sender.hasPermission(ADMIN)) {
            sender.sendMessage(messages().get("referral.usage"));
            return true;
        }
        if (ADMIN_SUBCOMMANDS.contains(sub)) {
            if (!sender.hasPermission(ADMIN)) {
                sender.sendMessage(messages().get("admin.no-permission"));
                return true;
            }
            switch (sub) {
                case "help" -> messages().lines("referral.admin.help").forEach(sender::sendMessage);
                case "info" -> info(sender, args);
                case "link" -> link(sender, args);
                case "unlink" -> unlink(sender, args);
                case "settime" -> setTime(sender, args);
                case "unclaim", "markclaimed" -> claims(sender, args, sub.equals("unclaim"));
                case "rare" -> rare(sender, args);
                case "hold", "release" -> hold(sender, args, sub.equals("hold"));
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
        return target(sender, args, length, 1);
    }

    private UUID target(CommandSender sender, String[] args, int length, int index) {
        if (args.length != length) {
            sender.sendMessage(messages().get("referral.usage-admin"));
            return null;
        }
        return account(sender, args[index]);
    }

    private UUID account(CommandSender sender, String name) {
        UUID uuid = referrals().find(name);
        if (uuid == null) {
            sender.sendMessage(messages().get("referral.admin.unknown", "%name%", name));
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
            invited.add(other.name() + " (" + other.minutes() / 60 + "h, claimed " + other.inviterClaimed() + ")");
        }
        List<String> counted = new ArrayList<>();
        for (UUID invitee : referrals().completedInvitees(uuid)) {
            counted.add(referrals().getStore().peek(invitee).name());
        }
        List<String> flags = new ArrayList<>();
        if (data.held()) {
            flags.add("held");
        }
        if (data.rareExcluded()) {
            flags.add("rare-excluded");
        }
        sender.sendMessage(messages().get("referral.admin.info", "%name%", data.name(), "%minutes%", data.minutes(),
            "%hours%", data.minutes() / 60, "%inviter%", inviter == null ? "-" : inviter.name(),
            "%claimed%", data.claimed(), "%inviter-claimed%", data.inviterClaimed(),
            "%invited%", invited.isEmpty() ? "-" : String.join(", ", invited),
            "%completed%", counted.size(), "%counted%", counted.isEmpty() ? "-" : String.join(", ", counted),
            "%rare%", data.rareClaimed(), "%counted-for%", name(data.rareCountedFor()),
            "%flags%", flags.isEmpty() ? "-" : String.join(", ", flags)));
    }

    private String name(UUID uuid) {
        ReferralData data = uuid == null ? null : referrals().getStore().peek(uuid);
        return data == null ? "-" : data.name();
    }

    private void link(CommandSender sender, String[] args) {
        UUID uuid = target(sender, args, 3);
        if (uuid == null) {
            return;
        }
        UUID inviter = account(sender, args[2]);
        if (inviter == null) {
            return;
        }
        UUID before = referrals().getStore().peek(uuid).invitedBy();
        Link result = referrals().staffLink(uuid, inviter);
        String key = switch (result) {
            case LINKED -> "referral.admin.linked";
            case ALREADY_LINKED -> "referral.admin.already-linked";
            case SELF -> "referral.admin.link-self";
            case LOOP -> "referral.admin.link-loop";
            default -> "referral.admin.link-failed";
        };
        sender.sendMessage(messages().get(key, "%name%", args[1], "%inviter%", args[2]));
        audit(sender, "action=staff-link target=" + uuid + " from=" + before + " inviter=" + inviter
            + " result=" + (result == Link.FAILED ? "save-failed" : result.name().toLowerCase(Locale.ROOT)));
    }

    private void unlink(CommandSender sender, String[] args) {
        UUID uuid = target(sender, args, 2);
        if (uuid == null) {
            return;
        }
        UUID before = referrals().getStore().peek(uuid).invitedBy();
        int done = referrals().unlink(uuid);
        String result = done == ReferralManager.SAVE_FAILED ? "save-failed" : done > 0 ? "done" : "not-linked";
        sender.sendMessage(messages().get(done == ReferralManager.SAVE_FAILED ? "referral.admin.save-failed"
            : done > 0 ? "referral.admin.unlinked" : "referral.admin.not-linked", "%name%", args[1]));
        audit(sender, "action=unlink target=" + uuid + " from=" + before + " result=" + result);
    }

    private void allowIp(CommandSender sender, String[] args) {
        UUID one = target(sender, args, 3);
        if (one == null) {
            return;
        }
        UUID other = account(sender, args[2]);
        if (other == null) {
            return;
        }
        if (one.equals(other)) {
            sender.sendMessage(messages().get("referral.usage-admin"));
            return;
        }
        int allowed = referrals().toggleIpPair(one, other);
        if (allowed == ReferralManager.SAVE_FAILED) {
            sender.sendMessage(messages().get("referral.admin.save-failed"));
            audit(sender, "action=allowip target=" + one + " other=" + other + " result=save-failed");
            return;
        }
        sender.sendMessage(messages().get(allowed > 0 ? "referral.admin.ip-pair-on" : "referral.admin.ip-pair-off",
            "%name%", args[1], "%other%", args[2]));
        audit(sender, "action=allowip target=" + one + " other=" + other + " allowed=" + (allowed > 0)
            + " result=done");
    }

    private void addTime(CommandSender sender, String[] args) {
        UUID uuid = target(sender, args, 3);
        if (uuid == null) {
            return;
        }
        int minutes = number(args[2]);
        if (minutes == 0 || Math.abs(minutes) > MAX_ADD_MINUTES) {
            sender.sendMessage(messages().get("referral.usage-admin"));
            return;
        }
        int before = referrals().getStore().peek(uuid).minutes();
        timeSet(sender, args[1], uuid, "addtime", before,
            referrals().setMinutes(uuid, (int) Math.min(Integer.MAX_VALUE, (long) before + minutes)));
    }

    private void setTime(CommandSender sender, String[] args) {
        UUID uuid = target(sender, args, 3);
        if (uuid == null) {
            return;
        }
        int minutes = number(args[2]);
        if (minutes < 0 || minutes > MAX_SET_MINUTES || minutes == 0 && !args[2].strip().equals("0")) {
            sender.sendMessage(messages().get("referral.usage-admin"));
            return;
        }
        int before = referrals().getStore().peek(uuid).minutes();
        timeSet(sender, args[1], uuid, "settime", before, referrals().setMinutes(uuid, minutes));
    }

    private void timeSet(CommandSender sender, String name, UUID uuid, String action, int before, int after) {
        if (after == ReferralManager.SAVE_FAILED) {
            sender.sendMessage(messages().get("referral.admin.save-failed"));
            audit(sender, "action=" + action + " target=" + uuid + " result=save-failed");
            return;
        }
        sender.sendMessage(messages().get("referral.admin.time-added", "%name%", name, "%minutes%", after));
        audit(sender, "action=" + action + " target=" + uuid + " minutes=" + before + "->" + after + " result=done");
    }

    // The staff answer for a change count: an error code's message, or {@code done} with its placeholders.
    private String result(int changed, String missing) {
        return changed == ReferralManager.SAVE_FAILED ? "save-failed" : changed < 0 ? missing : "done";
    }

    /** unclaim|markclaimed <player> <hours|all> <new|inviter|both> */
    private void claims(CommandSender sender, String[] args, boolean unclaim) {
        UUID uuid = target(sender, args, 4);
        if (uuid == null) {
            return;
        }
        Integer hours = which(args[2]);
        Side side = side(args[3]);
        if (hours != null && hours <= 0 || side == null) {
            sender.sendMessage(messages().get("referral.usage-admin"));
            return;
        }
        ReferralData data = referrals().getStore().peek(uuid);
        String before = "new=" + data.claimed() + " inviter=" + data.inviterClaimed();
        int changed = unclaim ? referrals().unclaim(uuid, hours, side) : referrals().markClaimed(uuid, hours, side);
        String what = hours == null ? "all" : hours + "h";
        String result = result(changed, "no-milestone");
        String key = !result.equals("done") ? "referral.admin." + result
            : unclaim ? "referral.admin.unclaimed" : "referral.admin.marked";
        sender.sendMessage(messages().get(key, "%name%", args[1], "%which%", what,
            "%side%", side.name().toLowerCase(Locale.ROOT), "%count%", Math.max(0, changed)));
        audit(sender, "action=" + (unclaim ? "unclaim" : "markclaimed") + " target=" + uuid + " milestone=" + what
            + " side=" + side.name().toLowerCase(Locale.ROOT) + " changed=" + Math.max(0, changed)
            + " before=" + Utils.quotedForLog(before) + " after="
            + Utils.quotedForLog("new=" + data.claimed() + " inviter=" + data.inviterClaimed()) + " result=" + result);
    }

    /** rare unclaim|markclaimed <inviter> <players|all>, rare exclude|include <player> */
    private void rare(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        if (action.equals("exclude") || action.equals("include")) {
            UUID uuid = target(sender, args, 3, 2);
            if (uuid == null) {
                return;
            }
            boolean exclude = action.equals("exclude");
            flag(sender, args[2], uuid, "rare-" + action, referrals().setRareExcluded(uuid, exclude),
                exclude ? "referral.admin.rare-excluded" : "referral.admin.rare-included");
            return;
        }
        if (!action.equals("unclaim") && !action.equals("markclaimed")) {
            sender.sendMessage(messages().get("referral.usage-admin"));
            return;
        }
        UUID uuid = target(sender, args, 4, 2);
        if (uuid == null) {
            return;
        }
        Integer players = which(args[3]);
        if (players != null && players <= 0) {
            sender.sendMessage(messages().get("referral.usage-admin"));
            return;
        }
        boolean unclaim = action.equals("unclaim");
        ReferralData data = referrals().getStore().peek(uuid);
        String before = String.valueOf(data.rareClaimed());
        int changed = unclaim ? referrals().rareUnclaim(uuid, players) : referrals().rareMarkClaimed(uuid, players);
        String what = players == null ? "all" : String.valueOf(players);
        String result = result(changed, "no-rare-step");
        String key = !result.equals("done") ? "referral.admin." + result
            : unclaim ? "referral.admin.rare-unclaimed" : "referral.admin.rare-marked";
        sender.sendMessage(messages().get(key, "%name%", args[2], "%which%", what, "%count%", Math.max(0, changed)));
        audit(sender, "action=rare-" + action + " target=" + uuid + " step=" + what + " changed=" + Math.max(0, changed)
            + " before=" + before + " after=" + data.rareClaimed() + " result=" + result);
    }

    private void hold(CommandSender sender, String[] args, boolean hold) {
        UUID uuid = target(sender, args, 2);
        if (uuid == null) {
            return;
        }
        flag(sender, args[1], uuid, hold ? "hold" : "release", referrals().setHeld(uuid, hold),
            hold ? "referral.admin.held" : "referral.admin.released");
    }

    private void flag(CommandSender sender, String name, UUID uuid, String action, int changed, String done) {
        String result = changed == 0 ? "unchanged" : result(changed, "unchanged");
        sender.sendMessage(messages().get(result.equals("done") ? done : "referral.admin." + result, "%name%", name));
        audit(sender, "action=" + action + " target=" + uuid + " result=" + result);
    }

    // "all" = null; anything that is not a whole number = -1 (refused by the callers).
    private static Integer which(String value) {
        if (value.equalsIgnoreCase("all")) {
            return null;
        }
        int number = number(value);
        return number == 0 ? -1 : number;
    }

    private static int number(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Side side(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "new" -> Side.NEW;
            case "inviter" -> Side.INVITER;
            case "both" -> Side.BOTH;
            default -> null;
        };
    }

    private void audit(CommandSender sender, String what) {
        manager.logger().info("REFERRAL-AUDIT sender=" + Utils.quotedForLog(sender.getName()) + " " + what);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        boolean admin = sender.hasPermission(ADMIN);
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            options.addAll(PLAYER_SUBCOMMANDS);
            if (admin) {
                options.addAll(ADMIN_SUBCOMMANDS);
            }
        } else if (args.length == 2 && sub.equals("invitedby")) {
            options.addAll(players(sender));
        } else if (admin && ADMIN_SUBCOMMANDS.contains(sub)) {
            options.addAll(adminOptions(sender, sub, args));
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }

    private List<String> adminOptions(CommandSender sender, String sub, String[] args) {
        int at = args.length;
        switch (sub) {
            case "help" -> {
                return List.of();
            }
            case "rare" -> {
                if (at == 2) {
                    return RARE_ACTIONS;
                }
                if (at == 3) {
                    return players(sender);
                }
                if (at == 4 && !args[1].equalsIgnoreCase("exclude") && !args[1].equalsIgnoreCase("include")) {
                    List<String> steps = new ArrayList<>();
                    Act act = referrals().act();
                    if (act != null) {
                        for (RareStep step : act.rareTrack()) {
                            steps.add(String.valueOf(step.players()));
                        }
                    }
                    steps.add("all");
                    return steps;
                }
                return List.of();
            }
            case "unclaim", "markclaimed" -> {
                if (at == 2) {
                    return players(sender);
                }
                if (at == 3) {
                    List<String> hours = new ArrayList<>();
                    Act act = referrals().act();
                    if (act != null) {
                        for (Milestone milestone : act.milestones()) {
                            hours.add(String.valueOf(milestone.hours()));
                        }
                    }
                    hours.add("all");
                    return hours;
                }
                return at == 4 ? SIDES : List.of();
            }
            case "link", "allowip" -> {
                return at == 2 || at == 3 ? players(sender) : List.of();
            }
            default -> {
                return at == 2 ? players(sender) : List.of();
            }
        }
    }

    private static List<String> players(CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!(sender instanceof Player player) || player.canSee(online)) {
                names.add(online.getName());
            }
        }
        return names;
    }
}
