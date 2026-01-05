package com.yourname.jarvis;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class DatabaseManager {

    private final Jarvis plugin;
    private final Map<String, HikariDataSource> sources = new HashMap<>();

    public DatabaseManager(Jarvis plugin) {
        this.plugin = plugin;
    }

    public void initializeDatabaseConnections() {
        File file = new File(plugin.getDataFolder(), plugin.getConfig().getString("databases.file", "databases.yml"));
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        for (String name : cfg.getKeys(false)) {
            HikariConfig hc = new HikariConfig();
            hc.setDriverClassName(cfg.getString(name + ".driver"));
            hc.setJdbcUrl(cfg.getString(name + ".url"));
            hc.setUsername(cfg.getString(name + ".username"));
            hc.setPassword(cfg.getString(name + ".password"));
            HikariDataSource ds = new HikariDataSource(hc);
            sources.put(name, ds);

            try (Connection c = ds.getConnection()) {
                initializeTables(c);
            } catch (SQLException e) {
                plugin.getLogger().warning("DB init error: " + e.getMessage());
            }
        }
    }

    private void initializeTables(Connection c) throws SQLException {
        // Player stats table
        try (PreparedStatement ps = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS player_stats (" +
                "player_id VARCHAR(36) PRIMARY KEY, " +
                "playtime BIGINT, " +
                "last_join BIGINT)")) {
            ps.executeUpdate();
        }

        // Quests table
        try (PreparedStatement ps = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS player_quests (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "player_id VARCHAR(36) NOT NULL, " +
                "quest_id VARCHAR(36) NOT NULL, " +
                "quest_data TEXT NOT NULL, " +
                "progress TEXT, " +
                "assigned_time BIGINT NOT NULL, " +
                "completed BOOLEAN DEFAULT 0, " +
                "completed_time BIGINT)")) {
            ps.executeUpdate();
        }

        // Quest progress tracking
        try (PreparedStatement ps = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS quest_objectives (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "player_id VARCHAR(36) NOT NULL, " +
                "quest_id VARCHAR(36) NOT NULL, " +
                "objective_type VARCHAR(50) NOT NULL, " +
                "objective_target VARCHAR(100) NOT NULL, " +
                "required_amount INTEGER NOT NULL, " +
                "current_amount INTEGER DEFAULT 0)")) {
            ps.executeUpdate();
        }

        // Build history
        try (PreparedStatement ps = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS build_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "player_id VARCHAR(36) NOT NULL, " +
                "description TEXT NOT NULL, " +
                "blocks_placed INTEGER NOT NULL, " +
                "timestamp BIGINT NOT NULL, " +
                "world VARCHAR(100), " +
                "x INTEGER, " +
                "y INTEGER, " +
                "z INTEGER)")) {
            ps.executeUpdate();
        }

        // Chat interactions (for AI learning)
        try (PreparedStatement ps = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS chat_interactions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "player_id VARCHAR(36) NOT NULL, " +
                "player_message TEXT NOT NULL, " +
                "ai_response TEXT, " +
                "action_taken VARCHAR(50), " +
                "timestamp BIGINT NOT NULL)")) {
            ps.executeUpdate();
        }

        plugin.getLogger().info("Database tables initialized successfully");
    }

    /**
     * Get a connection from the default data source
     */
    public Connection getConnection() throws SQLException {
        return getConnection("sqlite");
    }

    /**
     * Get a connection from a specific data source
     */
    public Connection getConnection(String name) throws SQLException {
        HikariDataSource ds = sources.get(name);
        if (ds == null) {
            throw new SQLException("No data source found with name: " + name);
        }
        return ds.getConnection();
    }

    /**
     * Save quest to database
     */
    public void saveQuest(String playerId, String questId, String questData, String progress) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO player_quests " +
                     "(player_id, quest_id, quest_data, progress, assigned_time) " +
                     "VALUES (?, ?, ?, ?, ?)")) {
            
            ps.setString(1, playerId);
            ps.setString(2, questId);
            ps.setString(3, questData);
            ps.setString(4, progress);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save quest: " + e.getMessage());
        }
    }

    /**
     * Update quest progress
     */
    public void updateQuestProgress(String playerId, String questId, String progress) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE player_quests SET progress = ? WHERE player_id = ? AND quest_id = ?")) {
            
            ps.setString(1, progress);
            ps.setString(2, playerId);
            ps.setString(3, questId);
            ps.executeUpdate();
            
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to update quest progress: " + e.getMessage());
        }
    }

    /**
     * Mark quest as completed
     */
    public void completeQuest(String playerId, String questId) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE player_quests SET completed = 1, completed_time = ? " +
                     "WHERE player_id = ? AND quest_id = ?")) {
            
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, playerId);
            ps.setString(3, questId);
            ps.executeUpdate();
            
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to complete quest: " + e.getMessage());
        }
    }

    /**
     * Delete quest
     */
    public void deleteQuest(String playerId, String questId) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM player_quests WHERE player_id = ? AND quest_id = ?")) {
            
            ps.setString(1, playerId);
            ps.setString(2, questId);
            ps.executeUpdate();
            
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to delete quest: " + e.getMessage());
        }
    }

    /**
     * Save build history
     */
    public void saveBuildHistory(String playerId, String description, int blocksPlaced, 
                                  String world, int x, int y, int z) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO build_history " +
                     "(player_id, description, blocks_placed, timestamp, world, x, y, z) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            
            ps.setString(1, playerId);
            ps.setString(2, description);
            ps.setInt(3, blocksPlaced);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, world);
            ps.setInt(6, x);
            ps.setInt(7, y);
            ps.setInt(8, z);
            ps.executeUpdate();
            
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save build history: " + e.getMessage());
        }
    }

    /**
     * Log chat interaction
     */
    public void logChatInteraction(String playerId, String playerMessage, 
                                    String aiResponse, String actionTaken) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO chat_interactions " +
                     "(player_id, player_message, ai_response, action_taken, timestamp) " +
                     "VALUES (?, ?, ?, ?, ?)")) {
            
            ps.setString(1, playerId);
            ps.setString(2, playerMessage);
            ps.setString(3, aiResponse);
            ps.setString(4, actionTaken);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to log chat interaction: " + e.getMessage());
        }
    }

    public void closeDatabases() {
        sources.values().forEach(HikariDataSource::close);
    }
}
