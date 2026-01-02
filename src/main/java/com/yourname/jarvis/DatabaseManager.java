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

            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "CREATE TABLE IF NOT EXISTS player_stats (player_id VARCHAR(36) PRIMARY KEY, playtime BIGINT, last_join BIGINT)")) {
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("DB init error: " + e.getMessage());
            }
        }
    }

    public void closeDatabases() {
        sources.values().forEach(HikariDataSource::close);
    }
}
