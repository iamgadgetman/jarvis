package com.gadgetman.jarvis;

import com.gadgetman.jarvis.commands.JarvisCommands;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Scheduler;
import com.gadgetman.jarvis.npc.provider.CitizensButlers;
import com.gadgetman.jarvis.npc.provider.CitizensInteractListener;
import com.gadgetman.jarvis.npc.provider.CitizensNPCProvider;
import com.gadgetman.jarvis.platform.PaperPlatform;
import com.gadgetman.jarvis.schematics.SchematicManager;
import com.gadgetman.jarvis.ui.PaperBell;
import com.gadgetman.jarvis.voice.PaperVoice;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Jarvis AI Butler, the Paper plugin.
 *
 * <p>A bootstrap: it builds the {@link PaperPlatform}, gives core a Citizens
 * body for the butler and a Bukkit executor for the admin actions, and
 * constructs {@link JarvisCore}. Everything the butler does lives in core;
 * what is left here is registration with the server.
 */
public class Jarvis extends JavaPlugin {

    /**
     * Read from plugin.yml, which takes its value from the pom. A hardcoded
     * constant here drifted: every v0.7.x release reported itself as v0.7.0
     * in the log and in /jarvis debug.
     */
    private String version = "unknown";

    // The platform core talks through; see docs/dev/platform-interface.md.
    private PaperPlatform platform;
    private JarvisCore core;

    private CitizensNPCProvider npcProvider;
    private SchematicManager schematicManager;
    private PaperVoice voice;

    @Override
    public void onEnable() {
        version = getPluginMeta().getVersion();

        getLogger().info("Jarvis AI Companion v" + version + " enabling...");

        saveDefaultConfig();
        // databases.yml is not covered by saveDefaultConfig(), which only writes
        // config.yml. Without this the data folder has no databases.yml, no data
        // source is ever registered, and every getConnection() throws.
        saveResource("databases.yml", false);

        platform = new PaperPlatform(this);
        platform.registerEvents();

        if (getServer().getPluginManager().getPlugin("Citizens") == null) {
            getLogger().warning("Citizens not found - NPC features disabled.");
            return;
        }

        // The butler's body, and the server-administration actions: the two
        // things core cannot supply for itself.
        npcProvider = new CitizensNPCProvider(this);
        CitizensButlers butlers = new CitizensButlers(this, npcProvider);
        // So a blow landed on him reaches his Defender as a ButlerDamagedEvent.
        platform.paperEvents().butlerResolver(butlers::ownerOf);

        core = new JarvisCore(platform, version, butlers, new JarvisActionExecutor(this));
        core.start();

        // WorldEdit, when present, accelerates JSON pastes and adds clipboard
        // saves and rotated pastes.
        schematicManager = new SchematicManager(this, core.schematics());
        core.setSchematicExtras(schematicManager);

        // Bukkit registration
        JarvisCommands commands = new JarvisCommands(this);
        getCommand("jarvis").setExecutor(commands);
        getCommand("jarvis").setTabCompleter(commands);
        getServer().getPluginManager().registerEvents(new PaperBell(platform.paperEvents()), this);
        getServer().getPluginManager().registerEvents(npcProvider.inventoryView(), this);
        getServer().getPluginManager().registerEvents(
                new CitizensInteractListener(npcProvider, platform.paperEvents()), this);

        // Ears. No-ops unless voice.enabled and Simple Voice Chat is installed.
        voice = new PaperVoice(this);
        voice.register();

        getLogger().info("Jarvis AI Companion v" + version + " enabled successfully!");
        getLogger().info("NPC, mining, building, and steward systems are at your service.");
    }

    @Override
    public void onDisable() {
        if (voice != null) {
            voice.shutdown();
        }
        if (core != null) {
            core.shutdown();
        }
    }

    // ========== GETTERS ==========

    /** Everything core needs from the server, in one place. */
    public PaperPlatform getPlatform() {
        return platform;
    }

    /** Jarvis himself. Null when Citizens is missing. */
    public JarvisCore core() {
        return core;
    }

    /** Core's handle for a player. Cheap; make one whenever a core call needs it. */
    public Owner owner(Player player) {
        return platform.owner(player);
    }

    /** Core's view of config.yml. Bukkit-side code may keep using getConfig(). */
    public Config getCoreConfig() {
        return platform.config();
    }

    public Log getLog() {
        return platform.log();
    }

    public Scheduler getScheduler() {
        return platform.scheduler();
    }

    /** The Citizens registry, for the one place that needs the NPC entity itself. */
    public CitizensNPCProvider getNpcProvider() {
        return npcProvider;
    }

    public SchematicManager getSchematicManager() {
        return schematicManager;
    }

    public PaperVoice getVoice() {
        return voice;
    }

    public String getVersion() {
        return version;
    }
}
