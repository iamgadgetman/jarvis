package com.gadgetman.jarvis;

import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import com.gadgetman.jarvis.ai.AIConnector;
import com.gadgetman.jarvis.npc.JarvisNPC;
import com.gadgetman.jarvis.commands.JarvisCommands;
import com.gadgetman.jarvis.ui.UIManager;
import com.gadgetman.jarvis.DatabaseManager;
import com.gadgetman.jarvis.building.BuildingAssistant;
import com.gadgetman.jarvis.schematics.SchematicManager;
import com.gadgetman.jarvis.listeners.ChatListener;
import com.gadgetman.jarvis.steward.DutyScheduler;
import com.gadgetman.jarvis.steward.MorningReport;
import com.gadgetman.jarvis.listeners.PlayerConnectionListener;
import com.gadgetman.jarvis.listeners.PlayerEventListener;
import org.bukkit.command.CommandSender;

/**
 * Jarvis AI Butler Plugin
 * Version: 0.1.0
 *
 * Main plugin class that manages all subsystems
 */
public class Jarvis extends JavaPlugin {

    private static final String VERSION = "0.7.0";

    private AIConnector aiConnector;
    private JarvisNPC jarvisNPC;
    private UIManager uiManager;
    private DatabaseManager databaseManager;
    private BuildingAssistant buildingAssistant;
    private SchematicManager schematicManager;
    private JarvisActionExecutor actionExecutor;
    private ConfirmationManager confirmationManager;
    private PlayerRequestManager playerRequestManager;
    private DutyScheduler dutyScheduler;
    private MorningReport morningReport;

    @Override
    public void onEnable() {
        getLogger().info("Jarvis AI Companion v" + VERSION + " enabling...");

        saveDefaultConfig();

        aiConnector = new AIConnector(this);

        databaseManager = new DatabaseManager(this);
        databaseManager.initializeDatabaseConnections();

        if (getServer().getPluginManager().getPlugin("Citizens") != null) {
            jarvisNPC = new JarvisNPC(this);
            getLogger().info("NPC system initialized.");
        } else {
            getLogger().warning("Citizens not found - NPC features disabled.");
            return;
        }

        getCommand("jarvis").setExecutor(new JarvisCommands(this));

        uiManager = new UIManager(this);

        // Initialize systems
        buildingAssistant = new BuildingAssistant(this);
        schematicManager = new SchematicManager(this);
        actionExecutor = new JarvisActionExecutor(this);
        confirmationManager = new ConfirmationManager(
                getConfig().getLong("confirmation-timeout-seconds", 30));
        playerRequestManager = new PlayerRequestManager();
        dutyScheduler = new DutyScheduler(this);
        morningReport = new MorningReport(this);

        // Register listeners
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerEventListener(this), this);
        getServer().getPluginManager().registerEvents(morningReport, this);

        // Periodic cleanup of old requests (every 5 minutes)
        getServer().getScheduler().runTaskTimer(this,
                () -> playerRequestManager.cleanOld(), 6000L, 6000L);

