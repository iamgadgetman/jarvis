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
import com.yourname.jarvis.listeners.ChatListener;
import com.yourname.jarvis.building.BuildingAssistant;
import com.yourname.jarvis.building.SchematicManager;
import com.yourname.jarvis.quests.QuestSystem;
import com.yourname.jarvis.DatabaseManager;
import org.bukkit.command.CommandSender;

public class Jarvis extends JavaPlugin {

    private AIConnector aiConnector;
    private JarvisNPC jarvisNPC;
    private UIManager uiManager;
    private DatabaseManager databaseManager;
    private ChatListener chatListener;
    private BuildingAssistant buildingAssistant;
    private SchematicManager schematicManager;
    private QuestSystem questSystem;

    @Override
    public void onEnable() {
        getLogger().info("Jarvis AI Companion enabling...");

        saveDefaultConfig();

        // Initialize AI connector
        aiConnector = new AIConnector(this);
        getLogger().info("AI Connector initialized with provider: " + aiConnector.getProvider());

        // Initialize database
        databaseManager = new DatabaseManager(this);
        databaseManager.initializeDatabaseConnections();

        // Check for Citizens (required for NPC)
        if (getServer().getPluginManager().getPlugin("Citizens") != null) {
            jarvisNPC = new JarvisNPC(this);
            getLogger().info("NPC system initialized.");
        } else {
            getLogger().warning("Citizens not found - NPC features disabled.");
            return;
        }

        // Initialize commands
        getCommand("jarvis").setExecutor(new JarvisCommands(this));

        // Initialize UI manager
        uiManager = new UIManager(this);

        // Initialize natural language processing
        if (getConfig().getBoolean("natural-language.enabled", true)) {
            chatListener = new ChatListener(this);
            getServer().getPluginManager().registerEvents(chatListener, this);
            getLogger().info("Natural language processing enabled.");
        }

        // Initialize building assistant (requires WorldEdit)
        if (getServer().getPluginManager().getPlugin("WorldEdit") != null) {
            buildingAssistant = new BuildingAssistant(this);
            schematicManager = new SchematicManager(this);
            getLogger().info("Building assistant and schematic manager initialized.");
        } else {
            getLogger().warning("WorldEdit not found - building features disabled.");
        }

        // Initialize quest system
        if (getConfig().getBoolean("quests.enabled", true)) {
            questSystem = new QuestSystem(this);
            getLogger().info("Quest system initialized.");
        }

        getLogger().info("Jarvis AI Companion v3.0 enabled successfully!");
        getLogger().info("Features: NLP=" + (chatListener != null) + 
                ", Building=" + (buildingAssistant != null) + 
                ", Quests=" + (questSystem != null));
    }

    @Override
    public void onDisable() {
        if (jarvisNPC != null) {
            jarvisNPC.dismissAll();
        }
        if (databaseManager != null) {
            databaseManager.closeDatabases();
        }
        getLogger().info("Jarvis AI Companion disabled.");
    }

    public void reload() {
        reloadConfig();
        if (aiConnector != null) {
            aiConnector.reloadConfig();
        }
        getLogger().info("Jarvis reloaded!");
    }

    public AIConnector getAIConnector() {
        return aiConnector;
    }

    public JarvisNPC getJarvisNPC() {
        return jarvisNPC;
    }

    public BuildingAssistant getBuildingAssistant() {
        return buildingAssistant;
    }

    public SchematicManager getSchematicManager() {
        return schematicManager;
    }

    public QuestSystem getQuestSystem() {
        return questSystem;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public void printDebug(CommandSender requester) {
        getLogger().info("==== Jarvis Debug Info ====");
        requester.sendMessage("==== Jarvis Debug Info ====");

        if (aiConnector == null) {
            getLogger().warning("AI connector not initialized");
            requester.sendMessage("AI connector not initialized");
        } else {
            String provider = aiConnector.getProvider();
            String modelName = aiConnector.getModel();
            boolean hasKey = aiConnector.hasApiKey();
            getLogger().info("AI Provider: " + provider + ", model: " + modelName + ", api key present: " + hasKey);
            requester.sendMessage("AI Provider: " + provider + ", model: " + modelName + ", api key present: " + hasKey);
        }

        if (jarvisNPC == null) {
            getLogger().warning("NPC system not initialized");
            requester.sendMessage("NPC system not initialized");
        } else {
            getLogger().info("Active NPCs: " + jarvisNPC.getActiveNpcCount());
            getLogger().info("Active tasks: " + jarvisNPC.getActiveTaskCount());
            requester.sendMessage("Active NPCs: " + jarvisNPC.getActiveNpcCount());
            requester.sendMessage("Active tasks: " + jarvisNPC.getActiveTaskCount());
        }

        if (databaseManager == null) {
            getLogger().warning("Database manager not initialized");
            requester.sendMessage("Database manager not initialized");
        } else {
            getLogger().info("Database connections initialized");
            requester.sendMessage("Database connections initialized");
        }

        // New features debug info
        requester.sendMessage("Natural Language: " + (chatListener != null ? "enabled" : "disabled"));
        requester.sendMessage("Building Assistant: " + (buildingAssistant != null ? "enabled" : "disabled"));
        requester.sendMessage("Schematic Manager: " + (schematicManager != null ? "enabled (" + schematicManager.getSchematics().size() + " schematics)" : "disabled"));
        requester.sendMessage("Quest System: " + (questSystem != null ? "enabled" : "disabled"));
    }

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
