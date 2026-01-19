package com.yourname.jarvis;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

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

    // ==================== NPC INVENTORY PERSISTENCE ====================

    /**
     * Save NPC inventory to database
     */
    public void saveNpcInventory(UUID playerId, ItemStack[] contents) {
        if (contents == null) return;

        String playerIdStr = playerId.toString();
        long savedTime = System.currentTimeMillis();

        try (Connection c = getConnection()) {
            // Clear existing inventory for this player
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM npc_inventory WHERE player_id = ?")) {
                ps.setString(1, playerIdStr);
                ps.executeUpdate();
            }

            // Save each non-null slot
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO npc_inventory (player_id, slot_index, item_type, item_amount, item_data, saved_time) " +
                    "VALUES (?, ?, ?, ?, ?, ?)")) {

                for (int i = 0; i < contents.length; i++) {
                    ItemStack item = contents[i];
                    if (item == null || item.getType() == Material.AIR) continue;

                    ps.setString(1, playerIdStr);
                    ps.setInt(2, i);
                    ps.setString(3, item.getType().name());
                    ps.setInt(4, item.getAmount());
                    ps.setString(5, serializeItemMeta(item));
                    ps.setLong(6, savedTime);
                    ps.addBatch();
                }

                ps.executeBatch();
            }

            plugin.getLogger().fine("Saved NPC inventory for " + playerIdStr);

        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save NPC inventory: " + e.getMessage());
        }
    }

    /**
     * Load NPC inventory from database
     */
    public ItemStack[] loadNpcInventory(UUID playerId) {
        String playerIdStr = playerId.toString();
        ItemStack[] contents = new ItemStack[36]; // Standard inventory size

        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT slot_index, item_type, item_amount, item_data FROM npc_inventory " +
                     "WHERE player_id = ? ORDER BY slot_index")) {

            ps.setString(1, playerIdStr);

            try (ResultSet rs = ps.executeQuery()) {
                boolean hasItems = false;
                while (rs.next()) {
                    hasItems = true;
                    int slot = rs.getInt("slot_index");
                    String itemType = rs.getString("item_type");
                    int amount = rs.getInt("item_amount");
                    String itemData = rs.getString("item_data");

                    if (slot >= 0 && slot < contents.length) {
                        try {
                            Material material = Material.valueOf(itemType);
                            ItemStack item = new ItemStack(material, amount);
                            deserializeItemMeta(item, itemData);
                            contents[slot] = item;
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Invalid material in saved inventory: " + itemType);
                        }
                    }
                }

                if (!hasItems) {
                    return null; // No saved inventory
                }
            }

        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load NPC inventory: " + e.getMessage());
            return null;
        }

        return contents;
    }

    /**
     * Clear saved inventory for a player
     */
    public void clearSavedInventory(UUID playerId) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM npc_inventory WHERE player_id = ?")) {

            ps.setString(1, playerId.toString());
            ps.executeUpdate();

        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to clear saved inventory: " + e.getMessage());
        }
    }

    /**
     * Check if player has saved inventory
     */
    public boolean hasSavedInventory(UUID playerId) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM npc_inventory WHERE player_id = ?")) {

            ps.setString(1, playerId.toString());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }

        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to check saved inventory: " + e.getMessage());
        }
        return false;
    }

    /**
     * Serialize item meta to JSON string for storage
     */
    private String serializeItemMeta(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        StringBuilder sb = new StringBuilder("{");

        // Display name
        if (meta.hasDisplayName()) {
            sb.append("\"name\":\"").append(escapeJson(meta.getDisplayName())).append("\",");
        }

        // Lore
        if (meta.hasLore()) {
            sb.append("\"lore\":[");
            List<String> lore = meta.getLore();
            for (int i = 0; i < lore.size(); i++) {
                sb.append("\"").append(escapeJson(lore.get(i))).append("\"");
                if (i < lore.size() - 1) sb.append(",");
            }
            sb.append("],");
        }

        // Enchantments
        if (!meta.getEnchants().isEmpty()) {
            sb.append("\"enchants\":{");
            var enchants = meta.getEnchants().entrySet().iterator();
            while (enchants.hasNext()) {
                var entry = enchants.next();
                sb.append("\"").append(entry.getKey().getKey().getKey()).append("\":").append(entry.getValue());
                if (enchants.hasNext()) sb.append(",");
            }
            sb.append("},");
        }

        // Remove trailing comma
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * Deserialize item meta from JSON string
     */
    private void deserializeItemMeta(ItemStack item, String data) {
        if (data == null || data.isEmpty() || data.equals("{}")) {
            return;
        }

        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;

            // Simple JSON parsing for our known format
            // Note: This is a basic implementation; consider using a JSON library for complex cases

            // Parse display name
            int nameStart = data.indexOf("\"name\":\"");
            if (nameStart >= 0) {
                nameStart += 8;
                int nameEnd = data.indexOf("\"", nameStart);
                if (nameEnd > nameStart) {
                    meta.setDisplayName(unescapeJson(data.substring(nameStart, nameEnd)));
                }
            }

            // Parse lore
            int loreStart = data.indexOf("\"lore\":[");
            if (loreStart >= 0) {
                loreStart += 8;
                int loreEnd = data.indexOf("]", loreStart);
                if (loreEnd > loreStart) {
                    String loreSection = data.substring(loreStart, loreEnd);
                    List<String> lore = new ArrayList<>();
                    int pos = 0;
                    while (pos < loreSection.length()) {
                        int start = loreSection.indexOf("\"", pos);
                        if (start < 0) break;
                        int end = loreSection.indexOf("\"", start + 1);
                        if (end < 0) break;
                        lore.add(unescapeJson(loreSection.substring(start + 1, end)));
                        pos = end + 1;
                    }
                    if (!lore.isEmpty()) {
                        meta.setLore(lore);
                    }
                }
            }

            // Parse enchantments
            int enchantsStart = data.indexOf("\"enchants\":{");
            if (enchantsStart >= 0) {
                enchantsStart += 12;
                int enchantsEnd = data.indexOf("}", enchantsStart);
                if (enchantsEnd > enchantsStart) {
                    String enchantsSection = data.substring(enchantsStart, enchantsEnd);
                    String[] pairs = enchantsSection.split(",");
                    for (String pair : pairs) {
                        String[] kv = pair.split(":");
                        if (kv.length == 2) {
                            String enchantName = kv[0].replace("\"", "").trim();
                            int level = Integer.parseInt(kv[1].trim());
                            org.bukkit.enchantments.Enchantment enchant =
                                org.bukkit.enchantments.Enchantment.getByKey(
                                    org.bukkit.NamespacedKey.minecraft(enchantName));
                            if (enchant != null) {
                                meta.addEnchant(enchant, level, true);
                            }
                        }
                    }
                }
            }

            item.setItemMeta(meta);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to deserialize item meta: " + e.getMessage());
        }
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }

    private String unescapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\\"", "\"")
                  .replace("\\\\", "\\")
                  .replace("\\n", "\n")
                  .replace("\\r", "\r");
    }

    public void closeDatabases() {
        sources.values().forEach(HikariDataSource::close);
    }
}
