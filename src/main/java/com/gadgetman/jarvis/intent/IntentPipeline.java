package com.gadgetman.jarvis.intent;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.JarvisActionExecutor;
import com.gadgetman.jarvis.ai.AIConnector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.JSONObject;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;

/**
 * IntentPipeline — the one road from "a player said something" to "Jarvis did
 * something".
 *
 * <p>This logic used to live inside {@link com.gadgetman.jarvis.listeners.ChatListener},
 * which meant chat was the only way in. Voice needs exactly the same
 * behaviour — build world context on the main thread, ask the AI off it,
 * execute back on it — so it was lifted out here rather than copied.
 *
 * <p>What differs between chat and voice is not the parse, it is where the
 * answer comes out: chat writes to the chat box, voice will speak. That is the
 * {@link Responder}, and it is the only seam between the two.
 *
 * <p>Threading is unchanged from the chat implementation and matters:
 * Bukkit's API is not thread-safe, so the world context is gathered on the
 * main thread, the slow AI call runs async, and execution returns to the main
 * thread.
 */
public class IntentPipeline {

    /** How the utterance reached Jarvis. */
    public enum Source { CHAT, VOICE, CONSOLE }

    /**
     * Where Jarvis's words go. {@link #speak} is Jarvis talking; {@link #feedback}
     * is the mechanical result of an action. Voice will want to speak the first
     * and may want to only print the second.
     */
    public interface Responder {
        void speak(Player player, String jarvisLine);
        void feedback(Player player, String line);
    }

    /** The original behaviour: everything goes to the chat box. */
    public static final Responder CHAT_RESPONDER = new Responder() {
        @Override public void speak(Player player, String jarvisLine) {
            player.sendMessage(ChatColor.AQUA + "Jarvis: " + ChatColor.WHITE + jarvisLine);
        }
        @Override public void feedback(Player player, String line) {
            player.sendMessage(line);
        }
    };

    /** Actions too risky to trust to a small local model's parsing. */
    private static final Set<String> RESTRICTED_IN_REDUCED_MODE = Set.of(
            "console_command", "console_commands", "lp_group_add", "lp_group_remove", "set_gamerule");

    private final Jarvis plugin;
    private final AIConnector aiConnector;

    public IntentPipeline(Jarvis plugin) {
        this.plugin      = plugin;
        this.aiConnector = plugin.getAIConnector();
    }

    // ==================== ENTRY POINT ====================

    /**
     * Hand Jarvis an utterance. Safe to call from any thread; the pipeline
     * hops to wherever it needs to be.
     *
     * @param player    who said it
     * @param utterance what they said, with any wake prefix already stripped
     * @param source    chat, voice or console — decides nothing here except
     *                  what gets recorded, but the responder usually follows it
     * @param responder where Jarvis's reply should come out
     */
    public void submit(Player player, String utterance, Source source, Responder responder) {
        final Responder sink = responder == null ? CHAT_RESPONDER : responder;

        // Build the world context on the MAIN thread first (Bukkit API isn't
        // thread-safe), then do the slow AI call async.
        new BukkitRunnable() {
            @Override public void run() {
                final String worldContext = buildContext(player);
                new BukkitRunnable() {
                    @Override public void run() {
                        parseAndDispatch(player, utterance, worldContext, source, sink);
                    }
                }.runTaskAsynchronously(plugin);
            }
        }.runTask(plugin);
    }

    /** Convenience for the chat path, which always answers in chat. */
    public void submit(Player player, String utterance, Source source) {
        submit(player, utterance, source, CHAT_RESPONDER);
    }

    // ==================== PARSE ====================

    private void parseAndDispatch(Player player, String message, String worldContext,
                                  Source source, Responder sink) {
        try {
            String context  = worldContext + buildMemoryContext(player);
            String response = aiConnector.parseNaturalLanguage(message, player.getName(), context);

            JSONObject action     = new JSONObject(extractJson(response));
            String actionType     = action.optString("action", "unknown");
            JSONObject parameters = action.optJSONObject("parameters");
            String aiResponse     = action.optString("response", "");

            if (actionType.isEmpty() || actionType.equals("unknown")) {
                throw new RuntimeException("No action in AI response");
            }

            new BukkitRunnable() {
                @Override public void run() {
                    executeAction(player, actionType, parameters, aiResponse, message, source, sink);
                }
            }.runTask(plugin);

        } catch (Exception e) {
            plugin.getLogger().warning("Natural language parse failed: " + e.getMessage());
            new BukkitRunnable() {
                @Override public void run() {
                    executeFallbackAction(player, message, sink);
                }
            }.runTask(plugin);
        }
    }

