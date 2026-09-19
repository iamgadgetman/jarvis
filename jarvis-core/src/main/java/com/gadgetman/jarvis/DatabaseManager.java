package com.gadgetman.jarvis;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.gadgetman.jarvis.core.config.YamlConfig;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class DatabaseManager {

    private final Config config;
    private final Log log;
    private final Path dataDir;
    private final Map<String, HikariDataSource> sources = new HashMap<>();

    public DatabaseManager(Config config, Log log, Path dataDir) {
        this.config = config;
        this.log = log;
        this.dataDir = dataDir;
    }

    public void initializeDatabaseConnections() {
        Path file = dataDir.resolve(config.getString("databases.file", "databases.yml"));
        Config cfg;
        try {
            cfg = YamlConfig.load(file);
        } catch (IOException e) {
            log.warn("Could not read " + file + ": " + e.getMessage());
            cfg = YamlConfig.empty();
        }

        for (String name : cfg.keys()) {
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
                log.warn("DB init error: " + e.getMessage());
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

        // NPC inventory persistence
        try (PreparedStatement ps = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS npc_inventory (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "player_id VARCHAR(36) NOT NULL, " +
                "slot_index INTEGER NOT NULL, " +
                "item_type VARCHAR(100) NOT NULL, " +
                "item_amount INTEGER NOT NULL, " +
                "item_data TEXT, " +
                "saved_time BIGINT NOT NULL, " +
                "UNIQUE(player_id, slot_index))")) {
            ps.executeUpdate();
        }

        // Experience memory (v0.9.0) — see com.gadgetman.jarvis.memory
        try (PreparedStatement ps = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS build_experiences (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "player_id VARCHAR(36), " +
                "task_type VARCHAR(50) NOT NULL, " +
                "request_text TEXT NOT NULL, " +
                "situation TEXT, " +
                "plan TEXT, " +
                "outcome VARCHAR(20) NOT NULL, " +
                "outcome_signal REAL NOT NULL, " +
                "provider VARCHAR(50), " +
                "embedding TEXT, " +
                "created_at BIGINT NOT NULL)")) {
            ps.executeUpdate();
        }

        // Retrieval reads positives newest-first and undo looks up one player's
        // recent successes; both are covered here.
        try (PreparedStatement ps = c.prepareStatement(
                "CREATE INDEX IF NOT EXISTS idx_experiences_outcome " +
                "ON build_experiences (outcome, created_at)")) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "CREATE INDEX IF NOT EXISTS idx_experiences_player " +
                "ON build_experiences (player_id, created_at)")) {
            ps.executeUpdate();
        }

        // Decomposed build requests (v0.11.0) — see schematics.RequestDecomposer.
        // Keyed on the normalised request text, so a repeat request never pays
        // for the model call twice.
        try (PreparedStatement ps = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS request_features (" +
                "request_key VARCHAR(255) PRIMARY KEY, " +
                "tags TEXT NOT NULL, " +
                "created_at BIGINT NOT NULL)")) {
            ps.executeUpdate();
        }

        log.info("Database tables initialized successfully");
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
            log.warn("Failed to save build history: " + e.getMessage());
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
            log.warn("Failed to log chat interaction: " + e.getMessage());
        }
    }

    /**
     * Fetch the most recent chat exchanges for a player (oldest first).
     * Each entry: [player_message, ai_response, action_taken]
     */
    public java.util.List<String[]> getRecentInteractions(String playerId, int limit) {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_message, ai_response, action_taken FROM chat_interactions " +
                     "WHERE player_id = ? ORDER BY timestamp DESC LIMIT ?")) {
            ps.setString(1, playerId);
            ps.setInt(2, limit);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[]{
                            rs.getString(1), rs.getString(2), rs.getString(3)});
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to read chat history: " + e.getMessage());
        }
        java.util.Collections.reverse(rows); // oldest first
        return rows;
    }

    // ==================== NPC INVENTORY PERSISTENCE ====================

    public void closeDatabases() {
        sources.values().forEach(HikariDataSource::close);
    }
}
