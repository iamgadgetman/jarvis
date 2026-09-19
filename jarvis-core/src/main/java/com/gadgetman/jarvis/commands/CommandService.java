package com.gadgetman.jarvis.commands;

import com.gadgetman.jarvis.ConfirmationManager;
import com.gadgetman.jarvis.JarvisCore;
import com.gadgetman.jarvis.PlayerRequestManager;
import com.gadgetman.jarvis.ai.AIConnector;
import com.gadgetman.jarvis.ai.AiSettings;
import com.gadgetman.jarvis.building.BuildingAssistant;
import com.gadgetman.jarvis.building.ScriptEngineProbe;
import com.gadgetman.jarvis.core.platform.Audience;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.core.text.RichLine;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Environment;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.intent.IntentPipeline;
import com.gadgetman.jarvis.memory.DatasetExporter;
import com.gadgetman.jarvis.npc.ButlerService;
import com.gadgetman.jarvis.npc.portal.PortalLink;
import com.gadgetman.jarvis.npc.portal.PortalScout;
import com.gadgetman.jarvis.npc.portal.PortalSighting;
import com.gadgetman.jarvis.progression.Rank;
import com.gadgetman.jarvis.progression.ServiceRecord;
import com.gadgetman.jarvis.schematics.SchematicLibrary;
import com.gadgetman.jarvis.ui.ControllerBell;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Everything "/jarvis" does. The adapter registers the command and calls
 * {@link #jarvis}; this decides what the words mean.
 */
public class CommandService implements CommandSink {

    /** Library match at or above this score answers the request directly. */
    private static final int SCHEMATIC_MATCH_THRESHOLD = 50;

    private static final Set<String> SIMPLE_SHAPES = Set.of("wall", "floor", "pillar", "cube");

    private static final Rank[] RANKS = Rank.values();

    private final JarvisCore core;
    private final Platform platform;

    public CommandService(JarvisCore core) {
        this.core = core;
        this.platform = core.platform();
    }

    private ButlerService npc() {
        return core.butlers();
    }

    private static boolean isAdmin(Audience sender, Optional<Owner> asPlayer) {
        return asPlayer.map(o -> o.hasPermission("jarvis.admin")).orElse(true);   // the console is
    }

    private static String rest(List<String> args, int from) {
        return String.join(" ", args.subList(Math.min(from, args.size()), args.size()));
    }

    private static Optional<Integer> intArg(List<String> args, int i) {
        if (i >= args.size()) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(args.get(i)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // ==================== ENTRY ====================

    @Override
    public List<String> jarvis(Audience sender, Optional<Owner> asPlayer, List<String> args, boolean tab) {
        if (tab) return complete(args);
        handle(sender, asPlayer, args);
        return List.of();
    }

    private List<String> complete(List<String> args) {
        if (args.size() > 1) {
            if (!args.get(0).equalsIgnoreCase("ai")) return List.of();
            List<String> pool = args.size() == 2 ? AI_SUBCOMMANDS
                    : args.size() == 3 && !args.get(1).equalsIgnoreCase("status") ? AiSettings.PROVIDERS
                    : List.of();
            String prefix = args.get(args.size() - 1).toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String c : pool) {
                if (c.startsWith(prefix)) out.add(c);
            }
            return out;
        }
        String prefix = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String c : COMPLETIONS) {
            if (c.startsWith(prefix)) out.add(c);
        }
        return out;
    }

    private void handle(Audience sender, Optional<Owner> asPlayer, List<String> args) {
        String sub = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);

        // Reload, debug and the dataset dump work from console or a player with permission
        switch (sub) {
            case "reload" -> {
                if (!isAdmin(sender, asPlayer)) { sender.message(Colors.RED + "You don't have permission."); return; }
                core.reload();
                sender.message(Colors.GREEN + "Jarvis: Systems reloaded, sir.");
                return;
            }
            case "debug" -> {
                if (!isAdmin(sender, asPlayer)) { sender.message(Colors.RED + "You don't have permission."); return; }
                core.printDebug(sender);
                return;
            }
            case "voice" -> {
                // Where the chain from microphone to order stands: the one
                // question worth answering when he does not hear you.
                sender.message(Colors.GOLD + "Jarvis: Voice, sir:");
                core.voiceStatus().report(sender);
                return;
            }
            case "ai" -> {
                if (args.size() <= 1 || args.get(1).equalsIgnoreCase("status")) {
                    showAiStatus(sender);
                    return;
                }
                if (!isAdmin(sender, asPlayer)) { sender.message(Colors.RED + "You don't have permission."); return; }
                handleAiSetup(sender, args);
                return;
            }
            // Console-friendly like reload and debug: an admin dumping training data
            // is far more likely to be at a terminal than standing in the world.
            case "export-dataset", "exportdataset" -> {
                if (!isAdmin(sender, asPlayer)) { sender.message(Colors.RED + "You don't have permission."); return; }
                sender.message(Colors.GRAY + "Jarvis: Writing the datasets, sir. One moment.");
                new DatasetExporter(platform, core.database()).exportAsync(sender);
                return;
            }
            default -> { }
        }

        // All other commands are player-only
        if (asPlayer.isEmpty()) {
            sender.message(Colors.RED + "Only players can use other Jarvis commands.");
            return;
        }
        Owner player = asPlayer.get();

        if (args.isEmpty()) {
            showHelp(player);
            return;
        }

        ButlerService npc = npc();
        switch (sub) {
            case "summon" -> npc.summon(player);
            case "dismiss" -> npc.dismiss(player);
            case "return" -> npc.returnToPlayer(player);
            case "attack" -> npc.attack(player);
            case "guard" -> npc.guard(player, args.size() > 1 ? args.get(1) : null);
            case "watch", "sentry" -> npc.watch(player, args.size() > 1 ? args.get(1) : null);
            case "version", "ver", "about" -> showVersion(player);
            case "dig" -> {
                // "/jarvis dig [depth]" -> vertical shaft, default from config
                int depth = 0;
                if (args.size() > 1) {
                    Optional<Integer> d = intArg(args, 1);
                    if (d.isEmpty()) {
                        player.message(Colors.RED + "Usage: /jarvis dig [depth]");
                        return;
                    }
                    depth = d.get();
                }
                npc.digDown(player, depth);
            }
            case "mine" -> {
                // "/jarvis mine here" -> branch mine at current spot
                if (args.size() > 1 && (args.get(1).equalsIgnoreCase("here") || args.get(1).equalsIgnoreCase("branch"))) {
                    npc.startBranchMining(player);
                    return;
                }
                // Pass additional args for ore type
                if (args.size() > 1) {
                    npc.mine(player, args.subList(1, args.size()).toArray(new String[0]));
                } else {
                    npc.mine(player);
                }
            }
            case "stop" -> npc.stop(player);
            case "follow" -> npc.follow(player);
            case "farm" -> npc.farm(player, args.size() > 1 ? rest(args, 1) : null, false);
            case "tend" -> npc.farm(player, args.size() > 1 ? rest(args, 1) : null, true);
            case "chop", "lumber" -> npc.chop(player, intArg(args, 1).orElse(5));
            case "fish" -> npc.fish(player);
            case "dance" -> npc.dance(player);
            case "light" -> {
                // /jarvis light [radius] [type] [spacing] — e.g. /jarvis light 32 torch 8
                // Numbers are read in order (radius, then spacing); words pick the type.
                int radius = -1, spacing = -1;
                String type = null;
                for (int i = 1; i < args.size(); i++) {
                    try {
                        int n = Integer.parseInt(args.get(i));
                        if (radius < 0) radius = n;
                        else if (spacing < 0) spacing = n;
                    } catch (NumberFormatException e) {
                        if (type == null) type = args.get(i);
                    }
                }
                npc.light(player, radius, type, spacing);
            }
            case "patrol" -> npc.patrol(player, args.size() > 1 ? args.get(1) : null);
            case "chest" -> npc.deposits().setChest(player);
            case "deposit" -> npc.deposits().deposit(player);
            case "loot" -> npc.openInventory(player);
            case "clearloot" -> {
                if (args.size() >= 2 && args.get(1).equalsIgnoreCase("confirm")) {
                    npc.clearInventory(player);
                } else {
                    player.message(Colors.YELLOW + "This will drop all collected items at Jarvis's location.");
                    player.message(Colors.RED + "Type " + Colors.WHITE + "/jarvis clearloot confirm"
                            + Colors.RED + " to proceed.");
                }
            }
            case "bell" -> {
                player.give(ControllerBell.create());
                player.message(Colors.GREEN + "Your controller bell, sir. Ring when needed.");
            }

            // Building commands - the library first, the AI builder after
            case "build", "paste" -> handleBuild(player, sub, args);

            case "cancelbuild" -> {
                if (core.building() == null) {
                    player.message(Colors.RED + "Building assistant not available");
                    return;
                }
                core.building().cancelBuild(player);
            }

            // Schematic management commands
            case "schematics", "schematic" -> handleSchematic(player, args);

            // Ask command - simple Q&A with AI
            case "ask" -> {
                if (args.size() < 2) {
                    player.message(Colors.RED + "Usage: /jarvis ask <question>");
                    player.message(Colors.GRAY + "Example: /jarvis ask what are the best mining levels?");
                    return;
                }
                String question = rest(args, 1);
                player.message(Colors.GOLD + "Jarvis: One moment while I consider that, sir...");
                platform.scheduler().async(() -> {
                    try {
                        String answer = core.ai().sendSimpleRequest(question);
                        platform.scheduler().sync(() ->
                                player.message(Colors.AQUA + "Jarvis: " + Colors.WHITE + answer));
                    } catch (Exception e) {
                        platform.log().warn("Failed to get AI answer: " + e.getMessage());
                        platform.scheduler().sync(() ->
                                player.message(Colors.RED + "Jarvis: I couldn't find an answer. Try rephrasing your question."));
                    }
                });
            }

            case "report", "briefing", "status" -> {
                if (core.morningReport() != null) core.morningReport().deliver(player, false);
            }
            case "duties" -> {
                if (core.duties() != null) core.duties().showDuties(player);
            }
            case "duty" -> handleDuty(player, args);
            case "recover" -> npc.getRecoveryService().recover(player);
            case "home" -> {
                if (args.size() > 1 && args.get(1).equalsIgnoreCase("set")) {
                    npc.getEscortService().setHome(player);
                } else {
                    npc.getEscortService().takeHome(player);
                }
            }
            case "confirm"   -> handleConfirm(player);
            case "cancel"    -> handleCancel(player);
            case "requests"  -> handleRequests(player);
            case "approve", "deny" -> {
                if (!player.hasPermission("jarvis.admin")) {
                    player.message(Colors.RED + "You don't have permission.");
                    return;
                }
                if (args.size() < 2) {
                    player.message(Colors.RED + "Usage: /jarvis " + sub + " <id>");
                    return;
                }
                Optional<Integer> id = intArg(args, 1);
                if (id.isEmpty()) {
                    player.message(Colors.RED + "Invalid request ID.");
                    return;
                }
                if (sub.equals("approve")) handleApprove(player, id.get());
                else handleDeny(player, id.get());
            }
            case "tunnel" -> {
                // Either order: "tunnel north 40" and "tunnel 40 north" both read
                // naturally, so take whichever argument parses as a number.
                int len = 0;
                String dir = null;
                for (int i = 1; i < args.size(); i++) {
                    try { len = Integer.parseInt(args.get(i)); }
                    catch (NumberFormatException ignored) { dir = args.get(i); }
                }
                npc.tunnel(player, len, dir);
            }
            case "portal", "portals" -> handlePortal(player, args);
            case "quiet", "hush" -> handleQuiet(player);
            case "rank", "service" -> handleRank(player, args);
            case "queue" -> handleQueue(player, args);
            case "help"      -> showHelp(player);

            default -> {
                // A single unknown word is a typo, not a sentence. Sending it to
                // the model costs a call and returns something confident and
                // invented -- "/jarvis version" produced a made-up Minecraft
                // version that way. Natural language is a phrase; catch the
                // one-word case here and suggest instead.
                if (args.size() == 1) {
                    String near = nearestCommand(args.get(0).toLowerCase(Locale.ROOT));
                    player.message(Colors.RED + "No such command: " + Colors.WHITE + args.get(0));
                    if (near != null) {
                        player.message(Colors.GRAY + "  Did you mean " + Colors.WHITE
                                + "/jarvis " + near + Colors.GRAY + "?");
                    } else {
                        player.message(Colors.GRAY + "  /jarvis help for the list, or "
                                + "phrase it as a sentence to ask me properly.");
                    }
                    return;
                }

                // Unknown subcommand — treat entire input as natural language
                String nlInput = String.join(" ", args);
                player.message(Colors.GOLD + "Jarvis: Very good, sir. On it...");
                core.intents().submit(player, nlInput, IntentPipeline.Source.CONSOLE);
            }
        }
    }

    // ==================== BUILDING ====================

    private void handleBuild(Owner player, String sub, List<String> args) {
        SchematicLibrary schematics = core.schematics();
        if (schematics == null) {
            player.message(Colors.RED + "Schematic manager not available");
            return;
        }

        if (args.size() < 2) {
            player.message(Colors.RED + "Usage: /jarvis build <what you want>");
            player.message(Colors.GRAY + "Examples: /jarvis build castle");
            player.message(Colors.GRAY + "          /jarvis build a small oak cottage");
            player.message(Colors.GRAY + "          /jarvis build undo  |  /jarvis build cancel");
            player.message(Colors.GRAY + "Use /jarvis schematic list to see available schematics");
            return;
        }

        String first = args.get(1).toLowerCase(Locale.ROOT);
        boolean isBuild = sub.equals("build");
        BuildingAssistant assistant = core.building();

        // /jarvis build undo | cancel
        if (isBuild && (first.equals("undo") || first.equals("cancel"))) {
            if (assistant == null) {
                player.message(Colors.RED + "Building assistant not available");
                return;
            }
            if (first.equals("undo")) assistant.undoLastBuild(player);
            else assistant.cancelBuild(player);
            return;
        }

        // /jarvis build wall|floor|pillar|cube [size] — simple shapes, no AI
        if (isBuild && SIMPLE_SHAPES.contains(first)) {
            if (assistant == null) {
                player.message(Colors.RED + "Building assistant not available");
                return;
            }
            int size = intArg(args, 2).orElse(5);
            assistant.buildSimpleStructure(player, first, Math.max(1, Math.min(64, size)));
            return;
        }

        // Explicit rotation form: /jarvis paste <name> rotate <degrees>
        if (args.size() >= 4 && args.get(2).equalsIgnoreCase("rotate")) {
            Optional<Integer> degrees = intArg(args, 3);
            if (degrees.isEmpty()) {
                player.message(Colors.RED + "Invalid rotation. Use: 90, 180, or 270");
            } else {
                core.rotatedPaste(player, args.get(1), degrees.get());
            }
            return;
        }

        // Everything after the subcommand is the request. Reading only
        // args[1] meant "/jarvis build a panic shelter" looked up a
        // schematic called "a".
        String request = rest(args, 1);

        // /jarvis paste stays literal: name in, schematic out, no AI.
        if (!isBuild) {
            schematics.pasteSchematic(player, request);
            return;
        }

        // /jarvis build: use the library when it genuinely matches the
        // request, otherwise hand off to the AI builder.
        String match = schematics.bestMatchName(request);
        int score = schematics.bestMatchScore(request);

        if (match != null && score >= SCHEMATIC_MATCH_THRESHOLD) {
            player.message(Colors.AQUA + "Jarvis: " + Colors.WHITE
                    + "The '" + match + "' schematic should serve nicely, sir.");
            schematics.pasteSchematic(player, match);
        } else if (assistant != null) {
            player.message(Colors.GRAY + "Jarvis: Nothing suitable in the library"
                    + " — improvising a design, sir.");
            assistant.startBuild(player, request);
        } else {
            player.message(Colors.RED + "Building assistant not available");
        }
    }

    private void handleSchematic(Owner player, List<String> args) {
        SchematicLibrary schematics = core.schematics();
        if (schematics == null) {
            player.message(Colors.RED + "Schematic manager not available");
            return;
        }

        if (args.size() < 2) {
            schematics.listSchematics(player);
            return;
        }

        String schematicSub = args.get(1).toLowerCase(Locale.ROOT);
        switch (schematicSub) {
            case "list" -> schematics.listSchematics(player);

            case "paste", "load" -> {
                if (args.size() < 3) {
                    player.message(Colors.RED + "Usage: /jarvis schematic paste <name>");
                    return;
                }
                schematics.pasteSchematic(player, args.get(2));
            }

            case "save" -> {
                if (args.size() < 3) {
                    player.message(Colors.RED + "Usage: /jarvis schematic save <name>");
                    player.message(Colors.GRAY + "First use //copy to copy your selection");
                    return;
                }
                core.saveClipboard(player, args.get(2));
            }

            case "rotate" -> {
                if (args.size() < 4) {
                    player.message(Colors.RED + "Usage: /jarvis schematic rotate <name> <degrees>");
                    player.message(Colors.GRAY + "Example: /jarvis schematic rotate castle 90");
                    return;
                }
                Optional<Integer> degrees = intArg(args, 3);
                if (degrees.isEmpty()) {
                    player.message(Colors.RED + "Invalid rotation. Use: 90, 180, or 270");
                } else {
                    core.rotatedPaste(player, args.get(2), degrees.get());
                }
            }

            case "scan", "reload" -> {
                player.message(Colors.GOLD + "Jarvis: Scanning for schematics...");
                schematics.scanFolder();
                player.message(Colors.GREEN + "Jarvis: Found " + schematics.getSchematics().size() + " schematics.");
            }

            case "folder" -> {
                player.message(Colors.GOLD + "Schematics folder: " + Colors.YELLOW + schematics.getSchematicFolder());
                player.message(Colors.GRAY + "Place .schem, .schematic, or .litematic files here");
                player.message(Colors.GRAY + "Then use /jarvis schematic scan");
            }

            case "litematic", "litematics" -> schematics.showLitematicFiles(player);

            case "convert" -> {
                if (args.size() < 3) {
                    player.message(Colors.RED + "Usage: /jarvis schematic convert <name>");
                    player.message(Colors.GRAY + "Converts .litematic to .schem format");
                    return;
                }
                schematics.convertLitematic(player, args.get(2));
            }

            case "convertall" -> {
                player.message(Colors.GOLD + "Converting all .litematic files...");
                schematics.convertAllLitematics(player);
            }

            default -> {
                player.message(Colors.RED + "Unknown schematic command.");
                player.message(Colors.GRAY + "Use: list, paste, save, rotate, scan, convert, convertall");
            }
        }
    }

    // ==================== RANK ====================

    /**
     * /jarvis rank — how he has earned his kit.
     * /jarvis rank set &lt;name|number&gt; | reset  (admin)
     */
    private void handleRank(Owner player, List<String> args) {
        var progression = core.progression();
        if (progression == null || !progression.isEnabled()) {
            player.message(Colors.GRAY + "Jarvis: Progression is switched off, sir.");
            return;
        }
        ServiceRecord record = progression.recordOf(player);
        Rank rank = progression.rankOf(player);

        if (args.size() >= 2 && (args.get(1).equalsIgnoreCase("set") || args.get(1).equalsIgnoreCase("reset"))) {
            if (!player.hasPermission("jarvis.admin")) {
                player.message(Colors.RED + "Jarvis: That is not yours to decide, sir.");
                return;
            }
            if (args.get(1).equalsIgnoreCase("reset")) {
                progression.save(player.id(), new ServiceRecord());
                player.message(Colors.YELLOW + "Service record cleared. Reconnect to reload it.");
                return;
            }
            if (args.size() < 3) {
                player.message(Colors.RED + "Usage: /jarvis rank set <name|1-" + RANKS.length + ">");
                return;
            }
            Rank target = null;
            try {
                int n = Integer.parseInt(args.get(2));
                if (n >= 1 && n <= RANKS.length) target = RANKS[n - 1];
            } catch (NumberFormatException ignored) {
                for (Rank r : RANKS) {
                    if (r.name().equalsIgnoreCase(args.get(2)) || r.title().equalsIgnoreCase(args.get(2))) target = r;
                }
            }
            if (target == null) {
                player.message(Colors.RED + "No such rank: " + args.get(2));
                return;
            }
            record.setServiceFloor(target);
            progression.save(player.id(), record);
            progression.reissueKit(player);
            player.message(Colors.GREEN + "Jarvis is now " + target.title() + ".");
            return;
        }

        player.message(Colors.GOLD + "=== Jarvis - Service Record ===");
        player.message(Colors.WHITE + "  Rank " + rank.number() + "/" + RANKS.length
                + Colors.GRAY + " - " + Colors.YELLOW + rank.title());
        if (progression.isExempt(player)) {
            player.message(Colors.LIGHT_PURPLE + "  Operator: issued the top kit without the climb.");
        } else {
            player.message(Colors.GRAY + "  Service: " + Colors.WHITE + record.service());
            Rank next = rank.next();
            if (next != null) {
                player.message(Colors.GRAY + "  Next: " + Colors.AQUA + next.title()
                        + Colors.GRAY + " in " + Colors.WHITE + record.serviceToNext()
                        + Colors.GRAY + " more - brings " + Colors.WHITE + next.whatIsNew());
            } else {
                player.message(Colors.GRAY + "  Nothing left to earn, sir.");
            }
        }
        player.message(Colors.GRAY + "  Ore " + record.oresMined()
                + " | Trees " + record.treesFelled()
                + " | Crops " + record.cropsHarvested()
                + " | Fish " + record.fishCaught()
                + " | Threats " + record.threatsFelled()
                + " | Blocks " + record.blocksPlaced());
    }

    // ==================== QUEUE ====================

    /**
     * /jarvis queue &lt;order&gt; | list | clear — line up orders.
     *
     * <p>The point is not to type less, it is to stop standing about waiting
     * for one job to finish before giving the next.
     */
    private void handleQueue(Owner player, List<String> args) {
        var monitor = core.taskMonitor();
        if (monitor == null) {
            player.message(Colors.RED + "Jarvis: The queue is not running, sir.");
            return;
        }
        if (args.size() < 2 || args.get(1).equalsIgnoreCase("list")) {
            var pending = monitor.queued(player.id());
            if (pending.isEmpty()) {
                player.message(Colors.GRAY + "Jarvis: Nothing on the list, sir.");
                return;
            }
            player.message(Colors.GOLD + "Jarvis — orders in hand:");
            int n = 1;
            for (String order : pending) {
                player.message(Colors.GRAY + "  " + (n++) + ". " + Colors.WHITE + "/jarvis " + order);
            }
            return;
        }
        if (args.get(1).equalsIgnoreCase("clear")) {
            monitor.clearQueue(player);
            return;
        }
        monitor.enqueue(player, rest(args, 1));
    }

    // ==================== DUTIES ====================

    /** /jarvis duty add <interval_minutes> <message...> | remove <id> */
    private void handleDuty(Owner player, List<String> args) {
        if (!player.hasPermission("jarvis.admin")) {
            player.message(Colors.RED + "You don't have permission.");
            return;
        }
        if (core.duties() == null) return;
        if (args.size() < 2) {
            player.message(Colors.RED + "Usage: /jarvis duty add <interval_minutes> <message...>");
            player.message(Colors.RED + "       /jarvis duty remove <id>");
            return;
        }
        switch (args.get(1).toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.size() < 4) {
                    player.message(Colors.RED + "Usage: /jarvis duty add <interval_minutes> <message...>");
                    return;
                }
                try {
                    long minutes = Long.parseLong(args.get(2));
                    var duty = core.duties().addBroadcast(rest(args, 3), minutes * 60, minutes * 60, player.name());
                    player.message(Colors.GREEN + "Jarvis: Duty #" + duty.id
                            + " noted, sir — every " + minutes + " minutes.");
                } catch (NumberFormatException e) {
                    player.message(Colors.RED + "Invalid interval (minutes).");
                }
            }
            case "remove", "delete" -> {
                if (args.size() < 3) {
                    player.message(Colors.RED + "Usage: /jarvis duty remove <id>");
                    return;
                }
                Optional<Integer> id = intArg(args, 2);
                if (id.isEmpty()) {
                    player.message(Colors.RED + "Invalid duty id.");
                } else if (core.duties().remove(id.get())) {
                    player.message(Colors.GREEN + "Jarvis: Duty #" + id.get() + " struck from the schedule, sir.");
                } else {
                    player.message(Colors.RED + "No duty with id " + id.get() + ".");
                }
            }
            default -> player.message(Colors.RED + "Use: add, remove");
        }
    }

    // ==================== AI ====================

    /** /jarvis ai — routing and provider health at a glance. */
    private void showAiStatus(Audience player) {
        AIConnector ai = core.ai();
        player.message(Colors.GOLD + "═══ Jarvis AI Status ═══");
        if (ai.isReducedMode()) {
            player.message(Colors.YELLOW + "Mode: REDUCED (Ollama only) — freeform builds and risky"
                    + " console actions are off");
        } else {
            player.message(Colors.GREEN + "Mode: Tiered routing");
        }
        String lightLast = ai.getLastServed(AIConnector.Tier.LIGHT);
        String heavyLast = ai.getLastServed(AIConnector.Tier.HEAVY);
        player.message(Colors.WHITE + "Light route " + Colors.GRAY + "(chat, intents): "
                + Colors.AQUA + String.join(" → ", ai.getLightRoute())
                + (lightLast != null ? Colors.GRAY + "  (last: " + lightLast + ")" : ""));
        player.message(Colors.WHITE + "Heavy route " + Colors.GRAY + "(build plans): "
                + Colors.AQUA + String.join(" → ", ai.getHeavyRoute())
                + (heavyLast != null ? Colors.GRAY + "  (last: " + heavyLast + ")" : ""));
        player.message(Colors.WHITE + "Providers:");
        for (var entry : ai.getProviderStatus().entrySet()) {
            String status = entry.getValue();
            String color = status.contains("active") || status.contains("available") ? Colors.GREEN
                    : status.contains("cooldown") ? Colors.RED : Colors.GRAY;
            player.message(Colors.GRAY + "  " + entry.getKey() + ": " + color + status);
        }
        player.message(Colors.GOLD + "════════════════════════");
    }

    private static final List<String> AI_SUBCOMMANDS = List.of(
            "status", "enable", "disable", "key", "endpoint", "model", "models", "test");

    /**
     * {@code /jarvis ai enable|disable|key|endpoint|model|test <provider> [value]}
     * and {@code /jarvis ai models}: the bell menu's AI page as words, for the
     * console and for anyone who prefers typing.
     */
    private void handleAiSetup(Audience sender, List<String> args) {
        AiSettings settings = core.aiSettings();
        String what = args.get(1).toLowerCase(Locale.ROOT);
        if (what.equals("models")) {
            String target = args.size() > 2 ? settings.resolve(args.get(2)) : "ollama";
            if (!"ollama".equals(target)) {
                sender.message(Colors.RED + "Jarvis: Only an Ollama server can be asked what it offers, sir.");
                return;
            }
            sender.message(Colors.GRAY + "Jarvis: Asking " + settings.endpoint("ollama") + "...");
            settings.ollamaModels(
                    models -> sender.message(models.isEmpty()
                            ? Colors.YELLOW + "Jarvis: The server has no models pulled yet, sir."
                            : Colors.GREEN + "Jarvis: Models on offer: " + Colors.WHITE + String.join(", ", models)),
                    why -> sender.message(Colors.RED + "Jarvis: I could not reach it, sir: " + why));
            return;
        }
        if (!AI_SUBCOMMANDS.contains(what)) {
            sender.message(Colors.RED + "Usage: /jarvis ai [status | enable | disable | key | endpoint | model | models | test] <provider> [value]");
            return;
        }
        if (args.size() < 3) {
            sender.message(Colors.RED + "Usage: /jarvis ai " + what + " <" + String.join("|", AiSettings.PROVIDERS) + ">"
                    + (what.equals("key") || what.equals("endpoint") || what.equals("model") ? " <value>" : ""));
            return;
        }
        String provider = settings.resolve(args.get(2));
        if (provider == null) {
            sender.message(Colors.RED + "Jarvis: I know " + String.join(", ", AiSettings.PROVIDERS) + ", sir; not '" + args.get(2) + "'.");
            return;
        }
        String value = rest(args, 3);
        switch (what) {
            case "enable" -> {
                settings.setEnabled(provider, true);
                sender.message(Colors.GREEN + "Jarvis: " + provider + " is on the list, sir. Status: " + settings.status(provider) + ".");
            }
            case "disable" -> {
                settings.setEnabled(provider, false);
                sender.message(Colors.YELLOW + "Jarvis: " + provider + " is off the list, sir.");
            }
            case "key" -> {
                if (!settings.needsKey(provider)) {
                    sender.message(Colors.GRAY + "Jarvis: " + provider + " needs no key, sir.");
                    return;
                }
                if (value.isEmpty()) {
                    sender.message(Colors.RED + "Usage: /jarvis ai key " + provider + " <key>");
                    return;
                }
                settings.setKey(provider, value);
                sender.message(Colors.GREEN + "Jarvis: Key for " + provider + " stored, sir.");
                sender.message(Colors.GRAY + "A word of caution: commands are written to the server log. "
                        + "The bell menu asks for keys in chat instead, which is not.");
            }
            case "endpoint" -> {
                if (!settings.setEndpoint(provider, value)) {
                    sender.message(Colors.RED + "Jarvis: An address starts with http:// or https://, sir.");
                    return;
                }
                sender.message(Colors.GREEN + "Jarvis: " + provider + " will be asked at "
                        + (value.isEmpty() ? "its default address" : value) + ", sir.");
            }
            case "model" -> {
                if (value.isEmpty()) {
                    sender.message(Colors.RED + "Usage: /jarvis ai model " + provider + " <model>");
                    return;
                }
                settings.setModel(provider, value);
                sender.message(Colors.GREEN + "Jarvis: " + provider + " will use " + value + ", sir.");
            }
            case "test" -> {
                sender.message(Colors.GRAY + "Jarvis: Trying " + provider + " (" + settings.model(provider) + ")...");
                settings.test(provider, verdict -> sender.message(
                        (verdict.startsWith("failed") ? Colors.RED : Colors.GREEN) + "Jarvis: " + provider + " " + verdict));
            }
            default -> showAiStatus(sender);
        }
    }

    // ==================== CONFIRMATIONS AND REQUESTS ====================

    private void handleConfirm(Owner player) {
        ConfirmationManager cm = core.confirmations();
        if (!cm.hasPending(player.id())) {
            player.message(Colors.YELLOW + "Jarvis: No pending action, or it timed out.");
            return;
        }
        String actionType  = cm.getPendingAction(player.id());
        JSONObject params   = cm.getPendingParameters(player.id());
        cm.clearPending(player.id());

        String result = core.actions().execute(actionType, params, player);
        if (result != null) {
            player.message(Colors.GREEN + "[Jarvis] " + result);
        }
    }

    private void handleCancel(Owner player) {
        ConfirmationManager cm = core.confirmations();
        if (!cm.hasPending(player.id())) {
            player.message(Colors.YELLOW + "Jarvis: Nothing to cancel.");
            return;
        }
        cm.clearPending(player.id());
        player.message(Colors.GRAY + "Jarvis: Action cancelled. Wise choice, perhaps.");
    }

    private void handleRequests(Owner player) {
        if (!player.hasPermission("jarvis.admin")) {
            player.message(Colors.RED + "You don't have permission.");
            return;
        }
        PlayerRequestManager rm = core.requests();
        if (rm == null || !rm.hasPending()) {
            player.message(Colors.GRAY + "Jarvis: No pending item requests.");
            return;
        }
        player.message(Colors.GOLD + "═══ Pending Item Requests ═══");
        for (var req : rm.getAllRequests()) {
            player.rich(new RichLine()
                    .text(Colors.YELLOW + "#" + req.id + " ")
                    .text(Colors.WHITE + req.playerName + " wants " + req.amount + "x " + req.item
                            + (req.reason.isEmpty() ? "" : " — " + req.reason))
                    .link(Colors.GREEN + " [Approve]", "/jarvis approve " + req.id, "Approve this request")
                    .link(Colors.RED + " [Deny]", "/jarvis deny " + req.id, "Deny this request"));
        }
        player.message(Colors.GOLD + "════════════════════════════");
    }

    private void handleApprove(Owner admin, int id) {
        PlayerRequestManager rm = core.requests();
        if (rm == null) { admin.message(Colors.RED + "Request system not available."); return; }
        var req = rm.getRequest(id);
        if (req == null) {
            admin.message(Colors.RED + "Request #" + id + " not found or already handled.");
            return;
        }
        rm.removeRequest(id);

        // Give item to the requesting player
        Optional<String> itemId = platform.items().resolve(req.item);
        if (itemId.isEmpty()) {
            admin.message(Colors.RED + "Unknown item: " + req.item + ". Request removed.");
            return;
        }

        Optional<Owner> target = platform.players().byId(req.playerUUID);
        if (target.isPresent()) {
            target.get().give(Item.of(itemId.get(), req.amount));
            target.get().message(Colors.GREEN + "Jarvis: " + admin.name()
                    + " approved your request for " + req.amount + "x " + req.item + ".");
            admin.message(Colors.GREEN + "Approved #" + id + ": gave " + req.amount + "x "
                    + req.item + " to " + req.playerName + ".");
        } else {
            admin.message(Colors.YELLOW + req.playerName + " is offline. Item will be given when they rejoin.");
            admin.message(Colors.GRAY + "(Offline delivery not yet implemented — re-approve when they join)");
        }
    }

    private void handleDeny(Owner admin, int id) {
        PlayerRequestManager rm = core.requests();
        if (rm == null) { admin.message(Colors.RED + "Request system not available."); return; }
        var req = rm.getRequest(id);
        if (req == null) {
            admin.message(Colors.RED + "Request #" + id + " not found or already handled.");
            return;
        }
        rm.removeRequest(id);

        platform.players().byId(req.playerUUID).ifPresent(target ->
                target.message(Colors.RED + "Jarvis: " + admin.name()
                        + " denied your request for " + req.amount + "x " + req.item + "."));
        admin.message(Colors.GRAY + "Denied request #" + id + " from " + req.playerName + ".");
    }

    // ==================== TYPOS ====================

    /** Every subcommand the switch above accepts, for typo suggestions. */
    private static final List<String> KNOWN_COMMANDS = List.of(
            "about", "add", "ai", "approve", "queue", "rank", "tunnel", "ask", "attack", "bell",
            "branch_mine", "briefing", "broadcast", "build", "cancel", "cancelbuild", "export-dataset",
            "chat", "chest", "chop", "chop_trees", "clearloot", "come", "confirm", "convert", "convertall",
            "dance", "defend", "delete", "deny", "deposit", "dig", "discord_broadcast", "dismiss", "duties",
            "duty", "enchant", "farm", "feed", "fight", "fish", "folder", "follow", "give_item", "guard",
            "heal", "help", "home", "inventory", "light", "light_area", "list", "litematic", "litematics",
            "load", "loot", "lp_group_add", "lp_group_remove", "lumber", "mine", "mine_here", "mining",
            "paste", "paste_schematic", "patrol", "potion_effect", "protect", "recover", "reload", "remove",
            "report", "requests", "return", "rotate", "save", "scan", "schematic", "schematics", "sentry",
            "server_say", "set_chest", "set_gamemode", "set_gamerule", "set_time", "set_weather",
            "stand_down", "status", "stop", "summon", "talk", "teleport", "tend", "ver", "version", "voice",
            "warp", "watch");

    /** The top-level words worth offering on tab. */
    private static final List<String> COMPLETIONS = List.of(
            "summon", "dismiss", "return", "follow", "stop", "attack", "guard", "watch", "mine", "dig",
            "tunnel", "farm", "tend", "chop", "fish", "dance", "light", "patrol", "chest", "deposit", "loot",
            "clearloot", "bell", "build", "paste", "cancelbuild", "schematic", "ask", "ai", "report", "duties",
            "duty", "recover", "home", "confirm", "cancel", "requests", "approve", "deny", "portal", "portals",
            "quiet", "rank", "queue", "version", "voice", "help", "reload", "debug", "export-dataset");

    /**
     * Closest command to a mistyped one, or null when nothing is close.
     *
     * <p>Edit distance rather than prefix matching, so "verison" and "dismis"
     * both land. Anything further than two edits away is treated as not a typo.
     */
    static String nearestCommand(String typed) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String c : KNOWN_COMMANDS) {
            int d = editDistance(typed, c);
            if (d < bestDist) { bestDist = d; best = c; }
        }
        return bestDist <= 2 ? best : null;
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    // ==================== VERSION ====================

    /**
     * What is actually running.
     *
     * <p>Added because there was no way to ask. "/jarvis version" fell through
     * to the natural-language handler, which sent the word "version" to a
     * model and got back a confident, invented Minecraft version -- while the
     * help screen showed a number that had been typed in seven releases ago.
     */
    private void showVersion(Owner player) {
        player.message(Colors.GOLD + "Jarvis " + Colors.WHITE + "v" + core.version());
        player.message(Colors.GRAY + "  Server: " + Colors.WHITE + platform.serverVersion());
        player.message(Colors.GRAY + "  Java: " + Colors.WHITE + System.getProperty("java.version"));

        String planner = core.building() == null ? "none" : core.building().getPlanner();
        boolean graal = ScriptEngineProbe.isAvailable();
        player.message(Colors.GRAY + "  Build planner: " + Colors.WHITE + planner
                + Colors.GRAY + (graal ? " (GraalJS present)" : " (GraalJS MISSING)"));

        player.message(Colors.GRAY + "  NPC backend: " + Colors.WHITE + npc().backendName());
        player.message(Colors.GRAY + "  AI: " + Colors.WHITE + core.ai().getProvider() + "/" + core.ai().getModel());
    }

    // ==================== PORTALS ====================

    /**
     * Nether portals: what he has seen, where they come out, and taking you to
     * one.
     *
     * <p>The three answers are deliberately different in kind. The list and the
     * escort depend on him having <i>seen</i> a portal, which is bounded by what
     * the server has loaded. "Where does this come out" depends on nothing at
     * all — it is arithmetic, and it works in the middle of nowhere.
     */
    private void handlePortal(Owner player, List<String> args) {
        PortalScout scout = core.portalScout();
        if (scout == null) {
            player.message(Colors.GRAY + "Jarvis: The portal service is not running, sir.");
            return;
        }
        String sub = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT)
                : (args.get(0).equalsIgnoreCase("portals") ? "list" : "");

        switch (sub) {
            case "where", "link", "other" -> portalWhere(player, scout);
            case "mark", "note" -> {
                boolean isNew = scout.mark(player);
                player.message(Colors.GOLD + "Jarvis: " + Colors.WHITE
                        + (isNew ? "Noted, sir. I shall remember this portal."
                                 : "I had this one already, sir."));
            }
            case "forget", "clear" -> {
                int gone = scout.forgetAll(player);
                player.message(Colors.GOLD + "Jarvis: " + Colors.WHITE
                        + (gone == 0 ? "I had none to forget, sir."
                                     : "Forgotten, sir — all " + gone + " of them."));
            }
            case "list" -> portalList(player, scout);
            default -> portalEscort(player, scout);
        }
    }

    private Optional<World> worldOf(Owner player) {
        return platform.world(player.world());
    }

    /** The arithmetic: where this side's portal lands on the other. */
    private void portalWhere(Owner player, PortalScout scout) {
        Environment environment = worldOf(player).map(World::environment).orElse(Environment.NORMAL);
        if (environment == Environment.END) {
            player.message(Colors.GOLD + "Jarvis: " + Colors.WHITE
                    + "The End does not pair with anything, sir. Only the Nether keeps that arrangement.");
            return;
        }
        boolean inNether = environment == Environment.NETHER;

        // The nearest portal he knows, if you are practically standing at it;
        // otherwise your own position, which answers "where should I dig".
        PortalSighting nearest = scout.nearest(player);
        BlockPos at = player.pos().block();
        int x = at.x(), y = at.y(), z = at.z();
        boolean atKnownPortal = nearest != null && nearest.distanceTo(x, y, z) <= 16;
        if (atKnownPortal) {
            x = nearest.x();
            y = nearest.y();
            z = nearest.z();
        }

        PortalLink.Coords link = PortalLink.counterpart(x, y, z, inNether);
        String side = inNether ? "Overworld" : "Nether";

        player.message(Colors.GOLD + "Jarvis: " + Colors.WHITE
                + (atKnownPortal ? "That portal comes out " : "A portal here would come out ")
                + "at roughly " + Colors.YELLOW + "x " + link.x() + ", z " + link.z()
                + Colors.WHITE + " in the " + side + ", sir.");
        player.message(Colors.GRAY + "         The game will link to any portal within "
                + PortalLink.LINK_RADIUS
                + " blocks of that before building a new one"
                + (inNether ? "." : " — which is why two portals close together in the Nether"
                              + " end up sharing an exit."));
    }

    /** What he has seen in this world, nearest first. */
    private void portalList(Owner player, PortalScout scout) {
        List<PortalSighting> known = scout.known(player);
        if (known.isEmpty()) {
            player.message(Colors.GOLD + "Jarvis: " + Colors.WHITE
                    + "None on record in this world, sir. I note them as we pass them.");
            return;
        }
        player.message(Colors.GOLD + "Portals I have seen here:");
        Vec3 at = player.pos();
        for (PortalSighting sighting : known) {
            Vec3 where = new Vec3(sighting.x(), sighting.y(), sighting.z());
            player.message(Colors.WHITE + "  x " + sighting.x() + ", y " + sighting.y()
                    + ", z " + sighting.z() + Colors.GRAY + " — "
                    + (int) at.distance(where) + "m " + PortalScout.bearing(at, where));
        }
    }

    /** Lead the way to the nearest one he knows. */
    private void portalEscort(Owner player, PortalScout scout) {
        PortalSighting nearest = scout.nearest(player);
        if (nearest == null) {
            player.message(Colors.GOLD + "Jarvis: " + Colors.WHITE
                    + "I know of no portal in this world, sir. I only see as far as the world is loaded — "
                    + "walk a little and I shall note any we pass, or say '/jarvis portal mark' at one.");
            return;
        }
        World world = worldOf(player).orElse(null);
        if (world == null) return;
        Vec3 where = new Vec3(nearest.x() + 0.5, nearest.y(), nearest.z() + 0.5);
        Vec3 at = player.pos();
        npc().getEscortService().escortTo(player, new Site(world, where),
                "The portal is " + (int) at.distance(where) + " metres "
                        + PortalScout.bearing(at, where) + ", sir. This way — stay close.",
                "The portal, sir. I shall wait on this side; I do not travel well between worlds.",
                "That portal is in another world, sir — which is rather the difficulty.");
    }

    // ==================== QUIET ====================

    /**
     * The mute. Idle remarks are the one thing he says without being asked, so
     * turning them off has to be a sentence rather than a config edit and a
     * reload — the whole failure mode is a player being quietly annoyed and
     * never saying so.
     */
    private void handleQuiet(Owner player) {
        var remarks = core.remarks();
        if (remarks == null || !remarks.isEnabled()) {
            player.message(Colors.GRAY + "Jarvis: I keep my observations to myself already, sir.");
            return;
        }
        boolean nowMuted = remarks.toggleMute(player);
        player.message(Colors.GOLD + "Jarvis: " + Colors.WHITE
                + (nowMuted ? "Very good, sir. I shall hold my tongue."
                            : "As you wish, sir. I shall speak up when something warrants it."));
    }

    // ==================== HELP ====================

    private void showHelp(Owner player) {
        player.message(Colors.GOLD + "═══════════════════════════════");
        player.message(Colors.GOLD + "  Jarvis — AI Butler v" + core.version());
        player.message(Colors.GOLD + "═══════════════════════════════");

        player.message(Colors.WHITE + "  /jarvis version" + Colors.GRAY + " - What is actually running");
        player.message(Colors.YELLOW + "NPC Commands:");
        player.message(Colors.WHITE + "  /jarvis summon" + Colors.GRAY + " - Bring Jarvis to you");
        player.message(Colors.WHITE + "  /jarvis dismiss" + Colors.GRAY + " - Send Jarvis away");
        player.message(Colors.WHITE + "  /jarvis return" + Colors.GRAY + " - Recall Jarvis to your side");
        player.message(Colors.WHITE + "  /jarvis stop" + Colors.GRAY + " - Stop current task");
        player.message(Colors.WHITE + "  /jarvis guard [stance]" + Colors.GRAY + " - Bodyguard mode (passive/defensive/aggressive)");
        player.message(Colors.WHITE + "  /jarvis watch" + Colors.GRAY + " - Night watch: hold this position");
        player.message(Colors.WHITE + "  /jarvis attack" + Colors.GRAY + " - Weapons free (aggressive guard)");
        player.message(Colors.WHITE + "  /jarvis mine [ore]" + Colors.GRAY + " - Mine nearby ores (e.g. diamond)");
        player.message(Colors.WHITE + "  /jarvis mine here" + Colors.GRAY + " - Dig a torch-lit branch mine");
        player.message(Colors.WHITE + "  /jarvis follow" + Colors.GRAY + " - Follow you and carry loot");
        player.message(Colors.WHITE + "  /jarvis farm [crop]" + Colors.GRAY + " - Harvest & replant the field once");
        player.message(Colors.WHITE + "  /jarvis tend [crop]" + Colors.GRAY + " - Stay on as a farmhand");
        player.message(Colors.WHITE + "  /jarvis chop [n]" + Colors.GRAY + " - Fell trees, replant saplings");
        player.message(Colors.WHITE + "  /jarvis fish" + Colors.GRAY + " - A spot of fishing");
        player.message(Colors.WHITE + "  /jarvis dance" + Colors.GRAY + " - The performance");
        player.message(Colors.WHITE + "  /jarvis patrol add|start|clear" + Colors.GRAY + " - Guard a waypoint circuit");
        player.message(Colors.WHITE + "  /jarvis light [radius] [type] [spacing]" + Colors.GRAY + " - Spawn-proof the area (torch/end_rod/lantern)");
        player.message(Colors.WHITE + "  /jarvis chest" + Colors.GRAY + " - Register the chest you're looking at");
        player.message(Colors.WHITE + "  /jarvis deposit" + Colors.GRAY + " - Deliver loot to your chest");
        player.message(Colors.WHITE + "  /jarvis loot" + Colors.GRAY + " - Open inventory");
        player.message(Colors.WHITE + "  /jarvis clearloot" + Colors.GRAY + " - Drop all collected items");
        player.message(Colors.WHITE + "  /jarvis bell" + Colors.GRAY + " - Get controller bell");
        player.message(Colors.WHITE + "  /jarvis report" + Colors.GRAY + " - Server status briefing");
        player.message(Colors.WHITE + "  /jarvis duties" + Colors.GRAY + " - Standing scheduled duties");
        player.message(Colors.WHITE + "  /jarvis recover" + Colors.GRAY + " - Retrieve your death drops");
        player.message(Colors.WHITE + "  /jarvis tunnel [n|s|e|w] [length]" + Colors.GRAY + " - Drive a 3x3 passage (Peerless rank)");
        player.message(Colors.WHITE + "  /jarvis portal" + Colors.GRAY + " - Lead you to the nearest portal he has seen");
        player.message(Colors.WHITE + "  /jarvis portal where" + Colors.GRAY + " - Where this side comes out on the other");
        player.message(Colors.WHITE + "  /jarvis portals" + Colors.GRAY + " - Portals he has noted in this world");
        player.message(Colors.WHITE + "  /jarvis quiet" + Colors.GRAY + " - Stop the idle remarks (toggle)");
        player.message(Colors.WHITE + "  /jarvis rank" + Colors.GRAY + " - Service record and what he has earned");
        player.message(Colors.WHITE + "  /jarvis queue <order>" + Colors.GRAY + " - Line up an order for when he's free");
        player.message(Colors.WHITE + "  /jarvis queue list|clear" + Colors.GRAY + " - Review or tear up the list");
        player.message(Colors.WHITE + "  /jarvis home set" + Colors.GRAY + " - Save this spot as home");
        player.message(Colors.WHITE + "  /jarvis home" + Colors.GRAY + " - Have Jarvis escort you home");

        if (core.schematics() != null) {
            player.message(Colors.YELLOW + "Schematic Commands:");
            player.message(Colors.WHITE + "  /jarvis schematic list" + Colors.GRAY + " - List available schematics");
            player.message(Colors.WHITE + "  /jarvis schematic paste <name>" + Colors.GRAY + " - Paste schematic");
            player.message(Colors.WHITE + "  /jarvis schematic save <name>" + Colors.GRAY + " - Save clipboard as schematic");
            player.message(Colors.WHITE + "  /jarvis schematic rotate <name> <deg>" + Colors.GRAY + " - Paste rotated");
            player.message(Colors.WHITE + "  /jarvis schematic scan" + Colors.GRAY + " - Rescan schematic folder");
            player.message(Colors.WHITE + "  /jarvis build <name>" + Colors.GRAY + " - Quick paste (alias)");
            player.message(Colors.YELLOW + "Litematic Conversion:");
            player.message(Colors.WHITE + "  /jarvis schematic litematic" + Colors.GRAY + " - List .litematic files");
            player.message(Colors.WHITE + "  /jarvis schematic convert <name>" + Colors.GRAY + " - Convert to .schem");
            player.message(Colors.WHITE + "  /jarvis schematic convertall" + Colors.GRAY + " - Convert all litematics");
        }

        player.message(Colors.YELLOW + "Natural Language (just type it):");
        player.message(Colors.GRAY + "  Chat: 'jarvis kill all creepers'");
        player.message(Colors.GRAY + "  Command: /jarvis kill all creepers nearby");
        player.message(Colors.WHITE + "  /jarvis ask <question>" + Colors.GRAY + " - Ask anything");

        if (core.requests() != null) {
            player.message(Colors.YELLOW + "Item Requests:");
            player.message(Colors.GRAY + "  Say 'jarvis, can I have <item>?' to request items from admins");
        }

        if (player.hasPermission("jarvis.admin")) {
            player.message(Colors.YELLOW + "Admin Commands:");
            player.message(Colors.WHITE + "  /jarvis reload" + Colors.GRAY + " - Reload config");
            player.message(Colors.WHITE + "  /jarvis debug" + Colors.GRAY + " - Debug info");
            player.message(Colors.WHITE + "  /jarvis ai" + Colors.GRAY + " - AI routing & provider health");
            player.message(Colors.WHITE + "  /jarvis requests" + Colors.GRAY + " - View pending item requests");
            player.message(Colors.WHITE + "  /jarvis approve <id>" + Colors.GRAY + " - Approve item request");
            player.message(Colors.WHITE + "  /jarvis deny <id>" + Colors.GRAY + " - Deny item request");
            player.message(Colors.WHITE + "  /jarvis export-dataset" + Colors.GRAY + " - Dump intent & build pairs as JSONL");
            player.message(Colors.GRAY + "  Console AI: 'jarvis, kill all creepers' — asks before executing");
        }

        player.message(Colors.GOLD + "═══════════════════════════════");
    }
}