    /**
     * Pull the JSON object out of a model reply.
     *
     * <p>The prompt asks for bare JSON, and models mostly comply — but not
     * always. Some wrap it in a ```json fence, some add a line of prose first.
     * Parsing the raw reply then fails with "A JSONObject text must begin with
     * '{'", the pipeline falls back to keyword matching, and because that
     * fallback has no branch for the richer actions the order silently does
     * nothing. Taking the outermost braced span costs nothing and makes the
     * parse robust to whichever model is answering.
     */
    static String extractJson(String reply) {
        if (reply == null) return "";
        String text = reply.trim();

        // Strip a markdown fence if that is all that is wrong.
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            if (firstBreak > 0) text = text.substring(firstBreak + 1);
            int close = text.lastIndexOf("```");
            if (close >= 0) text = text.substring(0, close);
            text = text.trim();
        }
        if (text.startsWith("{")) return text;

        // Otherwise take the outermost {...}, counting braces so that nested
        // parameter objects don't end the span early. Braces inside strings
        // are skipped, or a description containing "{" would truncate it.
        int start = text.indexOf('{');
        if (start < 0) return text;
        int depth = 0;
        boolean inString = false, escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped)            { escaped = false; continue; }
            if (c == '\\')          { escaped = true;  continue; }
            if (c == '"')           { inString = !inString; continue; }
            if (inString)           continue;
            if (c == '{')           depth++;
            else if (c == '}' && --depth == 0) return text.substring(start, i + 1);
        }
        return text.substring(start);
    }

    /**
     * Butler memory: fold the player's recent exchanges into the prompt so
     * Jarvis remembers the conversation. Reduced (Ollama-only) mode keeps the
     * context short for small local models.
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

    /** Must run on the main thread. */
    public String buildContext(Player player) {
        return "Location: " + player.getWorld().getName()
                + ", Biome: " + player.getLocation().getBlock().getBiome()
                + ", Health: " + (int) player.getHealth() + "/20"
                + ", Gamemode: " + player.getGameMode().name().toLowerCase()
                + ", Jarvis summoned: " + (plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId()) != null);
    }

    // ==================== EXECUTE ====================

    private void executeAction(Player player, String actionType, JSONObject parameters,
                               String aiResponse, String originalMessage,
                               Source source, Responder sink) {

        // Always show Jarvis's witty response first
        if (!aiResponse.isEmpty()) {
            sink.speak(player, aiResponse);
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
            sink.feedback(player, ChatColor.YELLOW
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
                    sink.feedback(player, ChatColor.GREEN + "[Jarvis] " + result);
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
            case "tunnel" -> plugin.getJarvisNPC().tunnel(player,
                    parameters == null ? 0 : parameters.optInt("length", 0),
                    parameters == null ? null : parameters.optString("direction", null));
            case "dig_down" -> plugin.getJarvisNPC().digDown(player,
                    parameters == null ? 0 : parameters.optInt("depth", 0));
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
                    sink.feedback(player, ChatColor.RED + "Jarvis: What would you like me to build?");
                    return;
                }
                startSchematicFirstBuild(player, desc, sink);
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
                // AI response already delivered above — nothing else needed
            }
            default -> {
                if (aiResponse.isEmpty()) {
                    sink.feedback(player, ChatColor.GRAY + "Jarvis: I'm not sure what you want me to do.");
                }
            }
        }
    }

    /**
     * Schematic-first building. The AI picks the best match from the schematic
     * library (a constrained choice small models handle well); freeform AI
     * block-planning is only the fallback — and is unavailable in reduced mode.
     *
     * <p>The request is decomposed into feature tags first, and the library is
     * scored against those. A confident tag match is taken without asking a
     * model to pick at all — which is the point, because that decomposition is
     * cached and a repeat request then costs nothing. Only a weak match falls
     * through to the AI pick, and it goes with the tags in hand rather than the
     * bare sentence.
     */
    private void startSchematicFirstBuild(Player player, String desc, Responder sink) {
        new BukkitRunnable() {
            @Override public void run() {
                String pick = null;
                var schematics = plugin.getSchematicManager();
                com.gadgetman.jarvis.schematics.RequestFeatures features =
                        com.gadgetman.jarvis.schematics.RequestFeatures.none();

                if (schematics != null) {
                    List<String> names = new ArrayList<>();
                    for (var info : schematics.getSchematics()) {
                        names.add(info.name);
                    }
                    if (!names.isEmpty()) {
                        var decomposer = plugin.getRequestDecomposer();
                        if (decomposer != null) features = decomposer.decompose(desc);

                        int threshold = plugin.getConfig()
                                .getInt("schematics.feature-tags.match-threshold", 90);
                        int score = schematics.bestMatchScore(desc, features);
                        if (score >= threshold) {
                            pick = schematics.bestMatchName(desc, features);
                            plugin.getLogger().fine("Feature match for \"" + desc + "\": "
                                    + pick + " (" + score + ", asked for: " + features + ")");
                        }

                        if (pick == null) {
                            // Not confident enough to answer from the library
                            // alone. The tags still go with the request — the
                            // model is choosing from names, and knowing the
                            // request means "storage" helps it do that.
                            String enriched = features.isEmpty() ? desc
                                    : desc + " (asking for: " + features.purpose()
                                      + " — a " + features.kind() + ")";
                            try {
                                pick = aiConnector.pickSchematic(enriched, names);
                            } catch (Exception e) {
                                plugin.getLogger().fine("Schematic pick failed: " + e.getMessage());
                            }
                        }
                    }
                }
                final String chosen = pick;
                new BukkitRunnable() {
                    @Override public void run() {
                        if (chosen != null) {
                            sink.speak(player, "The '" + chosen + "' schematic should serve nicely, sir.");
                            plugin.getSchematicManager().pasteSchematic(player, chosen);
                        } else if (aiConnector.isReducedMode()) {
                            sink.speak(player, "Nothing in the schematic library fits, sir, and freeform "
                                    + "design is beyond the house systems in local-only mode. "
                                    + "Do add a schematic to the library.");
                        } else if (plugin.getBuildingAssistant() != null) {
                            sink.speak(player, "Nothing suitable in the library — improvising a design, sir.");
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

    public boolean isExtendedAction(String actionType) {
        return switch (actionType) {
            case "give_item", "enchant", "potion_effect", "heal", "feed",
                 "set_gamemode", "teleport", "set_time", "set_weather", "set_gamerule",
                 "summon", "broadcast", "server_say", "lp_group_add", "lp_group_remove",
                 "warp", "discord_broadcast", "paste_schematic",
                 "console_command", "console_commands", "clear_mobs", "clear_drops",
                 "save_world", "set_difficulty", "announce_all", "schedule_broadcast",
                 "request_item" -> true;
            default -> false;
        };
    }

    /** Keyword fallback for when the AI is unreachable or returns nonsense. */
    public void executeFallbackAction(Player player, String message) {
        executeFallbackAction(player, message, CHAT_RESPONDER);
    }

    /**
     * Keyword fallback for when the AI is unreachable or returns nonsense.
     *
     * <p>It deliberately says so when it matches nothing. This path used to
     * return in silence, so a failed parse and a successful no-op looked
     * identical from the chair — a build order that fell in here simply never
     * happened, with no indication why.
     */
    public void executeFallbackAction(Player player, String message, Responder sink) {
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
        else {
            // Nothing matched, and the model call is what failed -- say so
            // rather than leaving the player wondering.
            sink.feedback(player, ChatColor.YELLOW
                    + "Jarvis: I didn't catch that, sir — I heard \"" + trim(message, 60)
                    + "\" but couldn't make an order of it.");
        }
    }
}
