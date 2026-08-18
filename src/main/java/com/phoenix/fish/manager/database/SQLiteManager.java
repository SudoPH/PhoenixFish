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
import java.util.concurrent.TimeUnit;

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
            addMissingColumns();

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
        String sql = "CREATE TABLE IF NOT EXISTS player_professions (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "total_xp BIGINT DEFAULT 0, " +
                "fishing_xp INT DEFAULT 0, " +
                "fishing_level INT DEFAULT 1, " +
                "discovered_fish TEXT DEFAULT '', " +
                "skill_points INT DEFAULT 0, " +
                "skill_fast INT DEFAULT 0, " +
                "skill_hunter INT DEFAULT 0, " +
                "skill_double INT DEFAULT 0, " +
                "skill_bait INT DEFAULT 0, " +
                "balance DOUBLE DEFAULT 0.0, " +
                "crafting_xp INT DEFAULT 0, " +
                "crafting_level INT DEFAULT 1, " +
                "total_crafting_xp INT DEFAULT 0" +
                ");";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private void addMissingColumns() {
        String[] columns = {
                "skill_points", "skill_fast", "skill_hunter", "skill_double", "skill_bait",
                "balance",
                "crafting_xp", "crafting_level", "total_crafting_xp"
        };
        for (String col : columns) {
            try (Statement stmt = connection.createStatement()) {
                String sql = "ALTER TABLE player_professions ADD COLUMN " + col
                        + (col.equals("balance") ? " DOUBLE DEFAULT 0.0;" : " INT DEFAULT 0;");
                stmt.execute(sql);
            } catch (SQLException ignored) {
                // Sütun zaten varsa hata alırız, önemli değil
            }
        }
    }

    @SuppressWarnings("null")
    @Override
    public CompletableFuture<FishingData> loadData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT fishing_xp, fishing_level, total_xp, discovered_fish, " +
                    "skill_points, skill_fast, skill_hunter, skill_double, skill_bait, balance, " +
                    "crafting_xp, crafting_level, total_crafting_xp " +
                    "FROM player_professions WHERE uuid = ?;";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        HashSet<String> discoveredFish = new HashSet<>();
                        String discoveredStr = rs.getString("discovered_fish");
                        if (discoveredStr != null && !discoveredStr.isEmpty()) {
                            discoveredFish.addAll(Arrays.asList(discoveredStr.split(",")));
                        }

                        return new FishingData(uuid,
                                rs.getInt("fishing_xp"),
                                rs.getInt("fishing_level"),
                                rs.getInt("total_xp"),
                                discoveredFish,
                                rs.getInt("skill_points"),
                                rs.getInt("skill_fast"),
                                rs.getInt("skill_hunter"),
                                rs.getInt("skill_double"),
                                rs.getInt("skill_bait"),
                                rs.getDouble("balance"),
                                rs.getInt("crafting_xp"),
                                rs.getInt("crafting_level"),
                                rs.getInt("total_crafting_xp"));
                    }
                }
            } catch (SQLException e) {
                String msg = plugin.getMessageManager().getPlainMessage("db_failed_load_data_sqlite");
                plugin.getLogger().severe(msg.replace("%uuid%", uuid.toString()).replace("%error%", e.getMessage()));
            }
            return new FishingData(uuid, 0, 1, 0, new HashSet<>(), 0, 0, 0, 0, 0, 0.0, 0, 1, 0);
        }, dbExecutor);
    }

    @Override
    public CompletableFuture<Void> saveData(UUID uuid, FishingData data) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_professions (uuid, total_xp, fishing_xp, fishing_level, discovered_fish, "
                    +
                    "skill_points, skill_fast, skill_hunter, skill_double, skill_bait, balance, " +
                    "crafting_xp, crafting_level, total_crafting_xp) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET " +
                    "total_xp = EXCLUDED.total_xp, " +
                    "fishing_xp = EXCLUDED.fishing_xp, " +
                    "fishing_level = EXCLUDED.fishing_level, " +
                    "discovered_fish = EXCLUDED.discovered_fish, " +
                    "skill_points = EXCLUDED.skill_points, " +
                    "skill_fast = EXCLUDED.skill_fast, " +
                    "skill_hunter = EXCLUDED.skill_hunter, " +
                    "skill_double = EXCLUDED.skill_double, " +
                    "skill_bait = EXCLUDED.skill_bait, " +
                    "balance = EXCLUDED.balance, " +
                    "crafting_xp = EXCLUDED.crafting_xp, " +
                    "crafting_level = EXCLUDED.crafting_level, " +
                    "total_crafting_xp = EXCLUDED.total_crafting_xp;";

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, data.getTotalXp());
                ps.setInt(3, data.getCurrentXp());
                ps.setInt(4, data.getCurrentLevel());
                ps.setString(5, String.join(",", data.getDiscoveredFish()));
                ps.setInt(6, data.getSkillPoints());
                ps.setInt(7, data.getFastCatcher());
                ps.setInt(8, data.getMasterHunter());
                ps.setInt(9, data.getDoubleCatch());
                ps.setInt(10, data.getLuckyBait());
                ps.setDouble(11, data.getBalance());
                ps.setInt(12, data.getCraftingXp());
                ps.setInt(13, data.getCraftingLevel());
                ps.setInt(14, data.getTotalCraftingXp());
                ps.executeUpdate();
            } catch (SQLException e) {
                String msg = plugin.getMessageManager().getPlainMessage("db_failed_save_data_sqlite");
                plugin.getLogger().severe(msg.replace("%uuid%", uuid.toString()).replace("%error%", e.getMessage()));
            }
        }, dbExecutor);
    }

    @Override
    public CompletableFuture<Void> batchSave(Collection<FishingData> dataCollection) {
        return CompletableFuture.runAsync(() -> {
            if (dataCollection == null || dataCollection.isEmpty())
                return;

            String sql = "INSERT INTO player_professions (uuid, total_xp, fishing_xp, fishing_level, discovered_fish, "
                    +
                    "skill_points, skill_fast, skill_hunter, skill_double, skill_bait, balance, " +
                    "crafting_xp, crafting_level, total_crafting_xp) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET " +
                    "total_xp = EXCLUDED.total_xp, " +
                    "fishing_xp = EXCLUDED.fishing_xp, " +
                    "fishing_level = EXCLUDED.fishing_level, " +
                    "discovered_fish = EXCLUDED.discovered_fish, " +
                    "skill_points = EXCLUDED.skill_points, " +
                    "skill_fast = EXCLUDED.skill_fast, " +
                    "skill_hunter = EXCLUDED.skill_hunter, " +
                    "skill_double = EXCLUDED.skill_double, " +
                    "skill_bait = EXCLUDED.skill_bait, " +
                    "balance = EXCLUDED.balance, " +
                    "crafting_xp = EXCLUDED.crafting_xp, " +
                    "crafting_level = EXCLUDED.crafting_level, " +
                    "total_crafting_xp = EXCLUDED.total_crafting_xp;";

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                connection.setAutoCommit(false);
                for (FishingData data : dataCollection) {
                    if (data == null || data.getUuid() == null)
                        continue;
                    ps.setString(1, data.getUuid().toString());
                    ps.setInt(2, data.getTotalXp());
                    ps.setInt(3, data.getCurrentXp());
                    ps.setInt(4, data.getCurrentLevel());
                    ps.setString(5, String.join(",", data.getDiscoveredFish()));
                    ps.setInt(6, data.getSkillPoints());
                    ps.setInt(7, data.getFastCatcher());
                    ps.setInt(8, data.getMasterHunter());
                    ps.setInt(9, data.getDoubleCatch());
                    ps.setInt(10, data.getLuckyBait());
                    ps.setDouble(11, data.getBalance());
                    ps.setInt(12, data.getCraftingXp());
                    ps.setInt(13, data.getCraftingLevel());
                    ps.setInt(14, data.getTotalCraftingXp());
                    ps.addBatch();
                }
                ps.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                String msg = plugin.getMessageManager().getPlainMessage("db_failed_batch_save_sqlite");
                plugin.getLogger().severe(msg.replace("%error%", e.getMessage()));
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        }, dbExecutor);
    }

    @Override
    public void close() {
        if (dbExecutor != null) {
            dbExecutor.shutdown();
            try {
                if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    dbExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                dbExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe(plugin.getMessageManager().getPlainMessage("db_failed_close_sqlite")
                    .replace("%error%", e.getMessage()));
        }
    }
}