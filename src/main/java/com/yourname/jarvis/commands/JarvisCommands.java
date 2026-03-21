package com.yourname.jarvis.commands;

import com.yourname.jarvis.ConfirmationManager;
import com.yourname.jarvis.JarvisActionExecutor;
import com.yourname.jarvis.Jarvis;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.JSONObject;

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
            case "clearloot" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
                    plugin.getJarvisNPC().clearInventory(player);
                } else {
                    player.sendMessage(ChatColor.YELLOW + "This will drop all collected items at Jarvis's location.");
                    player.sendMessage(ChatColor.RED + "Type " + ChatColor.WHITE + "/jarvis clearloot confirm" +
                        ChatColor.RED + " to proceed.");
                }
            }
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
            
            case "confirm"   -> handleConfirm(player);
            case "cancel"    -> handleCancel(player);
            case "requests"  -> handleRequests(player);
            case "approve"   -> {
                if (!player.hasPermission("jarvis.admin")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission.");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /jarvis approve <id>");
                    return true;
                }
                try { handleApprove(player, Integer.parseInt(args[1])); }
                catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Invalid request ID.");
                }
            }
            case "deny" -> {
                if (!player.hasPermission("jarvis.admin")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission.");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /jarvis deny <id>");
                    return true;
                }
                try { handleDeny(player, Integer.parseInt(args[1])); }
                catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Invalid request ID.");
                }
            }
            case "help"      -> showHelp(player);

            default -> {
                // Unknown subcommand — treat entire input as natural language
                String nlInput = String.join(" ", args);
                player.sendMessage(ChatColor.GOLD + "Jarvis: On it...");
                new BukkitRunnable() {
                    @Override public void run() {
                        try {
                            String context = "Location: " + player.getWorld().getName()
                                    + ", Health: " + (int) player.getHealth() + "/20"
                                    + ", Jarvis summoned: " + (plugin.getJarvisNPC().getNPC(player) != null);
                            String resp = plugin.getAIConnector().parseNaturalLanguage(
                                    nlInput, player.getName(), context);
                            JSONObject action = new JSONObject(resp);
                            String actionType  = action.optString("action", "unknown");
                            JSONObject params   = action.optJSONObject("parameters");
                            String aiResponse  = action.optString("response", "");

                            new BukkitRunnable() {
                                @Override public void run() {
                                    if (!aiResponse.isEmpty()) {
                                        player.sendMessage(ChatColor.AQUA + "Jarvis: " + ChatColor.WHITE + aiResponse);
                                    }
                                    if (!actionType.isEmpty() && !actionType.equals("unknown")) {
                                        executeNLAction(player, actionType, params);
                                    } else if (aiResponse.isEmpty()) {
                                        player.sendMessage(ChatColor.GRAY + "Jarvis: Not sure what you mean — try /jarvis help");
                                    }
                                }
                            }.runTask(plugin);
                        } catch (Exception e) {
                            new BukkitRunnable() {
                                @Override public void run() {
                                    player.sendMessage(ChatColor.RED + "Jarvis: Couldn't process that. Try /jarvis help");
                                }
                            }.runTask(plugin);
                        }
                    }
                }.runTaskAsynchronously(plugin);
            }
        }
        return true;
    }

    private void handleConfirm(Player player) {
        ConfirmationManager cm = plugin.getConfirmationManager();
        if (!cm.hasPending(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "Jarvis: No pending action, or it timed out.");
            return;
        }
        String actionType  = cm.getPendingAction(player.getUniqueId());
        JSONObject params   = cm.getPendingParameters(player.getUniqueId());
        cm.clearPending(player.getUniqueId());

        String result = plugin.getActionExecutor().execute(actionType, params, player);
        if (result != null) {
            player.sendMessage(ChatColor.GREEN + "[Jarvis] " + result);
        }
    }

    private void handleCancel(Player player) {
        ConfirmationManager cm = plugin.getConfirmationManager();
        if (!cm.hasPending(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "Jarvis: Nothing to cancel.");
            return;
        }
        cm.clearPending(player.getUniqueId());
        player.sendMessage(ChatColor.GRAY + "Jarvis: Action cancelled. Wise choice, perhaps.");
    }

    private void handleRequests(Player player) {
        if (!player.hasPermission("jarvis.admin")) {
            player.sendMessage(ChatColor.RED + "You don't have permission.");
            return;
        }
        var rm = plugin.getPlayerRequestManager();
        if (rm == null || !rm.hasPending()) {
            player.sendMessage(ChatColor.GRAY + "Jarvis: No pending item requests.");
            return;
        }
        player.sendMessage(ChatColor.GOLD + "═══ Pending Item Requests ═══");
        for (var req : rm.getAllRequests()) {
            net.kyori.adventure.text.Component line = net.kyori.adventure.text.Component
                    .text("#" + req.id + " ", net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                    .append(net.kyori.adventure.text.Component.text(
                            req.playerName + " wants " + req.amount + "x " + req.item
                            + (req.reason.isEmpty() ? "" : " — " + req.reason),
                            net.kyori.adventure.text.format.NamedTextColor.WHITE))
                    .append(net.kyori.adventure.text.Component.text(" [Approve]",
                            net.kyori.adventure.text.format.NamedTextColor.GREEN)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/jarvis approve " + req.id))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                    net.kyori.adventure.text.Component.text("Approve this request"))))
                    .append(net.kyori.adventure.text.Component.text(" [Deny]",
                            net.kyori.adventure.text.format.NamedTextColor.RED)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/jarvis deny " + req.id))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                    net.kyori.adventure.text.Component.text("Deny this request"))));
            player.sendMessage(line);
        }
        player.sendMessage(ChatColor.GOLD + "════════════════════════════");
    }

    private void handleApprove(Player admin, int id) {
        var rm = plugin.getPlayerRequestManager();
        if (rm == null) { admin.sendMessage(ChatColor.RED + "Request system not available."); return; }
        var req = rm.getRequest(id);
        if (req == null) {
            admin.sendMessage(ChatColor.RED + "Request #" + id + " not found or already handled.");
            return;
        }
        rm.removeRequest(id);

        // Give item to the requesting player
        org.bukkit.entity.Player target = plugin.getServer().getPlayer(req.playerUUID);
        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(req.item);
        if (mat == null) {
            admin.sendMessage(ChatColor.RED + "Unknown item: " + req.item + ". Request removed.");
            return;
        }

        if (target != null && target.isOnline()) {
            target.getInventory().addItem(new org.bukkit.inventory.ItemStack(mat, req.amount));
            target.sendMessage(ChatColor.GREEN + "Jarvis: " + admin.getName()
                    + " approved your request for " + req.amount + "x " + req.item + ".");
            admin.sendMessage(ChatColor.GREEN + "Approved #" + id + ": gave " + req.amount + "x "
                    + req.item + " to " + req.playerName + ".");
        } else {
            admin.sendMessage(ChatColor.YELLOW + req.playerName + " is offline. Item will be given when they rejoin.");
            // Store for next login — for now just notify admin
            admin.sendMessage(ChatColor.GRAY + "(Offline delivery not yet implemented — re-approve when they join)");
        }
    }

    private void handleDeny(Player admin, int id) {
        var rm = plugin.getPlayerRequestManager();
        if (rm == null) { admin.sendMessage(ChatColor.RED + "Request system not available."); return; }
        var req = rm.getRequest(id);
        if (req == null) {
            admin.sendMessage(ChatColor.RED + "Request #" + id + " not found or already handled.");
            return;
        }
        rm.removeRequest(id);

        org.bukkit.entity.Player target = plugin.getServer().getPlayer(req.playerUUID);
        if (target != null && target.isOnline()) {
            target.sendMessage(ChatColor.RED + "Jarvis: " + admin.getName()
                    + " denied your request for " + req.amount + "x " + req.item + ".");
        }
        admin.sendMessage(ChatColor.GRAY + "Denied request #" + id + " from " + req.playerName + ".");
    }

    /** Execute an action resolved from natural language input in /jarvis <...> */
    private void executeNLAction(Player player, String actionType, JSONObject params) {
        JarvisActionExecutor executor = plugin.getActionExecutor();

        // Check if this is an extended world action
        boolean isExtended = switch (actionType) {
            case "give_item", "enchant", "potion_effect", "heal", "feed",
                 "set_gamemode", "teleport", "set_time", "set_weather", "set_gamerule",
                 "broadcast", "server_say", "lp_group_add", "lp_group_remove",
                 "warp", "discord_broadcast", "paste_schematic" -> true;
            default -> false;
        };

        if (executor != null && isExtended) {
            if (JarvisActionExecutor.DANGEROUS_ACTIONS.contains(actionType)) {
                String desc = executor.describe(actionType, params);
                plugin.getConfirmationManager().setPending(
                        player.getUniqueId(), actionType, params, desc);
                player.sendMessage(ChatColor.YELLOW + "Jarvis: I want to — " + desc);
                Component confirm = Component.text("[Confirm]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/jarvis confirm"))
                        .hoverEvent(HoverEvent.showText(Component.text("Execute: " + desc)));
                Component cancel = Component.text(" [Cancel]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/jarvis cancel"))
                        .hoverEvent(HoverEvent.showText(Component.text("Cancel this action")));
                player.sendMessage(confirm.append(cancel));
            } else {
                String result = executor.execute(actionType, params, player);
                if (result != null) player.sendMessage(ChatColor.GREEN + "[Jarvis] " + result);
            }
            return;
        }

        // NPC / core actions
        switch (actionType.toLowerCase()) {
            case "summon"                   -> plugin.getJarvisNPC().summon(player);
            case "dismiss"                  -> plugin.getJarvisNPC().dismiss(player);
            case "return", "come", "follow" -> plugin.getJarvisNPC().returnToPlayer(player);
            case "attack", "defend", "fight"-> plugin.getJarvisNPC().attack(player);
            case "mine", "mining"           -> plugin.getJarvisNPC().mine(player);
            case "stop"                     -> plugin.getJarvisNPC().stop(player);
            case "loot", "inventory"        -> plugin.getJarvisNPC().openInventory(player);
            case "quest_accept"             -> { if (plugin.getQuestSystem() != null) plugin.getQuestSystem().generateAndAssignQuest(player); }
            case "quest_status"             -> { if (plugin.getQuestSystem() != null) plugin.getQuestSystem().showQuestStatus(player); }
            case "chat", "talk"             -> {} // AI response already shown above
        }
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
        player.sendMessage(ChatColor.WHITE + "  /jarvis clearloot" + ChatColor.GRAY + " - Drop all collected items");
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
        
        player.sendMessage(ChatColor.YELLOW + "Natural Language (just type it):");
        player.sendMessage(ChatColor.GRAY + "  Chat: 'jarvis kill all creepers'");
        player.sendMessage(ChatColor.GRAY + "  Command: /jarvis kill all creepers nearby");
        player.sendMessage(ChatColor.WHITE + "  /jarvis ask <question>" + ChatColor.GRAY + " - Ask anything");

        if (plugin.getPlayerRequestManager() != null) {
            player.sendMessage(ChatColor.YELLOW + "Item Requests:");
            player.sendMessage(ChatColor.GRAY + "  Say 'jarvis, can I have <item>?' to request items from admins");
        }
        
        if (player.hasPermission("jarvis.admin")) {
            player.sendMessage(ChatColor.YELLOW + "Admin Commands:");
            player.sendMessage(ChatColor.WHITE + "  /jarvis reload" + ChatColor.GRAY + " - Reload config");
            player.sendMessage(ChatColor.WHITE + "  /jarvis debug" + ChatColor.GRAY + " - Debug info");
            player.sendMessage(ChatColor.WHITE + "  /jarvis requests" + ChatColor.GRAY + " - View pending item requests");
            player.sendMessage(ChatColor.WHITE + "  /jarvis approve <id>" + ChatColor.GRAY + " - Approve item request");
            player.sendMessage(ChatColor.WHITE + "  /jarvis deny <id>" + ChatColor.GRAY + " - Deny item request");
            player.sendMessage(ChatColor.GRAY + "  Console AI: 'jarvis, kill all creepers' — asks before executing");
        }
        
        player.sendMessage(ChatColor.GOLD + "═══════════════════════════════");
    }
}
