package com.phoenix.fish.manager.database;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MySQLManager implements IDatabase {

    private final PhoenixFish plugin;
    private HikariDataSource dataSource;
    private ExecutorService dbExecutor;

    public MySQLManager(PhoenixFish plugin) {
        this.plugin = plugin;
    }

    @Override
    public void init() {
        HikariConfig config = new HikariConfig();
        String host = plugin.getConfig().getString("xp-system.database.host", "localhost");
        int port = plugin.getConfig().getInt("xp-system.database.port", 3306);
        String name = plugin.getConfig().getString("xp-system.database.name", "phoenix_db");
        String user = plugin.getConfig().getString("xp-system.database.username", "root");
        String pass = plugin.getConfig().getString("xp-system.database.password", "");

        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=true&requireSSL=false&serverTimezone=UTC&characterEncoding=utf8", host,
                port, name);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(pass);

        config.setMaximumPoolSize(10);
        config.setPoolName("PhoenixFish-Pool");
        config.setConnectionTimeout(10000);
        config.setMaxLifetime(1800000);
        config.setLeakDetectionThreshold(5000);

        this.dataSource = new HikariDataSource(config);

        this.dbExecutor = Executors.newFixedThreadPool(10, r -> {
            Thread t = new Thread(r, "PhoenixFish-MySQL-Worker");
            t.setDaemon(true);
            return t;
        });

        createTable();
        plugin.getLogger().info(plugin.getMessageManager().getPlainMessage("db_connected_mysql"));
    }

    private void createTable() {
        // Added discovered_fish column
        String sql = "CREATE TABLE IF NOT EXISTS player_professions (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "total_xp BIGINT DEFAULT 0, " +
                "fishing_xp INT DEFAULT 0, " +
                "fishing_level INT DEFAULT 1, " +
                "discovered_fish TEXT" +
                ");";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        } catch (SQLException e) {
            String msg = plugin.getMessageManager().getPlainMessage("db_failed_create_table");
            plugin.getLogger().severe(msg.replace("%error%", e.getMessage()));
        }
    }

    @Override
    public CompletableFuture<FishingData> loadData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT fishing_xp, fishing_level, total_xp, discovered_fish FROM player_professions WHERE uuid = ?;";
            try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int totalXp = rs.getInt("total_xp");
                        int fishingXp = rs.getInt("fishing_xp");
                        int fishingLevel = rs.getInt("fishing_level");

                        // Parse discovered fish
                        String discoveredStr = rs.getString("discovered_fish");
                        HashSet<String> discoveredFish = new HashSet<>();
                        if (discoveredStr != null && !discoveredStr.isEmpty()) {
                            discoveredFish.addAll(Arrays.asList(discoveredStr.split(",")));
                        }

                        return new FishingData(uuid, fishingXp, fishingLevel, totalXp, discoveredFish);
                    }
                }
            } catch (SQLException e) {
                String msg = plugin.getMessageManager().getPlainMessage("db_failed_load_data");
                plugin.getLogger().severe(msg.replace("%uuid%", uuid.toString()).replace("%error%", e.getMessage()));
            }
            return new FishingData(uuid, 0, 1, 0, new HashSet<>());
        }, dbExecutor);
    }

    @Override
    public CompletableFuture<Void> saveData(UUID uuid, FishingData data) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_professions (uuid, total_xp, fishing_xp, fishing_level, discovered_fish) VALUES (?, ?, ?, ?, ?) "
                    +
                    "ON DUPLICATE KEY UPDATE " +
                    "total_xp = VALUES(total_xp), " +
                    "fishing_xp = VALUES(fishing_xp), " +
                    "fishing_level = VALUES(fishing_level), " +
                    "discovered_fish = VALUES(discovered_fish);";

            try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, data.getTotalXp());
                ps.setInt(3, data.getCurrentXp());
                ps.setInt(4, data.getCurrentLevel());
                ps.setString(5, String.join(",", data.getDiscoveredFish()));
                ps.executeUpdate();
            } catch (SQLException e) {
                String msg = plugin.getMessageManager().getPlainMessage("db_failed_save_data");
                plugin.getLogger().severe(msg.replace("%uuid%", uuid.toString()).replace("%error%", e.getMessage()));
            }
        }, dbExecutor);
    }

    @Override
    public CompletableFuture<Void> batchSave(Collection<FishingData> dataCollection) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_professions (uuid, total_xp, fishing_xp, fishing_level, discovered_fish) VALUES (?, ?, ?, ?, ?) "
                    +
                    "ON DUPLICATE KEY UPDATE " +
                    "total_xp = VALUES(total_xp), " +
                    "fishing_xp = VALUES(fishing_xp), " +
                    "fishing_level = VALUES(fishing_level), " +
                    "discovered_fish = VALUES(discovered_fish);";

            try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                conn.setAutoCommit(false);
                for (FishingData data : dataCollection) {
                    ps.setString(1, data.getUuid().toString());
                    ps.setInt(2, data.getTotalXp());
                    ps.setInt(3, data.getCurrentXp());
                    ps.setInt(4, data.getCurrentLevel());
                    ps.setString(5, String.join(",", data.getDiscoveredFish()));
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                String msg = plugin.getMessageManager().getPlainMessage("db_failed_batch_save");
                plugin.getLogger().severe(msg.replace("%error%", e.getMessage()));
            }
        }, dbExecutor);
    }

    @Override
    public void close() {
        if (dbExecutor != null) {
            dbExecutor.shutdown();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}