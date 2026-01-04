package com.yourname.jarvis;

import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import com.yourname.jarvis.ai.AIConnector;
import com.yourname.jarvis.npc.JarvisNPC;
import com.yourname.jarvis.commands.JarvisCommands;
import com.yourname.jarvis.ui.UIManager;
import com.yourname.jarvis.DatabaseManager;
import com.yourname.jarvis.util.DebugLogger;

public class Jarvis extends JavaPlugin {

    private AIConnector aiConnector;
    private JarvisNPC jarvisNPC;
    private UIManager uiManager;
    private DatabaseManager databaseManager;
    private DebugLogger debugLogger;

    @Override
    public void onEnable() {
        getLogger().info("Jarvis AI Companion enabling...");

        saveDefaultConfig();
        reloadConfig();
        getLogger().info("Config loaded from: " + getDataFolder().getAbsolutePath() + File.separator + "config.yml");

        debugLogger = new DebugLogger(this);
        getLogger().info("Debug logging is " + (debugLogger.isEnabled() ? "ENABLED" : "disabled") + " (toggle with /jarvis debug on|off)");

        aiConnector = new AIConnector(this);
        getLogger().info("AI provider: " + aiConnector.getProviderName() + " (model=" + aiConnector.getModelName() + ", key set=" + aiConnector.isApiKeyPresent() + ")");

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
        if (debugLogger != null) {
            debugLogger.reload();
        }
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

    public DebugLogger getDebugLogger() {
        return debugLogger;
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
