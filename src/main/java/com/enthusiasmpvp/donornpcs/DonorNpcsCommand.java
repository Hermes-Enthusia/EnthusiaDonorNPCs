package com.enthusiasmpvp.donornpcs;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DonorNpcsCommand implements CommandExecutor, TabCompleter {
    private static final String PERMISSION = "enthusiadonornpcs.admin";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());

    private final EnthusiaDonorNPCsPlugin plugin;

    public DonorNpcsCommand(EnthusiaDonorNPCsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length != 1) {
            sendUsage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadPluginConfig();
                plugin.forceUpdate();
                sender.sendMessage(ChatColor.GREEN + "EnthusiaDonorNPCs config reloaded and skins force-updated.");
                return true;
            }
            case "update" -> {
                plugin.forceUpdate();
                sender.sendMessage(ChatColor.GREEN + "EnthusiaDonorNPCs skin update started.");
                return true;
            }
            case "status" -> {
                sendStatus(sender);
                return true;
            }
            default -> {
                sendUsage(sender, label);
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION) || args.length != 1) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> results = new ArrayList<>();
        for (String option : List.of("reload", "update", "status")) {
            if (option.startsWith(prefix)) {
                results.add(option);
            }
        }
        return results;
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <reload|update|status>");
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "EnthusiaDonorNPCs status:");
        if (plugin.updater().statuses().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No NPCs are configured.");
            return;
        }

        for (UpdateStatus status : plugin.updater().statuses()) {
            LeaderboardEntry entry = status.entry();
            String success = status.lastSuccessful() ? ChatColor.GREEN + "OK" : ChatColor.RED + "ERROR";
            String lastAttempt = status.lastAttemptAt() == null ? "never" : TIME_FORMAT.format(status.lastAttemptAt());

            sender.sendMessage(ChatColor.AQUA + entry.leaderboardDisplayName()
                    + ChatColor.GRAY + " #" + entry.position()
                    + " | NPC " + entry.npcId()
                    + " | facing " + entry.facingDirection().name().toLowerCase(Locale.ROOT)
                    + " | name " + printable(entry.namePlaceholder())
                    + " | uuid " + printable(entry.uuidPlaceholder()));
            sender.sendMessage(ChatColor.GRAY + "  desired=" + printable(status.lastDesiredSkinName())
                    + ", applied=" + printable(status.lastAppliedSkinName())
                    + ", raw=" + printable(status.lastPlaceholderValue())
                    + ", status=" + success
                    + ChatColor.GRAY + ", last=" + lastAttempt
                    + ", message=" + status.lastMessage());
        }
    }

    private String printable(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }
}
