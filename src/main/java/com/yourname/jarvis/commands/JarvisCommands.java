package com.yourname.jarvis.commands;

import com.yourname.jarvis.Jarvis;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

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
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use other Jarvis commands.");
            return true;
        }
        
        Player player = (Player) sender;

        if (args.length == 0) {
            showHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "summon" -> plugin.getJarvisNPC().summon(player);
            case "dismiss" -> plugin.getJarvisNPC().dismiss(player);
            case "return" -> plugin.getJarvisNPC().returnToPlayer(player);
            case "attack" -> plugin.getJarvisNPC().attack(player);
            case "mine" -> {
                // Pass additional args for ore type
                if (args.length > 1) {
                    String[] mineArgs = new String[args.length - 1];
                    System.arraycopy(args, 1, mineArgs, 0, args.length - 1);
                    plugin.getJarvisNPC().mine(player, mineArgs);
                } else {
                    plugin.getJarvisNPC().mine(player);
                }
            }
            case "stop" -> plugin.getJarvisNPC().stop(player);
            case "battle" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /jarvis battle <player>");
                    return true;
                }
                Player target = plugin.getServer().getPlayer(args[1]);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "Player not found!");
                    return true;
                }
                if (target.equals(player)) {
                    player.sendMessage(ChatColor.RED + "You can't battle yourself!");
                    return true;
                }
                plugin.getJarvisNPC().battle(player, target);
            }
            case "loot" -> plugin.getJarvisNPC().openInventory(player);
            case "bell" -> {
                player.getInventory().addItem(plugin.getControllerBell());
                player.sendMessage(ChatColor.GREEN + "Here's your Jarvis Controller bell!");
            }
            
            // Building commands
            case "build" -> {
                if (plugin.getSchematicManager() == null) {
                    player.sendMessage(ChatColor.RED + "Building system not available (WorldEdit required)");
                    return true;
                }
                
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /jarvis build <description>");
                    player.sendMessage(ChatColor.GRAY + "Example: /jarvis build small house");
                    return true;
                }
                
                // Join remaining args as description
                StringBuilder description = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    description.append(args[i]).append(" ");
                }
                
                // Use AI to select and build schematic
                plugin.getSchematicManager().buildWithAI(player, description.toString().trim());
            }
            
            case "cancelbuild" -> {
                if (plugin.getBuildingAssistant() == null) {
                    player.sendMessage(ChatColor.RED + "Building assistant not available");
                    return true;
                }
                plugin.getBuildingAssistant().cancelBuild(player);
            }
            
            // Schematic management commands
            case "schematics", "schematic" -> {
                if (plugin.getSchematicManager() == null) {
                    player.sendMessage(ChatColor.RED + "Schematic manager not available (WorldEdit required)");
                    return true;
                }
                
                if (args.length < 2) {
                    plugin.getSchematicManager().listSchematics(player);
                    return true;
                }
                
                String schematicSub = args[1].toLowerCase();
                switch (schematicSub) {
                    case "list" -> plugin.getSchematicManager().listSchematics(player);
                    
                    case "scan", "reload" -> {
                        player.sendMessage(ChatColor.GOLD + "Jarvis: Scanning for new schematics...");
                        plugin.getSchematicManager().scanFolder();
                        player.sendMessage(ChatColor.GREEN + "Jarvis: Scan complete! Found " + 
                                plugin.getSchematicManager().getSchematics().size() + " schematics.");
                    }
                    
                    case "download" -> {
                        if (args.length < 4) {
                            player.sendMessage(ChatColor.RED + "Usage: /jarvis schematics download <url> <name>");
                            player.sendMessage(ChatColor.GRAY + "Example: /jarvis schematics download https://example.com/house.schem myhouse");
                            return true;
                        }
                        String url = args[2];
                        String name = args[3];
                        plugin.getSchematicManager().downloadSchematic(player, url, name);
                    }
                    
                    case "folder" -> {
                        player.sendMessage(ChatColor.GOLD + "Schematics folder: " + 
                                ChatColor.YELLOW + plugin.getSchematicManager().getSchematicFolder().toString());
                        player.sendMessage(ChatColor.GRAY + "Place .schem or .schematic files here and use /jarvis schematics scan");
                    }
                    
                    default -> player.sendMessage(ChatColor.RED + "Unknown schematic command. Use: list, scan, download, folder");
                }
            }
            
            // Ask command - simple Q&A with AI
            case "ask" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /jarvis ask <question>");
                    player.sendMessage(ChatColor.GRAY + "Example: /jarvis ask what are the best mining levels?");
                    return true;
                }
                
                // Join remaining args as question
                StringBuilder question = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    question.append(args[i]).append(" ");
                }
                
                player.sendMessage(ChatColor.GOLD + "Jarvis: Let me think...");
                
                // Query AI asynchronously
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            String answer = plugin.getAIConnector().sendSimpleRequest(question.toString().trim());
                            
                            // Send answer on main thread
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    player.sendMessage(ChatColor.AQUA + "Jarvis: " + ChatColor.WHITE + answer);
                                }
                            }.runTask(plugin);
                            
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to get AI answer: " + e.getMessage());
                            
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    player.sendMessage(ChatColor.RED + "Jarvis: I couldn't find an answer. Try rephrasing your question.");
                                }
                            }.runTask(plugin);
                        }
                    }
                }.runTaskAsynchronously(plugin);
            }
            
            // Quest commands
            case "quest" -> {
                if (plugin.getQuestSystem() == null) {
                    player.sendMessage(ChatColor.RED + "Quest system not available");
                    return true;
                }
                
                if (args.length < 2) {
                    plugin.getQuestSystem().showQuestStatus(player);
                    return true;
                }
                
                String questSub = args[1].toLowerCase();
                switch (questSub) {
                    case "new", "accept", "get" -> plugin.getQuestSystem().generateAndAssignQuest(player);
                    case "status", "list", "show" -> plugin.getQuestSystem().showQuestStatus(player);
                    case "clear", "abandon" -> {
                        if (player.hasPermission("jarvis.admin")) {
                            plugin.getQuestSystem().clearQuests(player);
                        } else {
                            player.sendMessage(ChatColor.RED + "You don't have permission.");
                        }
                    }
                    default -> player.sendMessage(ChatColor.RED + "Unknown quest command. Use: new, status, clear");
                }
            }
            
            case "help" -> showHelp(player);
            
            default -> player.sendMessage(ChatColor.RED + "Unknown command. Type /jarvis help for help.");
        }
        return true;
    }

    private void showHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "═══════════════════════════════");
        player.sendMessage(ChatColor.GOLD + "  Jarvis AI Companion v0.0.4");
        player.sendMessage(ChatColor.GOLD + "═══════════════════════════════");
        
        player.sendMessage(ChatColor.YELLOW + "NPC Commands:");
        player.sendMessage(ChatColor.WHITE + "  /jarvis summon" + ChatColor.GRAY + " - Bring Jarvis to you");
        player.sendMessage(ChatColor.WHITE + "  /jarvis dismiss" + ChatColor.GRAY + " - Send Jarvis away");
        player.sendMessage(ChatColor.WHITE + "  /jarvis return" + ChatColor.GRAY + " - Warp Jarvis back");
        player.sendMessage(ChatColor.WHITE + "  /jarvis stop" + ChatColor.GRAY + " - Stop current task");
        player.sendMessage(ChatColor.WHITE + "  /jarvis attack" + ChatColor.GRAY + " - Fight mobs");
        player.sendMessage(ChatColor.WHITE + "  /jarvis mine [ore]" + ChatColor.GRAY + " - Mine ores (e.g. diamond)");
        player.sendMessage(ChatColor.WHITE + "  /jarvis battle <player>" + ChatColor.GRAY + " - Battle another Jarvis");
        player.sendMessage(ChatColor.WHITE + "  /jarvis loot" + ChatColor.GRAY + " - Open inventory");
        player.sendMessage(ChatColor.WHITE + "  /jarvis bell" + ChatColor.GRAY + " - Get controller bell");
        
        if (plugin.getBuildingAssistant() != null) {
            player.sendMessage(ChatColor.YELLOW + "Building Commands:");
            player.sendMessage(ChatColor.WHITE + "  /jarvis build <desc>" + ChatColor.GRAY + " - AI builds from schematics");
            player.sendMessage(ChatColor.WHITE + "  /jarvis schematics" + ChatColor.GRAY + " - List available schematics");
            player.sendMessage(ChatColor.WHITE + "  /jarvis schematics scan" + ChatColor.GRAY + " - Reload schematic folder");
            player.sendMessage(ChatColor.WHITE + "  /jarvis schematics download <url> <name>" + ChatColor.GRAY + " - Download schematic");
        }
        
        if (plugin.getQuestSystem() != null) {
            player.sendMessage(ChatColor.YELLOW + "Quest Commands:");
            player.sendMessage(ChatColor.WHITE + "  /jarvis quest" + ChatColor.GRAY + " - Show active quests");
            player.sendMessage(ChatColor.WHITE + "  /jarvis quest new" + ChatColor.GRAY + " - Get a new quest");
            player.sendMessage(ChatColor.WHITE + "  /jarvis quest status" + ChatColor.GRAY + " - Quest progress");
        }
        
        player.sendMessage(ChatColor.YELLOW + "Natural Language:");
        player.sendMessage(ChatColor.GRAY + "  Just say 'jarvis <command>' in chat!");
        player.sendMessage(ChatColor.GRAY + "  Example: 'jarvis come here and mine'");
        
        player.sendMessage(ChatColor.YELLOW + "AI Assistant:");
        player.sendMessage(ChatColor.WHITE + "  /jarvis ask <question>" + ChatColor.GRAY + " - Ask Jarvis anything");
        player.sendMessage(ChatColor.GRAY + "  Example: /jarvis ask what are the best enchantments?");
        
        if (player.hasPermission("jarvis.admin")) {
            player.sendMessage(ChatColor.YELLOW + "Admin Commands:");
            player.sendMessage(ChatColor.WHITE + "  /jarvis reload" + ChatColor.GRAY + " - Reload config");
            player.sendMessage(ChatColor.WHITE + "  /jarvis debug" + ChatColor.GRAY + " - Debug info");
        }
        
        player.sendMessage(ChatColor.GOLD + "═══════════════════════════════");
    }
}
