package com.phoenix.fish.manager.database;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
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
                "jdbc:mysql://%s:%d/%s?useSSL=true&requireSSL=false&serverTimezone=UTC&characterEncoding=utf8",
                host, port, name);
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
        addMissingColumns();
        plugin.getLogger().info(plugin.getMessageManager().getPlainMessage("db_connected_mysql"));
    }

    private void createTable() {
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
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        } catch (SQLException e) {
            String msg = plugin.getMessageManager().getPlainMessage("db_failed_create_table");
            plugin.getLogger().severe(msg.replace("%error%", e.getMessage()));
        }
    }

    private void addMissingColumns() {
        String[] intColumns = {
                "skill_points", "skill_fast", "skill_hunter", "skill_double", "skill_bait",
                "crafting_xp", "crafting_level", "total_crafting_xp"
        };
        String[] doubleColumns = { "balance" };

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            for (String col : intColumns) {
                try {
                    stmt.execute("ALTER TABLE player_professions ADD COLUMN " + col + " INT DEFAULT 0;");
                } catch (SQLException ignored) {
                }
            }
            for (String col : doubleColumns) {
                try {
                    stmt.execute("ALTER TABLE player_professions ADD COLUMN " + col + " DOUBLE DEFAULT 0.0;");
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not check/add missing columns to MySQL: " + e.getMessage());
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
            try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int totalXp = rs.getInt("total_xp");
                        int fishingXp = rs.getInt("fishing_xp");
                        int fishingLevel = rs.getInt("fishing_level");

                        String discoveredStr = rs.getString("discovered_fish");
                        HashSet<String> discoveredFish = new HashSet<>();
                        if (discoveredStr != null && !discoveredStr.isEmpty()) {
                            discoveredFish.addAll(Arrays.asList(discoveredStr.split(",")));
                        }

                        int skillPoints = rs.getInt("skill_points");
                        int fast = rs.getInt("skill_fast");
                        int hunter = rs.getInt("skill_hunter");
                        int dbl = rs.getInt("skill_double");
                        int bait = rs.getInt("skill_bait");
                        double balance = rs.getDouble("balance");

                        int craftingXp = rs.getInt("crafting_xp");
                        int craftingLevel = rs.getInt("crafting_level");
                        int totalCraftingXp = rs.getInt("total_crafting_xp");

                        return new FishingData(uuid, fishingXp, fishingLevel, totalXp, discoveredFish,
                                skillPoints, fast, hunter, dbl, bait, balance,
                                craftingXp, craftingLevel, totalCraftingXp);
                    }
                }
            } catch (SQLException e) {
                String msg = plugin.getMessageManager().getPlainMessage("db_failed_load_data");
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
                    "ON DUPLICATE KEY UPDATE " +
                    "total_xp = VALUES(total_xp), " +
                    "fishing_xp = VALUES(fishing_xp), " +
                    "fishing_level = VALUES(fishing_level), " +
                    "discovered_fish = VALUES(discovered_fish), " +
                    "skill_points = VALUES(skill_points), " +
                    "skill_fast = VALUES(skill_fast), " +
                    "skill_hunter = VALUES(skill_hunter), " +
                    "skill_double = VALUES(skill_double), " +
                    "skill_bait = VALUES(skill_bait), " +
                    "balance = VALUES(balance), " +
                    "crafting_xp = VALUES(crafting_xp), " +
                    "crafting_level = VALUES(crafting_level), " +
                    "total_crafting_xp = VALUES(total_crafting_xp);";

            try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
                String msg = plugin.getMessageManager().getPlainMessage("db_failed_save_data");
                plugin.getLogger().severe(msg.replace("%uuid%", uuid.toString()).replace("%error%", e.getMessage()));
            }
        }, dbExecutor);
    }

    @Override
    public CompletableFuture<Void> batchSave(Collection<FishingData> dataCollection) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_professions (uuid, total_xp, fishing_xp, fishing_level, discovered_fish, "
                    +
                    "skill_points, skill_fast, skill_hunter, skill_double, skill_bait, balance, " +
                    "crafting_xp, crafting_level, total_crafting_xp) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "total_xp = VALUES(total_xp), " +
                    "fishing_xp = VALUES(fishing_xp), " +
                    "fishing_level = VALUES(fishing_level), " +
                    "discovered_fish = VALUES(discovered_fish), " +
                    "skill_points = VALUES(skill_points), " +
                    "skill_fast = VALUES(skill_fast), " +
                    "skill_hunter = VALUES(skill_hunter), " +
                    "skill_double = VALUES(skill_double), " +
                    "skill_bait = VALUES(skill_bait), " +
                    "balance = VALUES(balance), " +
                    "crafting_xp = VALUES(crafting_xp), " +
                    "crafting_level = VALUES(crafting_level), " +
                    "total_crafting_xp = VALUES(total_crafting_xp);";

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
                    conn.commit();
                } catch (SQLException batchEx) {
                    conn.rollback();
                    throw batchEx;
                } finally {
                    conn.setAutoCommit(true);
                }
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
            try {
                if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    dbExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                dbExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}