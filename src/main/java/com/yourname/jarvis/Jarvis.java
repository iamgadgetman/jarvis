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
import org.bukkit.command.CommandSender;

public class Jarvis extends JavaPlugin {

    private AIConnector aiConnector;
    private JarvisNPC jarvisNPC;
    private UIManager uiManager;
    private DatabaseManager databaseManager;

    @Override
    public void onEnable() {
        getLogger().info("Jarvis AI Companion enabling...");

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

        getLogger().info("Jarvis AI Companion v2.7 enabled.");
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