        // TPS monitor — warn admins if TPS drops below 18
        double tpsThreshold = getConfig().getDouble("butler.tps-warn-threshold", 18.0);
        getServer().getScheduler().runTaskTimer(this, () -> {
            double[] tps = getServer().getTPS();
            if (tps.length > 0 && tps[0] < tpsThreshold) {
                String msg = org.bukkit.ChatColor.RED + "[Jarvis] Warning: Server TPS is "
                        + String.format("%.1f", tps[0]) + " (threshold: " + tpsThreshold + ")";
                for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
                    if (p.hasPermission("jarvis.admin")) p.sendMessage(msg);
                }
            }
        }, 1200L, 1200L); // every 60 seconds

        getLogger().info("Jarvis AI Companion v" + VERSION + " enabled successfully!");
        getLogger().info("NPC, mining, building, and steward systems are at your service.");
    }

    @Override
    public void onDisable() {
        if (jarvisNPC != null) {
            jarvisNPC.dismissAll();
        }
        if (databaseManager != null) {
            databaseManager.closeDatabases();
        }
        getLogger().info("Jarvis AI Companion v" + VERSION + " disabled.");
    }

    public void reload() {
        reloadConfig();
        if (aiConnector != null) {
            aiConnector.reloadConfig();
        }
        getLogger().info("Jarvis v" + VERSION + " reloaded!");
    }

    // ========== GETTERS ==========

    public AIConnector getAIConnector() {
        return aiConnector;
    }

    public JarvisNPC getJarvisNPC() {
        return jarvisNPC;
    }
    
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
    
    public BuildingAssistant getBuildingAssistant() {
        return buildingAssistant;
    }
    
    public SchematicManager getSchematicManager() {
        return schematicManager;
    }

    public JarvisActionExecutor getActionExecutor() {
        return actionExecutor;
    }

    public ConfirmationManager getConfirmationManager() {
        return confirmationManager;
    }

    public PlayerRequestManager getPlayerRequestManager() {
        return playerRequestManager;
    }

    public DutyScheduler getDutyScheduler() {
        return dutyScheduler;
    }

    public MorningReport getMorningReport() {
        return morningReport;
    }

    public String getVersion() {
        return VERSION;
    }

    // ========== DEBUG ==========

    public void printDebug(CommandSender requester) {
        getLogger().info("==== Jarvis Debug Info v" + VERSION + " ====");
        requester.sendMessage("==== Jarvis Debug Info v" + VERSION + " ====");

        if (aiConnector == null) {
            getLogger().warning("AI connector not initialized");
            requester.sendMessage("§cAI connector not initialized");
        } else {
            String provider = aiConnector.getProvider();
            String modelName = aiConnector.getModel();
            boolean hasKey = aiConnector.hasApiKey();
            boolean autoMode = aiConnector.isAutoMode();

            String info = "AI Provider: " + provider + ", model: " + modelName;
            if (autoMode) {
                info += " §7(auto mode)";
            }
            getLogger().info(info);
            requester.sendMessage("§e" + info);

            // Show provider status in auto mode
            if (autoMode) {
                requester.sendMessage("§7--- AI Provider Status ---");
                for (var entry : aiConnector.getProviderStatus().entrySet()) {
                    String status = entry.getValue();
                    String color = status.contains("active") ? "§a" :
                                   status.contains("available") ? "§e" :
                                   status.contains("cooldown") ? "§c" : "§7";
                    requester.sendMessage("§7  " + entry.getKey() + ": " + color + status);
                }
            }
        }

        if (jarvisNPC == null) {
            getLogger().warning("NPC system not initialized");
            requester.sendMessage("§cNPC system not initialized");
        } else {
            String npcInfo = "Active NPCs: " + jarvisNPC.getActiveNpcCount();
            String taskInfo = "Active tasks: " + jarvisNPC.getActiveTaskCount();
            getLogger().info(npcInfo);
            getLogger().info(taskInfo);
            requester.sendMessage("§a" + npcInfo);
            requester.sendMessage("§a" + taskInfo);
        }

        if (databaseManager == null) {
            getLogger().warning("Database manager not initialized");
            requester.sendMessage("§cDatabase manager not initialized");
        } else {
            getLogger().info("Database connections initialized");
            requester.sendMessage("§aDatabase connections initialized");
        }

        // Show systems status
        requester.sendMessage("§7--- Systems Status ---");
        requester.sendMessage("§aCore NPC & Mining: §2v0.1.0 (Citizens pathfinding + timed block breaking)");
        requester.sendMessage("§aBuilding System: §2Functional");
        requester.sendMessage("§aSchematic System: §2Functional");

        requester.sendMessage("§7==========================");
    }

    // ========== CONTROLLER BELL ==========

    public NamespacedKey getControllerKey() {
        return new NamespacedKey(this, "jarvis-controller");
    }

    public ItemStack getControllerBell() {
        ItemStack bell = new ItemStack(Material.BELL);
        ItemMeta meta = bell.getItemMeta();
        meta.setDisplayName("§6Jarvis Controller");
        meta.setLore(java.util.List.of("§7Right-click to open menu", "§7Works when placed too!"));
        meta.getPersistentDataContainer().set(getControllerKey(), PersistentDataType.BYTE, (byte) 1);
        bell.setItemMeta(meta);
        return bell;
    }
}
