package com.gadgetman.jarvis;

import com.gadgetman.jarvis.ai.AIConnector;
import com.gadgetman.jarvis.ai.AiSettings;
import com.gadgetman.jarvis.building.BuildingAssistant;
import com.gadgetman.jarvis.commands.ActionExecutor;
import com.gadgetman.jarvis.commands.CommandService;
import com.gadgetman.jarvis.commands.CommandSink;
import com.gadgetman.jarvis.core.platform.Audience;
import com.gadgetman.jarvis.core.platform.Butlers;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Subscription;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.events.QuitEvent;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.intent.ChatTrigger;
import com.gadgetman.jarvis.intent.IntentPipeline;
import com.gadgetman.jarvis.memory.ExperienceMemory;
import com.gadgetman.jarvis.npc.ButlerService;
import com.gadgetman.jarvis.npc.portal.PortalScout;
import com.gadgetman.jarvis.progression.ProgressionManager;
import com.gadgetman.jarvis.recovery.TaskRecoveryHandler;
import com.gadgetman.jarvis.schematics.RequestDecomposer;
import com.gadgetman.jarvis.schematics.SchematicExtras;
import com.gadgetman.jarvis.schematics.SchematicLibrary;
import com.gadgetman.jarvis.steward.Courtesies;
import com.gadgetman.jarvis.steward.DutyScheduler;
import com.gadgetman.jarvis.steward.MorningReport;
import com.gadgetman.jarvis.steward.remarks.Remarks;
import com.gadgetman.jarvis.ui.Menus;
import com.gadgetman.jarvis.ui.Prompts;
import com.gadgetman.jarvis.ui.TaskMonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Jarvis, assembled.
 *
 * <p>Everything the butler is, built on a {@link Platform} and a
 * {@link Butlers}. The adapter constructs one of these in its own start-up,
 * hands it the two things only it can supply (the NPC backend and the
 * server-administration {@link ActionExecutor}), and calls {@link #start}.
 * From then on the adapter's job is registration: its command, its listeners,
 * its optional integrations.
 */
public final class JarvisCore {

    private final Platform platform;
    private final String version;
    private final Butlers butlerBackend;
    private final ActionExecutor actions;
    private final Log log;

    private AIConnector ai;
    private DatabaseManager database;
    private ExperienceMemory memory;
    private TaskRecoveryHandler recovery;
    private ButlerService butlers;
    private ProgressionManager progression;
    private BuildingAssistant building;
    private SchematicLibrary schematics;
    private SchematicExtras schematicExtras = SchematicExtras.NONE;
    private RequestDecomposer requestDecomposer;
    private ConfirmationManager confirmations;
    private PlayerRequestManager requests;
    private DutyScheduler duties;
    private MorningReport morningReport;
    private Remarks remarks;
    private PortalScout portalScout;
    private IntentPipeline intents;
    private Prompts prompts;
    private AiSettings aiSettings;
    private ChatTrigger chatTrigger;
    private Courtesies courtesies;
    private TaskMonitor taskMonitor;
    private Menus menus;
    private CommandService commands;

    private final List<Subscription> subscriptions = new ArrayList<>();
    private final List<Task> tasks = new ArrayList<>();

    public JarvisCore(Platform platform, String version, Butlers butlerBackend, ActionExecutor actions) {
        this.platform = platform;
        this.version = version;
        this.butlerBackend = butlerBackend;
        this.actions = actions;
        this.log = platform.log();
    }

    // ==================== LIFECYCLE ====================

    /** Build every subsystem, in dependency order, and start the ones that tick. */
    public void start() {
        Config config = platform.config();

        ai = new AIConnector(config, log);

        database = new DatabaseManager(config, log, platform.dataDir());
        database.initializeDatabaseConnections();

        memory = new ExperienceMemory(config, log, platform.scheduler(), database);
        recovery = new TaskRecoveryHandler(config, log, platform.scheduler(), ai);

        butlers = new ButlerService(platform, butlerBackend, recovery);
        log.info("NPC system initialized on " + butlerBackend.name() + ".");

        // Service record — created before anything can issue him a tool.
        progression = new ProgressionManager(platform, database);
        progression.attach(butlers);
        butlers.setProgression(progression);

        building = new BuildingAssistant(platform, butlers, ai, memory, database, progression);
        schematics = new SchematicLibrary(platform, butlers);
        requestDecomposer = new RequestDecomposer(config, log, ai, database);
        confirmations = new ConfirmationManager(config.getLong("confirmation-timeout-seconds", 30));
        requests = new PlayerRequestManager();
        duties = new DutyScheduler(platform);
        morningReport = new MorningReport(platform, butlers, requests, duties);

        // Idle commentary. Off unless steward.remarks.enabled; start() is a
        // no-op otherwise, so nothing ticks for a server that has not asked.
        remarks = new Remarks(platform, butlers);
        remarks.start();

        // Portals: he notes the ones we pass, and can do the 1:8 arithmetic
        // whether or not he has ever seen one.
        portalScout = new PortalScout(platform, butlers);
        portalScout.start();

        // The one road from an utterance to an action; chat, voice and the
        // command's fallback all use it.
        intents = new IntentPipeline(this);
        prompts = new Prompts(platform);
        aiSettings = new AiSettings(platform, ai);
        chatTrigger = new ChatTrigger(platform, intents, prompts);
        chatTrigger.start();
        courtesies = new Courtesies(platform, ai);
        courtesies.start();

        // Progress bar for long jobs, plus the order queue.
        taskMonitor = new TaskMonitor(this);
        taskMonitor.start();

        menus = new Menus(this);
        menus.start();
        commands = new CommandService(this);

        // Per-session state that goes when the player does
        subscriptions.add(platform.events().on(QuitEvent.class, e -> {
            Owner player = e.who();
            // Idle-remark cooldowns are per-session state; the mute is not, and stays.
            remarks.forget(player);
            portalScout.forget(player);
            log.fine("Cleaned up Jarvis state for disconnected player: " + player.name());
        }));

        // Periodic cleanup of old requests (every 5 minutes)
        tasks.add(platform.scheduler().every(6000L, 6000L, self -> requests.cleanOld()));

        // TPS monitor — warn admins if TPS drops below the threshold
        double tpsThreshold = config.getDouble("butler.tps-warn-threshold", 18.0);
        tasks.add(platform.scheduler().every(1200L, 1200L, self -> {   // every 60 seconds
            double tps = platform.tps();
            if (tps < tpsThreshold) {
                String msg = Colors.RED + "[Jarvis] Warning: Server TPS is "
                        + String.format("%.1f", tps) + " (threshold: " + tpsThreshold + ")";
                for (Owner p : platform.players().online()) {
                    if (p.hasPermission("jarvis.admin")) p.message(msg);
                }
            }
        }));
    }

    /** Stop everything, in the reverse of the order it started. */
    public void shutdown() {
        tasks.forEach(Task::cancel);
        tasks.clear();
        subscriptions.forEach(Subscription::cancel);
        subscriptions.clear();
        if (progression != null) progression.saveAll();
        if (menus != null) menus.shutdown();
        if (taskMonitor != null) taskMonitor.shutdown();
        if (courtesies != null) courtesies.shutdown();
        if (chatTrigger != null) chatTrigger.shutdown();
        if (duties != null) duties.shutdown();
        if (remarks != null) remarks.shutdown();
        if (portalScout != null) portalScout.shutdown();
        if (butlers != null) {
            butlers.shutdown();
            butlers.dismissAll();
        }
        if (building != null) building.shutdown();
        if (database != null) database.closeDatabases();
        log.info("Jarvis AI Companion v" + version + " disabled.");
    }

    /** Re-read config.yml, then tell every subsystem that watches it. */
    public void reload() {
        platform.config().reload();
        if (ai != null) ai.reloadConfig();
        if (memory != null) memory.reload();
        if (recovery != null) recovery.reload();
        if (requestDecomposer != null) requestDecomposer.reload();
        if (remarks != null) remarks.reload();
        if (portalScout != null) portalScout.reload();
        log.info("Jarvis v" + version + " reloaded!");
    }

    /** Give the library an editor for saving clipboards and rotated pastes. */
    public void setSchematicExtras(SchematicExtras extras) {
        this.schematicExtras = extras == null ? SchematicExtras.NONE : extras;
    }

    // ==================== ACCESSORS ====================

    public Platform platform() { return platform; }
    public String version() { return version; }
    public AIConnector ai() { return ai; }
    public DatabaseManager database() { return database; }
    public ExperienceMemory memory() { return memory; }
    public TaskRecoveryHandler recovery() { return recovery; }
    public ButlerService butlers() { return butlers; }
    public ProgressionManager progression() { return progression; }
    public BuildingAssistant building() { return building; }
    public SchematicLibrary schematics() { return schematics; }
    public RequestDecomposer requestDecomposer() { return requestDecomposer; }
    public ActionExecutor actions() { return actions; }
    public ConfirmationManager confirmations() { return confirmations; }
    public PlayerRequestManager requests() { return requests; }
    public DutyScheduler duties() { return duties; }
    public MorningReport morningReport() { return morningReport; }
    public Remarks remarks() { return remarks; }
    public PortalScout portalScout() { return portalScout; }
    public IntentPipeline intents() { return intents; }
    /** Questions answered in chat, for menus that need a typed value. */
    public Prompts prompts() { return prompts; }
    /** The AI providers as an operator sets them up. */
    public AiSettings aiSettings() { return aiSettings; }
    public TaskMonitor taskMonitor() { return taskMonitor; }
    public Menus menus() { return menus; }
    public CommandSink commands() { return commands; }

    public void saveClipboard(Owner player, String name) {
        schematicExtras.saveClipboard(player, name);
    }

    public void rotatedPaste(Owner player, String name, int degrees) {
        schematicExtras.rotateAndPaste(player, name, degrees);
    }

    // ==================== DEBUG ====================

    public void printDebug(Audience requester) {
        log.info("==== Jarvis Debug Info v" + version + " ====");
        requester.message("==== Jarvis Debug Info v" + version + " ====");

        if (ai == null) {
            log.warn("AI connector not initialized");
            requester.message(Colors.RED + "AI connector not initialized");
        } else {
            String info = "AI Provider: " + ai.getProvider() + ", model: " + ai.getModel();
            if (ai.isAutoMode()) {
                info += Colors.GRAY + " (auto mode)";
            }
            log.info(Colors.strip(info));
            requester.message(Colors.YELLOW + info);

            // Show provider status in auto mode
            if (ai.isAutoMode()) {
                requester.message(Colors.GRAY + "--- AI Provider Status ---");
                for (var entry : ai.getProviderStatus().entrySet()) {
                    String status = entry.getValue();
                    String color = status.contains("active") ? Colors.GREEN :
                                   status.contains("available") ? Colors.YELLOW :
                                   status.contains("cooldown") ? Colors.RED : Colors.GRAY;
                    requester.message(Colors.GRAY + "  " + entry.getKey() + ": " + color + status);
                }
            }
        }

        if (butlers == null) {
            log.warn("NPC system not initialized");
            requester.message(Colors.RED + "NPC system not initialized");
        } else {
            String npcInfo = "Active NPCs: " + butlers.getActiveNpcCount();
            String taskInfo = "Active tasks: " + butlers.getActiveTaskCount();
            log.info(npcInfo);
            log.info(taskInfo);
            requester.message(Colors.GREEN + npcInfo);
            requester.message(Colors.GREEN + taskInfo);
        }

        if (database == null) {
            log.warn("Database manager not initialized");
            requester.message(Colors.RED + "Database manager not initialized");
        } else {
            log.info("Database connections initialized");
            requester.message(Colors.GREEN + "Database connections initialized");
        }

        if (memory == null || !memory.isEnabled()) {
            requester.message(Colors.GRAY + "Experience memory: disabled");
        } else {
            int successes = memory.getSuccessCount();
            boolean unlocked = memory.isReducedModeBuildUnlocked();
            requester.message(Colors.GREEN + "Experience memory: " + Colors.WHITE + successes + " successful builds"
                    + (unlocked ? Colors.DARK_GREEN + " (reduced-mode freeform builds unlocked)"
                                : Colors.GRAY + " (reduced-mode freeform builds still locked)"));
            var embedder = memory.getEmbeddingClient();
            requester.message(Colors.GRAY + "  embeddings: " + embedder.getModel() + " — "
                    + (embedder.isAvailable() ? Colors.GREEN + "ok"
                                              : Colors.RED + "cooling down: " + embedder.getLastError()));
        }

        if (recovery == null || !recovery.isEnabled()) {
            requester.message(Colors.GRAY + "Self-explain recovery: disabled");
        } else {
            requester.message(Colors.GREEN + "Self-explain recovery: " + Colors.WHITE + "enabled");
        }

        if (requestDecomposer == null || !requestDecomposer.isEnabled()) {
            requester.message(Colors.GRAY + "Schematic feature tags: disabled");
        } else {
            requester.message(Colors.GREEN + "Schematic feature tags: " + Colors.WHITE + "enabled " + Colors.GRAY + "("
                    + requestDecomposer.getCachedCount() + " requests decomposed this session)");
        }

        // Show systems status
        requester.message(Colors.GRAY + "--- Systems Status ---");
        requester.message(Colors.GREEN + "Core NPC & Mining: " + Colors.DARK_GREEN + "on " + butlerBackend.name()
                + " (native pathfinding + timed block breaking)");
        requester.message(Colors.GREEN + "Building System: " + Colors.DARK_GREEN + "Functional");
        requester.message(Colors.GREEN + "Schematic System: " + Colors.DARK_GREEN + "Functional");

        requester.message(Colors.GRAY + "==========================");
    }
}
