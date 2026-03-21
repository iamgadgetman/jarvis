package com.yourname.jarvis.listeners;

import com.yourname.jarvis.ConfirmationManager;
import com.yourname.jarvis.JarvisActionExecutor;
import com.yourname.jarvis.Jarvis;
import com.yourname.jarvis.ai.AIConnector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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

    private static final long COOLDOWN_MS = 2000;
    private final Map<UUID, Long> lastCommandTime = new ConcurrentHashMap<>();

    public ChatListener(Jarvis plugin) {
        this.plugin        = plugin;
        this.aiConnector   = plugin.getAIConnector();
        this.enabled       = plugin.getConfig().getBoolean("natural-language.enabled", true);
        this.prefix        = plugin.getConfig().getString("natural-language.prefix", "jarvis").toLowerCase();
        this.requirePrefix = plugin.getConfig().getBoolean("natural-language.require-prefix", false);
        startCleanupTask();
    }

    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override public void run() {
                long cutoff = System.currentTimeMillis() - 60000;
                lastCommandTime.entrySet().removeIf(e -> e.getValue() < cutoff);
            }
        }.runTaskTimer(plugin, 1200L, 1200L);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled() || !enabled) return;

        Player player  = event.getPlayer();
        String message = event.getMessage().toLowerCase().trim();

        boolean shouldProcess;
        String processedMessage = message;

        if (requirePrefix) {
            shouldProcess = message.startsWith(prefix + " ") || message.startsWith(prefix + ",");
            if (shouldProcess) {
                processedMessage = message.substring(prefix.length()).trim();
                event.setCancelled(true);
            }
        } else {
            shouldProcess = message.contains(prefix)
                    || message.contains("summon") || message.contains("dismiss")
                    || message.contains("mine")   || message.contains("attack")
                    || message.contains("build")  || message.contains("come here")
                    || message.contains("follow") || message.contains("give me")
                    || message.contains("heal")   || message.contains("feed me")
                    || message.contains("time")   || message.contains("weather");
        }

        if (!shouldProcess) return;

        long now = System.currentTimeMillis();
        Long last = lastCommandTime.get(player.getUniqueId());
        if (last != null && (now - last) < COOLDOWN_MS) return;
        lastCommandTime.put(player.getUniqueId(), now);

        String finalMessage = processedMessage;
        new BukkitRunnable() {
            @Override public void run() {
                processNaturalLanguageCommand(player, finalMessage);
            }
        }.runTaskAsynchronously(plugin);
    }

    private void processNaturalLanguageCommand(Player player, String message) {
        try {
            String context  = buildContext(player);
            String response = aiConnector.parseNaturalLanguage(message, player.getName(), context);

            JSONObject action     = new JSONObject(response);
            String actionType     = action.optString("action", "unknown");
            JSONObject parameters = action.optJSONObject("parameters");
            String aiResponse     = action.optString("response", "");

            if (actionType.isEmpty() || actionType.equals("unknown")) {
                throw new RuntimeException("No action in AI response");
            }

            new BukkitRunnable() {
                @Override public void run() {
                    executeAction(player, actionType, parameters, aiResponse, message);
                }
            }.runTask(plugin);

        } catch (Exception e) {
            plugin.getLogger().warning("Natural language parse failed: " + e.getMessage());
            new BukkitRunnable() {
                @Override public void run() {
                    executeFallbackAction(player, message);
                }
            }.runTask(plugin);
        }
    }

    private String buildContext(Player player) {
        return "Location: " + player.getWorld().getName()
                + ", Biome: " + player.getLocation().getBlock().getBiome()
                + ", Health: " + (int) player.getHealth() + "/20"
                + ", Gamemode: " + player.getGameMode().name().toLowerCase()
                + ", Jarvis summoned: " + (plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId()) != null);
    }

    private void executeAction(Player player, String actionType, JSONObject parameters,
                               String aiResponse, String originalMessage) {

        // Always show Jarvis's witty response first
        if (!aiResponse.isEmpty()) {
            player.sendMessage(ChatColor.AQUA + "Jarvis: " + ChatColor.WHITE + aiResponse);
        }

        JarvisActionExecutor executor = plugin.getActionExecutor();

        // Check if this is a new action type handled by JarvisActionExecutor
        if (executor != null && isExtendedAction(actionType)) {
            if (JarvisActionExecutor.DANGEROUS_ACTIONS.contains(actionType)) {
                queueForConfirmation(player, actionType, parameters, executor);
            } else {
                String result = executor.execute(actionType, parameters, player);
                if (result != null) {
                    player.sendMessage(ChatColor.GREEN + "[Jarvis] " + result);
                }
            }
            return;
        }

        // Core NPC / existing actions
        switch (actionType.toLowerCase()) {
            case "summon"                  -> plugin.getJarvisNPC().summon(player);
            case "dismiss"                 -> plugin.getJarvisNPC().dismiss(player);
            case "return", "come", "follow"-> plugin.getJarvisNPC().returnToPlayer(player);
            case "attack", "defend", "fight"-> plugin.getJarvisNPC().attack(player);
            case "mine", "mining"          -> plugin.getJarvisNPC().mine(player);
            case "stop"                    -> plugin.getJarvisNPC().stop(player);
            case "loot", "inventory"       -> plugin.getJarvisNPC().openInventory(player);
            case "build" -> {
                String desc = (parameters != null) ? parameters.optString("description", "") : "";
                if (!desc.isEmpty() && plugin.getBuildingAssistant() != null) {
                    plugin.getBuildingAssistant().startBuild(player, desc);
                } else {
                    player.sendMessage(ChatColor.RED + "Jarvis: What would you like me to build?");
                }
            }
            case "quest_accept" -> {
                if (plugin.getQuestSystem() != null) plugin.getQuestSystem().generateAndAssignQuest(player);
            }
            case "quest_status" -> {
                if (plugin.getQuestSystem() != null) plugin.getQuestSystem().showQuestStatus(player);
            }
            case "clearloot" -> plugin.getJarvisNPC().clearInventory(player);
            case "chat", "talk" -> {
                // AI response already shown above — nothing else needed
            }
            default -> {
                if (aiResponse.isEmpty()) {
                    player.sendMessage(ChatColor.GRAY + "Jarvis: I'm not sure what you want me to do.");
                }
            }
        }
    }

    /** Show a clickable confirmation prompt for dangerous actions. */
    private void queueForConfirmation(Player player, String actionType,
                                      JSONObject parameters, JarvisActionExecutor executor) {
        String description = executor.describe(actionType, parameters);
        plugin.getConfirmationManager().setPending(
                player.getUniqueId(), actionType, parameters, description);

        player.sendMessage(ChatColor.YELLOW + "Jarvis: I want to — " + description);

        Component confirm = Component.text("[Confirm]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/jarvis confirm"))
                .hoverEvent(HoverEvent.showText(Component.text("Execute: " + description)));
        Component cancel = Component.text(" [Cancel]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/jarvis cancel"))
                .hoverEvent(HoverEvent.showText(Component.text("Cancel this action")));
        player.sendMessage(confirm.append(cancel));
        player.sendMessage(Component.text("Expires in "
                + plugin.getConfirmationManager().getTimeoutSeconds() + "s.",
                NamedTextColor.GRAY));
    }

    private boolean isExtendedAction(String actionType) {
        return switch (actionType) {
            case "give_item", "enchant", "potion_effect", "heal", "feed",
                 "set_gamemode", "teleport", "set_time", "set_weather", "set_gamerule",
                 "summon", "broadcast", "server_say", "lp_group_add", "lp_group_remove",
                 "warp", "discord_broadcast", "paste_schematic",
                 // v0.0.9 new actions
                 "console_command", "console_commands", "clear_mobs", "clear_drops",
                 "save_world", "set_difficulty", "announce_all", "schedule_broadcast",
                 "request_item" -> true;
            default -> false;
        };
    }

    private void executeFallbackAction(Player player, String message) {
        if (message.contains("summon") || message.contains("come"))     plugin.getJarvisNPC().summon(player);
        else if (message.contains("dismiss") || message.contains("away")) plugin.getJarvisNPC().dismiss(player);
        else if (message.contains("mine") || message.contains("dig"))   plugin.getJarvisNPC().mine(player);
        else if (message.contains("attack") || message.contains("fight")) plugin.getJarvisNPC().attack(player);
        else if (message.contains("return") || message.contains("back")) plugin.getJarvisNPC().returnToPlayer(player);
        else if (message.contains("loot") || message.contains("inventory")) plugin.getJarvisNPC().openInventory(player);
    }
}
