package com.phoenix.fish.manager.database;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SQLiteManager implements IDatabase {

    private final PhoenixFish plugin;
    private Connection connection;
    private ExecutorService dbExecutor;

    public SQLiteManager(PhoenixFish plugin) {
        this.plugin = plugin;
    }

    @Override
    public void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(plugin.getDataFolder(), "phoenix_fish.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            this.connection = DriverManager.getConnection(url);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
            }

            createTable();

            this.dbExecutor = Executors.newFixedThreadPool(1, r -> {
                Thread t = new Thread(r, "PhoenixFish-SQLite-Worker");
                t.setDaemon(true);
                return t;
            });

            plugin.getLogger().info(plugin.getMessageManager().getPlainMessage("db_connected_sqlite"));
        } catch (Exception e) {
            String msg = plugin.getMessageManager().getPlainMessage("db_failed_connect_sqlite");
            plugin.getLogger().severe(msg.replace("%error%", e.getMessage()));
        }
    }

    private void createTable() throws SQLException {
        // Added discovered_fish column
        String sql = "CREATE TABLE IF NOT EXISTS player_professions (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "total_xp BIGINT DEFAULT 0, " +
                "fishing_xp INT DEFAULT 0, " +
                "fishing_level INT DEFAULT 1, " +
                "discovered_fish TEXT DEFAULT ''" +
                ");";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.execute();
        }
    }

    @Override
    public CompletableFuture<FishingData> loadData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT fishing_xp, fishing_level, total_xp, discovered_fish FROM player_professions WHERE uuid = ?;";
            synchronized (this) {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
                    String msg = plugin.getMessageManager().getPlainMessage("db_failed_load_data_sqlite");
                    plugin.getLogger()
                            .severe(msg.replace("%uuid%", uuid.toString()).replace("%error%", e.getMessage()));
                }
                return new FishingData(uuid, 0, 1, 0, new HashSet<>());
            }
        }, dbExecutor);
    }

    @Override
    public CompletableFuture<Void> saveData(UUID uuid, FishingData data) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_professions (uuid, total_xp, fishing_xp, fishing_level, discovered_fish) VALUES (?, ?, ?, ?, ?) "
                    +
                    "ON CONFLICT(uuid) DO UPDATE SET " +
                    "total_xp = EXCLUDED.total_xp, " +
                    "fishing_xp = EXCLUDED.fishing_xp, " +
                    "fishing_level = EXCLUDED.fishing_level, " +
                    "discovered_fish = EXCLUDED.discovered_fish;";
            synchronized (this) {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, uuid.toString());
                    ps.setInt(2, data.getTotalXp());
                    ps.setInt(3, data.getCurrentXp());
                    ps.setInt(4, data.getCurrentLevel());
                    ps.setString(5, String.join(",", data.getDiscoveredFish())); // Convert Set to String
                    ps.executeUpdate();
                } catch (SQLException e) {
                    String msg = plugin.getMessageManager().getPlainMessage("db_failed_save_data_sqlite");
                    plugin.getLogger()
                            .severe(msg.replace("%uuid%", uuid.toString()).replace("%error%", e.getMessage()));
                }
            }
        }, dbExecutor);
    }

    @Override
    public CompletableFuture<Void> batchSave(Collection<FishingData> dataCollection) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_professions (uuid, total_xp, fishing_xp, fishing_level, discovered_fish) VALUES (?, ?, ?, ?, ?) "
                    +
                    "ON CONFLICT(uuid) DO UPDATE SET " +
                    "total_xp = EXCLUDED.total_xp, " +
                    "fishing_xp = EXCLUDED.fishing_xp, " +
                    "fishing_level = EXCLUDED.fishing_level, " +
                    "discovered_fish = EXCLUDED.discovered_fish;";
            synchronized (this) {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    connection.setAutoCommit(false);
                    for (FishingData data : dataCollection) {
                        ps.setString(1, data.getUuid().toString());
                        ps.setInt(2, data.getTotalXp());
                        ps.setInt(3, data.getCurrentXp());
                        ps.setInt(4, data.getCurrentLevel());
                        ps.setString(5, String.join(",", data.getDiscoveredFish()));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    connection.commit();
                    connection.setAutoCommit(true);
                } catch (SQLException e) {
                    String msg = plugin.getMessageManager().getPlainMessage("db_failed_batch_save_sqlite");
                    plugin.getLogger().severe(msg.replace("%error%", e.getMessage()));
                }
            }
        }, dbExecutor);
    }

    @Override
    public void close() {
        if (dbExecutor != null) {
            dbExecutor.shutdown();
        }
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            String msg = plugin.getMessageManager().getPlainMessage("db_failed_close_sqlite");
            plugin.getLogger().severe(msg.replace("%error%", e.getMessage()));
        }
    }
}