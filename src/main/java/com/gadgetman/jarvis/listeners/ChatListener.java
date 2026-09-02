package com.gadgetman.jarvis.listeners;

import com.gadgetman.jarvis.ConfirmationManager;
import com.gadgetman.jarvis.JarvisActionExecutor;
import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.ai.AIConnector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        if (!enabled) return;

        Player player  = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText()
                .serialize(event.message()).toLowerCase().trim();

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
        // v0.8.0: build the world context on the MAIN thread first (Bukkit API
        // isn't thread-safe), then do the slow AI call async.
        new BukkitRunnable() {
            @Override public void run() {
                final String worldContext = buildContext(player);
                new BukkitRunnable() {
                    @Override public void run() {
                        processNaturalLanguageCommand(player, finalMessage, worldContext);
                    }
                }.runTaskAsynchronously(plugin);
            }
        }.runTask(plugin);
    }

    private void processNaturalLanguageCommand(Player player, String message, String worldContext) {
        try {
            String context  = worldContext + buildMemoryContext(player);
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

    /**
     * Butler memory (v0.3.0): fold the player's recent exchanges into the
     * prompt so Jarvis remembers the conversation. Reduced (Ollama-only)
     * mode keeps the context short for small local models.
     */
    private String buildMemoryContext(Player player) {
        if (plugin.getDatabaseManager() == null) return "";
        int turns = aiConnector.isReducedMode() ? 2 : 5;
        var recent = plugin.getDatabaseManager()
                .getRecentInteractions(player.getUniqueId().toString(), turns);
        if (recent.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nRecent conversation (oldest first):\n");
        for (String[] row : recent) {
            sb.append("  Player: ").append(trim(row[0], 100)).append("\n");
            if (row[2] != null && !row[2].isEmpty()) {
                sb.append("  Jarvis did: ").append(row[2]);
            }
            if (row[1] != null && !row[1].isEmpty()) {
                sb.append(" — said: ").append(trim(row[1], 100));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String trim(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
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

        // Butler memory: remember this exchange (async — DB writes off-thread)
        if (plugin.getDatabaseManager() != null) {
            String finalAction = actionType;
            new BukkitRunnable() {
                @Override public void run() {
                    plugin.getDatabaseManager().logChatInteraction(
                            player.getUniqueId().toString(), originalMessage, aiResponse, finalAction);
                }
            }.runTaskAsynchronously(plugin);
        }

        // Reduced (Ollama-only) mode: decline high-risk actions parsed by a small local model
        if (aiConnector.isReducedMode() && RESTRICTED_IN_REDUCED_MODE.contains(actionType)
                && !plugin.getConfig().getBoolean("ai.reduced-mode.allow-risky-actions", false)) {
            player.sendMessage(ChatColor.YELLOW
                    + "Jarvis: I'd rather not run " + actionType + " on local-model judgement alone, sir. "
                    + "Use the slash command directly, or enable ai.reduced-mode.allow-risky-actions.");
            return;
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
            case "return", "come"          -> plugin.getJarvisNPC().returnToPlayer(player);
            case "follow"                  -> plugin.getJarvisNPC().follow(player);
            case "mine_here", "branch_mine"-> plugin.getJarvisNPC().startBranchMining(player);
            case "dig_down" -> plugin.getJarvisNPC().digDown(player,
                    parameters.optInt("depth", 0));
            case "deposit"                 -> plugin.getJarvisNPC().getDepositManager().deposit(player);
            case "set_chest"               -> plugin.getJarvisNPC().getDepositManager().setChest(player);
            case "attack", "fight"         -> plugin.getJarvisNPC().guard(player, "aggressive");
            case "guard", "defend", "protect"-> plugin.getJarvisNPC().guard(player, "defensive");
            case "watch", "sentry"         -> plugin.getJarvisNPC().watch(player, null);
            case "stand_down"              -> plugin.getJarvisNPC().guard(player, "passive");
            case "mine", "mining"          -> plugin.getJarvisNPC().mine(player);
            case "stop"                    -> plugin.getJarvisNPC().stop(player);
            case "loot", "inventory"       -> plugin.getJarvisNPC().openInventory(player);
            case "build" -> {
                String desc = (parameters != null) ? parameters.optString("description", "") : "";
                if (desc.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "Jarvis: What would you like me to build?");
                    return;
                }
                startSchematicFirstBuild(player, desc);
            }
            case "report" -> {
                if (plugin.getMorningReport() != null) plugin.getMorningReport().deliver(player, false);
            }
            case "recover" -> plugin.getJarvisNPC().getRecoveryService().recover(player);
            case "take_home" -> plugin.getJarvisNPC().getEscortService().takeHome(player);
            case "set_home" -> plugin.getJarvisNPC().getEscortService().setHome(player);
            case "farm" -> plugin.getJarvisNPC().farm(player,
                    parameters != null ? parameters.optString("crop", null) : null, false);
            case "tend" -> plugin.getJarvisNPC().farm(player,
                    parameters != null ? parameters.optString("crop", null) : null, true);
            case "chop", "chop_trees" -> plugin.getJarvisNPC().chop(player,
                    parameters != null ? parameters.optInt("count", 5) : 5);
            case "fish" -> plugin.getJarvisNPC().fish(player);
            case "dance" -> plugin.getJarvisNPC().dance(player);
            case "patrol" -> plugin.getJarvisNPC().patrol(player, "start");
            case "light", "light_area" -> plugin.getJarvisNPC().light(player,
                    parameters != null ? parameters.optInt("radius", -1) : -1,
                    parameters != null ? parameters.optString("type", null) : null,
                    parameters != null ? parameters.optInt("spacing", -1) : -1);
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

    /**
     * v0.5.0: schematic-first building. The AI picks the best match from the
     * schematic library (a constrained choice small models handle well);
     * freeform AI block-planning is only the fallback — and is unavailable
     * in reduced mode.
     */
    private void startSchematicFirstBuild(Player player, String desc) {
        new BukkitRunnable() {
            @Override public void run() {
                String pick = null;
                if (plugin.getSchematicManager() != null) {
                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (var info : plugin.getSchematicManager().getSchematics()) {
                        names.add(info.name);
                    }
                    if (!names.isEmpty()) {
                        try {
                            pick = aiConnector.pickSchematic(desc, names);
                        } catch (Exception e) {
                            plugin.getLogger().fine("Schematic pick failed: " + e.getMessage());
                        }
                    }
                }
                final String chosen = pick;
                new BukkitRunnable() {
                    @Override public void run() {
                        if (chosen != null) {
                            player.sendMessage(ChatColor.AQUA + "Jarvis: " + ChatColor.WHITE
                                    + "The '" + chosen + "' schematic should serve nicely, sir.");
                            plugin.getSchematicManager().pasteSchematic(player, chosen);
                        } else if (aiConnector.isReducedMode()) {
                            player.sendMessage(ChatColor.YELLOW + "Jarvis: Nothing in the schematic "
                                    + "library fits, sir, and freeform design is beyond the house systems "
                                    + "in local-only mode. Do add a schematic to the library.");
                        } else if (plugin.getBuildingAssistant() != null) {
                            player.sendMessage(ChatColor.GRAY + "Jarvis: Nothing suitable in the "
                                    + "library — improvising a design, sir.");
                            plugin.getBuildingAssistant().startBuild(player, desc);
                        }
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
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

    /** Actions too risky to trust to a small local model's parsing. */
    private static final java.util.Set<String> RESTRICTED_IN_REDUCED_MODE = java.util.Set.of(
            "console_command", "console_commands", "lp_group_add", "lp_group_remove", "set_gamerule");

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
        else if (message.contains("attack") || message.contains("fight")) plugin.getJarvisNPC().guard(player, "aggressive");
        else if (message.contains("guard") || message.contains("protect")) plugin.getJarvisNPC().guard(player, "defensive");
        else if (message.contains("watch")) plugin.getJarvisNPC().watch(player, null);
        else if (message.contains("follow")) plugin.getJarvisNPC().follow(player);
        else if (message.contains("return") || message.contains("back")) plugin.getJarvisNPC().returnToPlayer(player);
        else if (message.contains("deposit")) plugin.getJarvisNPC().getDepositManager().deposit(player);
        else if (message.contains("farm")) plugin.getJarvisNPC().farm(player, message, false);
        else if (message.contains("chop") || message.contains("trees")) plugin.getJarvisNPC().chop(player, 5);
        else if (message.contains("fish")) plugin.getJarvisNPC().fish(player);
        else if (message.contains("dance")) plugin.getJarvisNPC().dance(player);
        else if (message.contains("light")) plugin.getJarvisNPC().light(player, -1, null, -1);
        else if (message.contains("loot") || message.contains("inventory")) plugin.getJarvisNPC().openInventory(player);
    }
}
