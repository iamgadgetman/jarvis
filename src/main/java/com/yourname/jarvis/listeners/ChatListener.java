package com.yourname.jarvis.listeners;

import com.yourname.jarvis.Jarvis;
import com.yourname.jarvis.ai.AIConnector;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChatListener implements Listener {

    private final Jarvis plugin;
    private final AIConnector aiConnector;
    private final boolean enabled;
    private final String prefix;
    private final boolean requirePrefix;

    // Cooldown to prevent spam (in milliseconds)
    private static final long COOLDOWN_MS = 2000;
    private final Map<UUID, Long> lastCommandTime = new ConcurrentHashMap<>();

    public ChatListener(Jarvis plugin) {
        this.plugin = plugin;
        this.aiConnector = plugin.getAIConnector();
        this.enabled = plugin.getConfig().getBoolean("natural-language.enabled", true);
        this.prefix = plugin.getConfig().getString("natural-language.prefix", "jarvis").toLowerCase();
        this.requirePrefix = plugin.getConfig().getBoolean("natural-language.require-prefix", false);

        // Phase 6: Start cleanup task for memory leak prevention
        startCleanupTask();
    }

    /**
     * Phase 6: Periodic cleanup task to remove stale cooldown entries
     * Runs every minute to clean up entries older than 1 minute
     */
    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long maxAge = 60000; // 1 minute in milliseconds

                lastCommandTime.entrySet().removeIf(entry ->
                    (now - entry.getValue()) > maxAge);
            }
        }.runTaskTimer(plugin, 1200L, 1200L); // Run every minute (1200 ticks)
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled() || !enabled) return;

        Player player = event.getPlayer();
        String message = event.getMessage().toLowerCase().trim();

        // Check if message should be processed
        boolean shouldProcess = false;
        String processedMessage = message;

        if (requirePrefix) {
            // Only process if starts with prefix
            if (message.startsWith(prefix + " ") || message.startsWith(prefix + ",")) {
                shouldProcess = true;
                processedMessage = message.substring(prefix.length()).trim();
                event.setCancelled(true); // Don't broadcast to other players
            }
        } else {
            // Process if contains prefix or certain keywords
            if (message.contains(prefix) || 
                message.contains("summon") || 
                message.contains("dismiss") ||
                message.contains("mine") || 
                message.contains("attack") ||
                message.contains("build") ||
                message.contains("come here") ||
                message.contains("follow")) {
                shouldProcess = true;
            }
        }

        if (!shouldProcess) return;

        // Check cooldown
        long now = System.currentTimeMillis();
        Long lastTime = lastCommandTime.get(player.getUniqueId());
        if (lastTime != null && (now - lastTime) < COOLDOWN_MS) {
            return; // Still on cooldown
        }
        lastCommandTime.put(player.getUniqueId(), now);

        // Process async to avoid blocking chat
        String finalMessage = processedMessage;
        new BukkitRunnable() {
            @Override
            public void run() {
                processNaturalLanguageCommand(player, finalMessage);
            }
        }.runTaskAsynchronously(plugin);
    }

    private void processNaturalLanguageCommand(Player player, String message) {
        try {
            // Get context about player's situation
            String context = buildContext(player);

            // Query AI to parse the command
            String response = aiConnector.parseNaturalLanguage(message, player.getName(), context);
            
            // Parse JSON response with safety checks
            JSONObject action = new JSONObject(response);
            String actionType = action.optString("action", "unknown");
            JSONObject parameters = action.optJSONObject("parameters");

            if (actionType.isEmpty() || actionType.equals("unknown")) {
                throw new RuntimeException("No action in AI response");
            }

            // Execute action on main thread
            new BukkitRunnable() {
                @Override
                public void run() {
                    executeAction(player, actionType, parameters, message);
                }
            }.runTask(plugin);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to process natural language: " + e.getMessage());
            
            // Fallback: try to execute simple commands
            new BukkitRunnable() {
                @Override
                public void run() {
                    executeFallbackAction(player, message);
                }
            }.runTask(plugin);
        }
    }

    private String buildContext(Player player) {
        StringBuilder context = new StringBuilder();
        context.append("Location: ").append(player.getWorld().getName());
        context.append(", Biome: ").append(player.getLocation().getBlock().getBiome().toString());
        context.append(", Health: ").append((int)player.getHealth()).append("/20");
        
        // Check if Jarvis is summoned
        boolean jarvisSummoned = plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId()) != null;
        context.append(", Jarvis summoned: ").append(jarvisSummoned);
        
        return context.toString();
    }

    private void executeAction(Player player, String action, JSONObject parameters, String originalMessage) {
        switch (action.toLowerCase()) {
            case "summon" -> {
                plugin.getJarvisNPC().summon(player);
                player.sendMessage(ChatColor.GREEN + "Jarvis: On my way!");
            }
            case "dismiss" -> {
                plugin.getJarvisNPC().dismiss(player);
                player.sendMessage(ChatColor.YELLOW + "Jarvis: Farewell!");
            }
            case "return", "come", "follow" -> {
                plugin.getJarvisNPC().returnToPlayer(player);
                player.sendMessage(ChatColor.GREEN + "Jarvis: Right behind you!");
            }
            case "attack", "defend", "fight" -> {
                plugin.getJarvisNPC().attack(player);
                player.sendMessage(ChatColor.RED + "Jarvis: Engaging combat mode!");
            }
            case "mine", "mining" -> {
                plugin.getJarvisNPC().mine(player);
                player.sendMessage(ChatColor.AQUA + "Jarvis: Initiating mining operations!");
            }
            case "build" -> {
                String description = (parameters != null) ? parameters.optString("description", "") : "";
                if (!description.isEmpty()) {
                    player.sendMessage(ChatColor.GOLD + "Jarvis: I'll build a " + description + " for you!");

                    // Trigger building assistant
                    if (plugin.getBuildingAssistant() != null) {
                        plugin.getBuildingAssistant().startBuild(player, description);
                    }
                } else {
                    player.sendMessage(ChatColor.RED + "Jarvis: What would you like me to build?");
                }
            }
            case "quest_accept" -> {
                player.sendMessage(ChatColor.LIGHT_PURPLE + "Jarvis: Let me find a quest for you...");
                if (plugin.getQuestSystem() != null) {
                    plugin.getQuestSystem().generateAndAssignQuest(player);
                }
            }
            case "quest_status" -> {
                player.sendMessage(ChatColor.LIGHT_PURPLE + "Jarvis: Checking your active quests...");
                if (plugin.getQuestSystem() != null) {
                    plugin.getQuestSystem().showQuestStatus(player);
                }
            }
            case "loot", "inventory" -> {
                plugin.getJarvisNPC().openInventory(player);
            }
            case "chat", "talk" -> {
                // Generate AI dialogue response
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            String npcContext = "You are helping " + player.getName();
                            String response = aiConnector.generateDialogue(originalMessage, npcContext);
                            
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    player.sendMessage(ChatColor.AQUA + "Jarvis: " + ChatColor.WHITE + response);
                                }
                            }.runTask(plugin);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to generate dialogue: " + e.getMessage());
                        }
                    }
                }.runTaskAsynchronously(plugin);
            }
            default -> {
                player.sendMessage(ChatColor.GRAY + "Jarvis: I'm not sure what you want me to do.");
            }
        }
    }

    private void executeFallbackAction(Player player, String message) {
        // Simple keyword matching as fallback
        if (message.contains("summon") || message.contains("come")) {
            plugin.getJarvisNPC().summon(player);
        } else if (message.contains("dismiss") || message.contains("go away")) {
            plugin.getJarvisNPC().dismiss(player);
        } else if (message.contains("mine") || message.contains("dig")) {
            plugin.getJarvisNPC().mine(player);
        } else if (message.contains("attack") || message.contains("fight") || message.contains("defend")) {
            plugin.getJarvisNPC().attack(player);
        } else if (message.contains("return") || message.contains("back")) {
            plugin.getJarvisNPC().returnToPlayer(player);
        } else if (message.contains("loot") || message.contains("inventory")) {
            plugin.getJarvisNPC().openInventory(player);
        }
    }
}
