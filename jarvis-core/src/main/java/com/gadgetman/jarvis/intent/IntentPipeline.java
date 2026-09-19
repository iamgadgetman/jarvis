package com.gadgetman.jarvis.intent;

import com.gadgetman.jarvis.JarvisCore;
import com.gadgetman.jarvis.ai.AIConnector;
import com.gadgetman.jarvis.commands.ActionExecutor;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Scheduler;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.core.text.RichLine;
import com.gadgetman.jarvis.npc.ButlerService;
import com.gadgetman.jarvis.schematics.RequestFeatures;
import com.gadgetman.jarvis.schematics.SchematicLibrary;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one road from "a player said something" to "Jarvis did something".
 *
 * <p>Chat, voice and the slash command's natural-language fallback all come
 * through here. What differs between them is not the parse, it is where the
 * answer comes out: chat writes to the chat box, voice speaks. That is the
 * {@link Responder}, and it is the only seam between them.
 *
 * <p>Threading matters: the world context is gathered on the server thread,
 * the slow AI call runs async, and execution returns to the server thread.
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
        void speak(Owner player, String jarvisLine);
        void feedback(Owner player, String line);
    }

    /** The original behaviour: everything goes to the chat box. */
    public static final Responder CHAT_RESPONDER = new Responder() {
        @Override public void speak(Owner player, String jarvisLine) {
            player.message(Colors.AQUA + "Jarvis: " + Colors.WHITE + jarvisLine);
        }
        @Override public void feedback(Owner player, String line) {
            player.message(line);
        }
    };

    /** Actions too risky to trust to a small local model's parsing. */
    private static final Set<String> RESTRICTED_IN_REDUCED_MODE = Set.of(
            "console_command", "console_commands", "lp_group_add", "lp_group_remove", "set_gamerule");

    private final JarvisCore core;
    private final Platform platform;
    private final AIConnector ai;

    public IntentPipeline(JarvisCore core) {
        this.core = core;
        this.platform = core.platform();
        this.ai = core.ai();
    }

    private Scheduler scheduler() {
        return platform.scheduler();
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
    public void submit(Owner player, String utterance, Source source, Responder responder) {
        final Responder sink = responder == null ? CHAT_RESPONDER : responder;

        // Build the world context on the SERVER thread first, then do the
        // slow AI call async.
        scheduler().sync(() -> {
            final String worldContext = buildContext(player);
            scheduler().async(() -> parseAndDispatch(player, utterance, worldContext, source, sink));
        });
    }

    /** Convenience for the chat path, which always answers in chat. */
    public void submit(Owner player, String utterance, Source source) {
        submit(player, utterance, source, CHAT_RESPONDER);
    }

    // ==================== PARSE ====================

    private void parseAndDispatch(Owner player, String message, String worldContext,
                                  Source source, Responder sink) {
        try {
            String context  = worldContext + buildMemoryContext(player);
            String response = ai.parseNaturalLanguage(message, player.name(), context);

            JSONObject action     = new JSONObject(extractJson(response));
            String actionType     = action.optString("action", "unknown");
            JSONObject parameters = action.optJSONObject("parameters");
            String aiResponse     = action.optString("response", "");

            if (actionType.isEmpty() || actionType.equals("unknown")) {
                throw new RuntimeException("No action in AI response");
            }

            scheduler().sync(() -> executeAction(player, actionType, parameters, aiResponse, message, source, sink));

        } catch (Exception e) {
            platform.log().warn("Natural language parse failed: " + e.getMessage());
            scheduler().sync(() -> executeFallbackAction(player, message, sink));
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
    private String buildMemoryContext(Owner player) {
        if (core.database() == null) return "";
        int turns = ai.isReducedMode() ? 2 : 5;
        var recent = core.database().getRecentInteractions(player.id().toString(), turns);
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

    /** Must run on the server thread. */
    public String buildContext(Owner player) {
        World world = platform.world(player.world()).orElse(null);
        String worldName = world == null ? player.world().id() : world.name();
        String biome = world == null ? "unknown" : world.biome(player.pos().block());
        return "Location: " + worldName
                + ", Biome: " + biome
                + ", Health: " + (int) player.health() + "/20"
                + ", Gamemode: " + player.gameMode()
                + ", Jarvis summoned: " + core.butlers().exists(player);
    }

    // ==================== EXECUTE ====================

    private void executeAction(Owner player, String actionType, JSONObject parameters,
                               String aiResponse, String originalMessage,
                               Source source, Responder sink) {

        // Always show Jarvis's witty response first
        if (!aiResponse.isEmpty()) {
            sink.speak(player, aiResponse);
        }

        // Butler memory: remember this exchange (async — DB writes off-thread)
        if (core.database() != null) {
            String finalAction = actionType;
            scheduler().async(() -> core.database().logChatInteraction(
                    player.id().toString(), originalMessage, aiResponse, finalAction));
        }

        // Reduced (Ollama-only) mode: decline high-risk actions parsed by a small local model
        if (ai.isReducedMode() && RESTRICTED_IN_REDUCED_MODE.contains(actionType)
                && !platform.config().getBoolean("ai.reduced-mode.allow-risky-actions", false)) {
            sink.feedback(player, Colors.YELLOW
                    + "Jarvis: I'd rather not run " + actionType + " on local-model judgement alone, sir. "
                    + "Use the slash command directly, or enable ai.reduced-mode.allow-risky-actions.");
            return;
        }

        ActionExecutor executor = core.actions();
        ButlerService npc = core.butlers();

        // Server-administration actions belong to the adapter's executor
        if (executor != null && executor.handles(actionType)) {
            if (executor.dangerousActions().contains(actionType)) {
                queueForConfirmation(player, actionType, parameters, executor);
            } else {
                String result = executor.execute(actionType, parameters, player);
                if (result != null) {
                    sink.feedback(player, Colors.GREEN + "[Jarvis] " + result);
                }
            }
            return;
        }

        // Core NPC / existing actions
        switch (actionType.toLowerCase(Locale.ROOT)) {
            case "summon"                  -> npc.summon(player);
            case "dismiss"                 -> npc.dismiss(player);
            case "return", "come"          -> npc.returnToPlayer(player);
            case "follow"                  -> npc.follow(player);
            case "mine_here", "branch_mine"-> npc.startBranchMining(player);
            case "tunnel" -> npc.tunnel(player,
                    parameters == null ? 0 : parameters.optInt("length", 0),
                    parameters == null ? null : parameters.optString("direction", null));
            case "dig_down" -> npc.digDown(player,
                    parameters == null ? 0 : parameters.optInt("depth", 0));
            case "deposit"                 -> npc.deposits().deposit(player);
            case "set_chest"               -> npc.deposits().setChest(player);
            case "attack", "fight"         -> npc.guard(player, "aggressive");
            case "guard", "defend", "protect"-> npc.guard(player, "defensive");
            case "watch", "sentry"         -> npc.watch(player, null);
            case "stand_down"              -> npc.guard(player, "passive");
            case "mine", "mining"          -> npc.mine(player);
            case "stop"                    -> npc.stop(player);
            case "loot", "inventory"       -> npc.openInventory(player);
            case "build" -> {
                String desc = (parameters != null) ? parameters.optString("description", "") : "";
                if (desc.isEmpty()) {
                    sink.feedback(player, Colors.RED + "Jarvis: What would you like me to build?");
                    return;
                }
                startSchematicFirstBuild(player, desc, sink);
            }
            case "report" -> {
                if (core.morningReport() != null) core.morningReport().deliver(player, false);
            }
            case "recover" -> npc.getRecoveryService().recover(player);
            case "take_home" -> npc.getEscortService().takeHome(player);
            case "set_home" -> npc.getEscortService().setHome(player);
            case "farm" -> npc.farm(player,
                    parameters != null ? parameters.optString("crop", null) : null, false);
            case "tend" -> npc.farm(player,
                    parameters != null ? parameters.optString("crop", null) : null, true);
            case "chop", "chop_trees" -> npc.chop(player,
                    parameters != null ? parameters.optInt("count", 5) : 5);
            case "fish" -> npc.fish(player);
            case "dance" -> npc.dance(player);
            case "patrol" -> npc.patrol(player, "start");
            case "light", "light_area" -> npc.light(player,
                    parameters != null ? parameters.optInt("radius", -1) : -1,
                    parameters != null ? parameters.optString("type", null) : null,
                    parameters != null ? parameters.optInt("spacing", -1) : -1);
            case "clearloot" -> npc.clearInventory(player);
            case "chat", "talk" -> {
                // AI response already delivered above — nothing else needed
            }
            default -> {
                if (aiResponse.isEmpty()) {
                    sink.feedback(player, Colors.GRAY + "Jarvis: I'm not sure what you want me to do.");
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
    private void startSchematicFirstBuild(Owner player, String desc, Responder sink) {
        scheduler().async(() -> {
            String pick = null;
            SchematicLibrary schematics = core.schematics();
            RequestFeatures features = RequestFeatures.none();

            if (schematics != null) {
                List<String> names = new ArrayList<>();
                for (var info : schematics.getSchematics()) {
                    names.add(info.name());
                }
                if (!names.isEmpty()) {
                    var decomposer = core.requestDecomposer();
                    if (decomposer != null) features = decomposer.decompose(desc);

                    int threshold = platform.config().getInt("schematics.feature-tags.match-threshold", 90);
                    int score = schematics.bestMatchScore(desc, features);
                    if (score >= threshold) {
                        pick = schematics.bestMatchName(desc, features);
                        platform.log().fine("Feature match for \"" + desc + "\": "
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
                            pick = ai.pickSchematic(enriched, names);
                        } catch (Exception e) {
                            platform.log().fine("Schematic pick failed: " + e.getMessage());
                        }
                    }
                }
            }
            final String chosen = pick;
            scheduler().sync(() -> {
                if (chosen != null) {
                    sink.speak(player, "The '" + chosen + "' schematic should serve nicely, sir.");
                    core.schematics().pasteSchematic(player, chosen);
                } else if (ai.isReducedMode()) {
                    sink.speak(player, "Nothing in the schematic library fits, sir, and freeform "
                            + "design is beyond the house systems in local-only mode. "
                            + "Do add a schematic to the library.");
                } else if (core.building() != null) {
                    sink.speak(player, "Nothing suitable in the library — improvising a design, sir.");
                    core.building().startBuild(player, desc);
                }
            });
        });
    }

    /** Show a clickable confirmation prompt for dangerous actions. */
    private void queueForConfirmation(Owner player, String actionType,
                                      JSONObject parameters, ActionExecutor executor) {
        String description = executor.describe(actionType, parameters);
        core.confirmations().setPending(player.id(), actionType, parameters, description);

        player.message(Colors.YELLOW + "Jarvis: I want to — " + description);
        player.rich(new RichLine()
                .link(Colors.GREEN + "[Confirm]", "/jarvis confirm", "Execute: " + description)
                .link(Colors.RED + " [Cancel]", "/jarvis cancel", "Cancel this action"));
        player.message(Colors.GRAY + "Expires in " + core.confirmations().getTimeoutSeconds() + "s.");
    }

    /** Keyword fallback for when the AI is unreachable or returns nonsense. */
    public void executeFallbackAction(Owner player, String message) {
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
    public void executeFallbackAction(Owner player, String message, Responder sink) {
        ButlerService npc = core.butlers();
        if (message.contains("summon") || message.contains("come"))     npc.summon(player);
        else if (message.contains("dismiss") || message.contains("away")) npc.dismiss(player);
        else if (message.contains("mine") || message.contains("dig"))   npc.mine(player);
        else if (message.contains("attack") || message.contains("fight")) npc.guard(player, "aggressive");
        else if (message.contains("guard") || message.contains("protect")) npc.guard(player, "defensive");
        else if (message.contains("watch")) npc.watch(player, null);
        else if (message.contains("follow")) npc.follow(player);
        else if (message.contains("return") || message.contains("back")) npc.returnToPlayer(player);
        else if (message.contains("deposit")) npc.deposits().deposit(player);
        else if (message.contains("farm")) npc.farm(player, message, false);
        else if (message.contains("chop") || message.contains("trees")) npc.chop(player, 5);
        else if (message.contains("fish")) npc.fish(player);
        else if (message.contains("dance")) npc.dance(player);
        else if (message.contains("light")) npc.light(player, -1, null, -1);
        else if (message.contains("loot") || message.contains("inventory")) npc.openInventory(player);
        else {
            // Nothing matched, and the model call is what failed -- say so
            // rather than leaving the player wondering.
            sink.feedback(player, Colors.YELLOW
                    + "Jarvis: I didn't catch that, sir — I heard \"" + trim(message, 60)
                    + "\" but couldn't make an order of it.");
        }
    }
}
