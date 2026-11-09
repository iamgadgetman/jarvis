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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    /**
     * Actions too risky to trust to a small local model's parsing. The fast
     * path never produces these either.
     */
    static final Set<String> RESTRICTED_IN_REDUCED_MODE = Set.of(
            "console_command", "console_commands", "lp_group_add", "lp_group_remove", "set_gamerule");

    /** Actions whose message reaches every player (or Discord), not just the one who spoke. */
    static final Set<String> PUBLIC_ACTIONS = Set.of(
            "server_say", "broadcast", "announce_all", "schedule_broadcast", "discord_broadcast");

    /** Words that show the player asked for an announcement rather than an answer. */
    private static final java.util.regex.Pattern ASKED_TO_ANNOUNCE = java.util.regex.Pattern.compile(
            "\\b(announce\\w*|broadcast\\w*|everyone|everybody|all players|all the players"
                    + "|the (whole )?server|server[- ]wide|discord|tell (them|all)|let (everyone|all)"
                    + "|remind (everyone|all|people)|in (global|public) chat)\\b");

    /** True when the model picked a whole-server action the player never asked for. */
    static boolean publicActionWithoutAsking(String actionType, String message) {
        if (!PUBLIC_ACTIONS.contains(actionType)) return false;
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return !ASKED_TO_ANNOUNCE.matcher(m).find();
    }

    /** What to say privately in place of a public action: its message if it has one. */
    static String privateReply(JSONObject parameters, String aiResponse) {
        String msg = parameters == null ? "" : parameters.optString("message", "").trim();
        return msg.isEmpty() ? aiResponse : msg;
    }

    private final JarvisCore core;
    private final Platform platform;
    private final AIConnector ai;
    /**
     * Bad extra-phrases already warned about, so the log says it once. Server
     * thread only. The warning appears on the first fast-path line after
     * startup, and is not repeated after a reload while the server keeps running.
     */
    private final java.util.Set<String> warnedAliases = new java.util.HashSet<>();

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
            // Literal commands are answered locally, before any model is asked.
            if (tryFastPath(player, utterance, source, sink)) return;
            final String worldContext = buildContext(player);
            scheduler().async(() -> parseAndDispatch(player, utterance, worldContext, source, sink));
        });
    }

    /** Convenience for the chat path, which always answers in chat. */
    public void submit(Owner player, String utterance, Source source) {
        submit(player, utterance, source, CHAT_RESPONDER);
    }

    // ==================== FAST PATH ====================

    /**
     * Run a plain command ("follow me", "mine 10 iron") without the AI.
     * Server thread. Returns false when the parser can't place the sentence,
     * or the fast path is off, and the model should decide.
     */
    private boolean tryFastPath(Owner player, String utterance, Source source, Responder sink) {
        var cfg = platform.config();
        if (!cfg.getBoolean("intent.fast-path.enabled", true)) return false;

        FastPathParser parser = new FastPathParser(extraPhrases());
        for (String alias : parser.droppedAliases()) {
            if (warnedAliases.add(alias)) {
                platform.log().warn("[fast-path] Ignoring intent.fast-path.extra-phrases entry " + alias
                        + ": aliases can only point at Jarvis's own actions ("
                        + String.join(", ", new java.util.TreeSet<>(FastPathParser.ALIAS_TARGETS)) + ")");
            }
        }
        Optional<Intent> hit = parser.parse(utterance);
        if (hit.isEmpty()) return false;
        Intent intent = hit.get();

        // A request must name a real item: try the words as typed, then the
        // parser's singular guess. Neither known -> let the model work it out.
        if ("request_item".equals(intent.action())) {
            Optional<String> id = resolveRequestedItem(intent.params());
            if (id.isEmpty()) return false;
            intent.params().put("item", id.get());
            intent.params().remove("said");
        }

        if (cfg.getBoolean("intent.fast-path.log-hits", false)) {
            platform.log().info("[fast-path] " + intent.action() + " " + intent.params()
                    + " <- \"" + trim(utterance, 60) + "\"");
        }
        executeAction(player, intent.action(), executorParams(intent),
                FastPathParser.acknowledge(intent), utterance, source, sink);
        return true;
    }

    /** The registry id for a fast-path request: as typed first, then singular. */
    Optional<String> resolveRequestedItem(JSONObject params) {
        var items = platform.items();
        if (params.has("said")) {
            Optional<String> asSaid = items.resolve(params.getString("said"));
            if (asSaid.isPresent()) return asSaid;
        }
        return params.has("item") ? items.resolve(params.getString("item")) : Optional.empty();
    }

    /** intent.fast-path.extra-phrases, phrase -> action. Re-read each time so a reload takes. */
    private Map<String, String> extraPhrases() {
        var section = platform.config().section("intent.fast-path.extra-phrases");
        Map<String, String> out = new HashMap<>();
        for (String phrase : section.keys()) {
            String action = section.getString(phrase, null);
            if (action != null) out.put(phrase, action);
        }
        return out;
    }

    /**
     * The parser speaks {@code item}/{@code count}; the actions read their own
     * names. mine filters by {@code ore} (no count: he mines what he finds),
     * request_item wants {@code amount}, chop already reads {@code count}.
     */
    static JSONObject executorParams(Intent intent) {
        JSONObject in = intent.params();
        JSONObject out = new JSONObject(in.toString());
        switch (intent.action()) {
            case "mine" -> {
                out.remove("item");
                out.remove("count");
                if (in.has("item")) out.put("ore", in.getString("item"));
            }
            case "request_item" -> {
                out.remove("said");
                out.remove("count");
                if (in.has("count")) out.put("amount", in.getInt("count"));
            }
            default -> { }
        }
        return out;
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

            // A model that wants to "say" its answer reaches for server_say,
            // which posts it to every player as [Server]. Only a player who
            // asked for an announcement gets one; otherwise it is a reply.
            // The model then puts its real answer in the action's message and
            // filler ("A tactical observation, sir.") in response, so say the message.
            boolean keptPrivate = publicActionWithoutAsking(actionType, message);
            String chosen = keptPrivate ? "chat" : actionType;
            String reply = keptPrivate ? privateReply(parameters, aiResponse) : aiResponse;

            scheduler().sync(() -> executeAction(player, chosen, parameters, reply, message, source, sink));

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
        return com.gadgetman.jarvis.ai.ModelJson.extractObject(reply);
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

        // "summon" with no entity is Jarvis himself, not a mob: the adapter's
        // executor would otherwise offer to spawn a zombie for "come here".
        boolean npcSummon = "summon".equals(actionType)
                && (parameters == null || !parameters.has("entity"));

        // Server-administration actions belong to the adapter's executor
        if (!npcSummon && executor != null && executor.handles(actionType)) {
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

        // Core NPC / existing actions. When a reply was already spoken above, the
        // action's own "order received" line (ButlerService.confirm) stays quiet,
        // so one order gets one answer.
        Runnable order = () -> dispatchNpc(player, actionType, parameters, aiResponse, sink, npc);
        if (!aiResponse.isEmpty() && npc != null) npc.runAnswered(order);
        else order.run();
    }

    private void dispatchNpc(Owner player, String actionType, JSONObject parameters,
                             String aiResponse, Responder sink, ButlerService npc) {
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
            case "mine", "mining" -> {
                String ore = parameters == null ? "" : parameters.optString("ore", "");
                if (ore.isEmpty()) npc.mine(player);
                else npc.mine(player, new String[]{ore});
            }
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
            case "undo" -> core.undo().undo(player, 1);
            case "redo" -> core.undo().redo(player);
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

    /** "undo that", "put it back", "take that down": a request to undo the last build. */
    public static boolean isUndo(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("undo") || m.contains("put it back") || m.contains("put that back")
                || m.contains("put everything back") || m.contains("take it down")
                || m.contains("take that down");
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
        // Before "back" and "come": "put it back" is an undo, not a recall.
        if (isUndo(message)) core.undo().undo(player, 1);
        else if (message.contains("redo")) core.undo().redo(player);
        else if (message.contains("summon") || message.contains("come"))     npc.summon(player);
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
