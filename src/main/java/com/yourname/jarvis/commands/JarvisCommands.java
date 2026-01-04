package com.yourname.jarvis.commands;

import com.yourname.jarvis.Jarvis;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;

public class JarvisCommands implements CommandExecutor {

    private final Jarvis plugin;

    public JarvisCommands(Jarvis plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // Reload works from console or player with permission
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (sender.hasPermission("jarvis.admin") || sender instanceof ConsoleCommandSender) {
                plugin.reload();
                sender.sendMessage(ChatColor.GREEN + "Jarvis reloaded successfully!");
                return true;
            } else {
                sender.sendMessage(ChatColor.RED + "You don't have permission.");
                return true;
            }
        }

        // Debug toggle can be called before player check
        if (args.length >= 1 && args[0].equalsIgnoreCase("debug")) {
            return handleDebug(sender, args);
        }

        // All other commands are player-only
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use other Jarvis commands.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(ChatColor.GOLD + "=== Jarvis Commands ===");
            player.sendMessage(ChatColor.YELLOW + "/jarvis summon   - Bring Jarvis to you");
            player.sendMessage(ChatColor.YELLOW + "/jarvis dismiss  - Send Jarvis away");
            player.sendMessage(ChatColor.YELLOW + "/jarvis return   - Warp Jarvis back");
            player.sendMessage(ChatColor.YELLOW + "/jarvis attack   - Fight mobs");
            player.sendMessage(ChatColor.YELLOW + "/jarvis mine     - Mine ores");
            player.sendMessage(ChatColor.YELLOW + "/jarvis loot     - Open inventory");
            player.sendMessage(ChatColor.YELLOW + "/jarvis bell     - Get controller bell");
            player.sendMessage(ChatColor.YELLOW + "/jarvis ask <q> - Ask the configured AI");
            player.sendMessage(ChatColor.YELLOW + "/jarvis debug <on|off> - Toggle debug logging (admin)");
            player.sendMessage(ChatColor.GREEN + "/jarvis reload   - Reload config (admin)");
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "summon" -> plugin.getJarvisNPC().summon(player);
            case "dismiss" -> plugin.getJarvisNPC().dismiss(player);
            case "return" -> plugin.getJarvisNPC().returnToPlayer(player);
            case "attack" -> plugin.getJarvisNPC().attack(player);
            case "mine" -> plugin.getJarvisNPC().mine(player);
            case "loot" -> plugin.getJarvisNPC().openInventory(player);
            case "bell" -> {
                player.getInventory().addItem(plugin.getControllerBell());
                player.sendMessage(ChatColor.GREEN + "Here's your Jarvis Controller bell!");
            }
            case "ask" -> handleAsk(player, args);
            case "debug" -> handleDebug(player, args);
            default -> player.sendMessage(ChatColor.RED + "Unknown command. Type /jarvis for help.");
        }
        return true;
    }

    private void handleAsk(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /jarvis ask <your question>");
            return;
        }
        String prompt = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        player.sendMessage(ChatColor.GOLD + "Jarvis is thinking with " + plugin.getAIConnector().getProviderName() + "...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String reply = plugin.getAIConnector().simpleChat(prompt);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.AQUA + "Jarvis AI: " + reply));
            } catch (Exception e) {
                plugin.getLogger().warning("AI ask failed: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.RED + "AI request failed: " + e.getMessage()));
            }
        });
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (!(sender.hasPermission("jarvis.admin") || sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to toggle debug.");
            return true;
        }
        if (args.length < 2 || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /jarvis debug <on|off>");
            return true;
        }
        boolean enable = args[1].equalsIgnoreCase("on");
        plugin.getDebugLogger().setEnabled(enable);
        sender.sendMessage(ChatColor.GREEN + "Jarvis debug mode is now " + (enable ? "ON" : "OFF"));
        return true;
    }
}
