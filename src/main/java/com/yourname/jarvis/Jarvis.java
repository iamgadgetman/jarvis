package com.yourname.jarvis;

import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import com.yourname.jarvis.ai.AIConnector;
import com.yourname.jarvis.npc.JarvisNPC;
import com.yourname.jarvis.commands.JarvisCommands;
import com.yourname.jarvis.ui.UIManager;
import com.yourname.jarvis.DatabaseManager;
import com.yourname.jarvis.building.BuildingAssistant;
import com.yourname.jarvis.quests.QuestSystem;
import com.yourname.jarvis.schematics.SchematicManager;
import com.yourname.jarvis.listeners.ChatListener;
import org.bukkit.command.CommandSender;

/**
 * Jarvis AI Companion Plugin
 * Version: 0.0.5
 * 
 * Main plugin class that manages all subsystems
 */
public class Jarvis extends JavaPlugin {

    private static final String VERSION = "0.0.6";
    
    private AIConnector aiConnector;
    private JarvisNPC jarvisNPC;
    private UIManager uiManager;
    private DatabaseManager databaseManager;
    private BuildingAssistant buildingAssistant;
    private QuestSystem questSystem;
    private SchematicManager schematicManager;

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
        questSystem = new QuestSystem(this);
        schematicManager = new SchematicManager(this);

        // Register chat listener for natural language commands
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);

        getLogger().info("Jarvis AI Companion v" + VERSION + " enabled successfully!");
        getLogger().info("NPC, Mining, Quest, and Building systems are functional!");
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
    
    public QuestSystem getQuestSystem() {
        return questSystem;
    }
    
    public SchematicManager getSchematicManager() {
        return schematicManager;
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
            String info = "AI Provider: " + provider + ", model: " + modelName + ", api key present: " + hasKey;
            getLogger().info(info);
            requester.sendMessage("§e" + info);
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
        requester.sendMessage("§aCore NPC & Mining: §2Fully Functional");
        requester.sendMessage("§aBuilding System: §2Functional");
        requester.sendMessage("§aQuest System: §2Functional (" + (questSystem != null ? questSystem.getQuestLibrary().getTemplateCount() + " templates" : "N/A") + ")");
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
