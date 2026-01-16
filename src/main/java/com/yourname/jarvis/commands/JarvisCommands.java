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
            
            // Building commands - now uses WorldEdit schematics
            case "build", "paste" -> {
                if (plugin.getSchematicManager() == null) {
                    player.sendMessage(ChatColor.RED + "Schematic manager not available");
                    return true;
                }

                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /jarvis build <schematic_name>");
                    player.sendMessage(ChatColor.GRAY + "Example: /jarvis build castle");
                    player.sendMessage(ChatColor.GRAY + "Use /jarvis schematic list to see available schematics");
                    return true;
                }

                String schematicName = args[1];

                // Check for rotation argument
                if (args.length >= 4 && args[2].equalsIgnoreCase("rotate")) {
                    try {
                        int degrees = Integer.parseInt(args[3]);
                        plugin.getSchematicManager().rotateAndPaste(player, schematicName, degrees);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "Invalid rotation. Use: 90, 180, or 270");
                    }
                } else {
                    plugin.getSchematicManager().pasteSchematic(player, schematicName);
                }
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
                    player.sendMessage(ChatColor.RED + "Schematic manager not available");
                    return true;
                }

                if (args.length < 2) {
                    plugin.getSchematicManager().listSchematics(player);
                    return true;
                }

                String schematicSub = args[1].toLowerCase();
                switch (schematicSub) {
                    case "list" -> plugin.getSchematicManager().listSchematics(player);

                    case "paste", "load" -> {
                        if (args.length < 3) {
                            player.sendMessage(ChatColor.RED + "Usage: /jarvis schematic paste <name>");
                            return true;
                        }
                        plugin.getSchematicManager().pasteSchematic(player, args[2]);
                    }

                    case "save" -> {
                        if (args.length < 3) {
                            player.sendMessage(ChatColor.RED + "Usage: /jarvis schematic save <name>");
                            player.sendMessage(ChatColor.GRAY + "First use //copy to copy your selection");
                            return true;
                        }
                        plugin.getSchematicManager().saveSchematic(player, args[2]);
                    }

                    case "rotate" -> {
                        if (args.length < 4) {
                            player.sendMessage(ChatColor.RED + "Usage: /jarvis schematic rotate <name> <degrees>");
                            player.sendMessage(ChatColor.GRAY + "Example: /jarvis schematic rotate castle 90");
                            return true;
                        }
                        try {
                            int degrees = Integer.parseInt(args[3]);
                            plugin.getSchematicManager().rotateAndPaste(player, args[2], degrees);
                        } catch (NumberFormatException e) {
                            player.sendMessage(ChatColor.RED + "Invalid rotation. Use: 90, 180, or 270");
                        }
                    }

                    case "scan", "reload" -> {
                        player.sendMessage(ChatColor.GOLD + "Jarvis: Scanning for schematics...");
                        plugin.getSchematicManager().scanFolder();
                        player.sendMessage(ChatColor.GREEN + "Jarvis: Found " +
                                plugin.getSchematicManager().getSchematics().size() + " schematics.");
                    }

                    case "folder" -> {
                        player.sendMessage(ChatColor.GOLD + "Schematics folder: " +
                                ChatColor.YELLOW + plugin.getSchematicManager().getSchematicFolder().toString());
                        player.sendMessage(ChatColor.GRAY + "Place .schem, .schematic, or .litematic files here");
                        player.sendMessage(ChatColor.GRAY + "Then use /jarvis schematic scan");
                    }

                    case "litematic", "litematics" -> {
                        plugin.getSchematicManager().showLitematicFiles(player);
                    }

                    case "convert" -> {
                        if (args.length < 3) {
                            player.sendMessage(ChatColor.RED + "Usage: /jarvis schematic convert <name>");
                            player.sendMessage(ChatColor.GRAY + "Converts .litematic to .schem format");
                            return true;
                        }
                        plugin.getSchematicManager().convertLitematic(player, args[2]);
                    }

                    case "convertall" -> {
                        player.sendMessage(ChatColor.GOLD + "Converting all .litematic files...");
                        plugin.getSchematicManager().convertAllLitematics(player);
                    }

                    default -> {
                        player.sendMessage(ChatColor.RED + "Unknown schematic command.");
                        player.sendMessage(ChatColor.GRAY + "Use: list, paste, save, rotate, scan, convert, convertall");
                    }
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
        player.sendMessage(ChatColor.GOLD + "  Jarvis AI Companion v0.0.8");
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
        
        if (plugin.getSchematicManager() != null) {
            player.sendMessage(ChatColor.YELLOW + "Schematic Commands:");
            player.sendMessage(ChatColor.WHITE + "  /jarvis schematic list" + ChatColor.GRAY + " - List available schematics");
            player.sendMessage(ChatColor.WHITE + "  /jarvis schematic paste <name>" + ChatColor.GRAY + " - Paste schematic");
            player.sendMessage(ChatColor.WHITE + "  /jarvis schematic save <name>" + ChatColor.GRAY + " - Save clipboard as schematic");
            player.sendMessage(ChatColor.WHITE + "  /jarvis schematic rotate <name> <deg>" + ChatColor.GRAY + " - Paste rotated");
            player.sendMessage(ChatColor.WHITE + "  /jarvis schematic scan" + ChatColor.GRAY + " - Rescan schematic folder");
            player.sendMessage(ChatColor.WHITE + "  /jarvis build <name>" + ChatColor.GRAY + " - Quick paste (alias)");
            player.sendMessage(ChatColor.YELLOW + "Litematic Conversion:");
            player.sendMessage(ChatColor.WHITE + "  /jarvis schematic litematic" + ChatColor.GRAY + " - List .litematic files");
            player.sendMessage(ChatColor.WHITE + "  /jarvis schematic convert <name>" + ChatColor.GRAY + " - Convert to .schem");
            player.sendMessage(ChatColor.WHITE + "  /jarvis schematic convertall" + ChatColor.GRAY + " - Convert all litematics");
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
