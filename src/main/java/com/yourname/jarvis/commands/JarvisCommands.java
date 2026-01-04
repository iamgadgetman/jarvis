package com.yourname.jarvis.commands;

import com.yourname.jarvis.Jarvis;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

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

        if (args.length >= 1 && args[0].equalsIgnoreCase("debug")) {
            if (sender.hasPermission("jarvis.admin") || sender instanceof ConsoleCommandSender) {
                plugin.printDebug(sender);
                return true;
            } else {
                sender.sendMessage(ChatColor.RED + "You don't have permission.");
                return true;
            }
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
            default -> player.sendMessage(ChatColor.RED + "Unknown command. Type /jarvis for help.");
        }
        return true;
    }
}
