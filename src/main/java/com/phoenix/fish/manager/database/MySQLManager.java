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
import java.util.concurrent.CompletionException;
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

        String jdbcUrl = buildJdbcUrl(host, port, name);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(pass);

        config.setMaximumPoolSize(10);
        config.setPoolName("PhoenixFish-Pool");
        config.setConnectionTimeout(10000);
        config.setMaxLifetime(1800000);
        config.setLeakDetectionThreshold(5000);

        try {
            this.dataSource = new HikariDataSource(config);
        } catch (RuntimeException e) {
            String msg = plugin.getMessageManager().getPlainMessage("db_failed_connect_mysql");
            plugin.getLogger().severe(msg.replace("%error%", e.getMessage()));
            throw new IllegalStateException("MySQL HikariCP init failed", e);
        }

        this.dbExecutor = Executors.newFixedThreadPool(10, r -> {
            Thread t = new Thread(r, "PhoenixFish-MySQL-Worker");
            t.setDaemon(true);
            return t;
        });

        // H-4 FIX: throw on critical init errors so plugin enable fails fast instead of starting "active" with a broken DB.
        try {
            createTable();
            addMissingColumns();
        } catch (SQLException e) {
            String msg = plugin.getMessageManager().getPlainMessage("db_failed_create_table");
            plugin.getLogger().severe(msg.replace("%error%", e.getMessage()));
            try {
                this.dataSource.close();
            } catch (RuntimeException ignored) {
            }
            throw new IllegalStateException("MySQL schema init failed", e);
        }

        plugin.getLogger().info(plugin.getMessageManager().getPlainMessage("db_connected_mysql"));
    }

    private String buildJdbcUrl(String host, int port, String name) {
        boolean useSSL = plugin.getConfig().getBoolean("xp-system.database.use-ssl", true);
        boolean verifyCert = plugin.getConfig().getBoolean("xp-system.database.verify-server-certificate", true);

        StringBuilder url = new StringBuilder();
        url.append("jdbc:mysql://").append(host).append(":").append(port).append("/").append(name);
        url.append("?serverTimezone=UTC&characterEncoding=utf8");

        if (useSSL) {
            url.append("&useSSL=true&requireSSL=true");
            if (verifyCert) {
                url.append("&verifyServerCertificate=true");
            } else {
                url.append("&verifyServerCertificate=false");
                url.append("&allowPublicKeyRetrieval=true");
            }
        } else {
            url.append("&useSSL=false");
            url.append("&allowPublicKeyRetrieval=true");
        }

        String mode;
        if (useSSL && verifyCert)
            mode = "production (SSL + cert verify)";
        else if (useSSL)
            mode = "self-signed dev (SSL, no cert verify)";
        else
            mode = "plaintext dev (NO SSL)";
        plugin.getLogger().info("MySQL connection mode: " + mode);
        plugin.getLogger().info("MySQL JDBC URL: " + url);

        return url.toString();
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
                "total_crafting_xp INT DEFAULT 0, " +
                // v1.6.0 — new skill columns
                "skill_big INT DEFAULT 0, " +
                "skill_lucky INT DEFAULT 0, " +
                "skill_stamina INT DEFAULT 0, " +
                // v1.6.0 — new stat columns
                "total_catches INT DEFAULT 0, " +
                "biggest_weight DOUBLE DEFAULT 0.0, " +
                "biggest_fish_name TEXT DEFAULT NULL, " +
                "rarest_catch_rarity INT DEFAULT 0, " +
                "rarest_catch_name TEXT DEFAULT NULL, " +
                "total_earnings DOUBLE DEFAULT 0.0, " +
                "current_streak INT DEFAULT 0, " +
                "best_streak INT DEFAULT 0, " +
                "mutation_count INT DEFAULT 0" +
                ");";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private void addMissingColumns() throws SQLException {
        // v1.6.0 — Refactored to handle three column types cleanly.
        String[] intColumns = {
                "skill_points", "skill_fast", "skill_hunter", "skill_double", "skill_bait",
                "crafting_xp", "crafting_level", "total_crafting_xp",
                // v1.6.0 — new int skill + stat columns
                "skill_big", "skill_lucky", "skill_stamina",
                "total_catches", "rarest_catch_rarity",
                "current_streak", "best_streak", "mutation_count"
        };
        String[] doubleColumns = {
                "balance",
                // v1.6.0 — new double stat columns
                "biggest_weight", "total_earnings"
        };
        String[] textColumns = {
                // v1.6.0 — new text stat columns
                "biggest_fish_name", "rarest_catch_name"
        };

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            for (String col : intColumns) {
                addColumnIfMissing(stmt, col, "INT DEFAULT 0");
            }
            for (String col : doubleColumns) {
                addColumnIfMissing(stmt, col, "DOUBLE DEFAULT 0.0");
            }
            for (String col : textColumns) {
                addColumnIfMissing(stmt, col, "TEXT DEFAULT NULL");
            }
        }
    }

    private void addColumnIfMissing(Statement stmt, String col, String type) throws SQLException {
        String sql = "ALTER TABLE player_professions ADD COLUMN " + col + " " + type + ";";
        try {
            stmt.execute(sql);
            plugin.getLogger().info("MySQL migration: added column '" + col + "' to player_professions.");
        } catch (SQLException e) {
            if (isColumnAlreadyExistsError(e)) {
                // expected — silent
                return;
            }
            plugin.getLogger().warning(
                    "MySQL: could not add column '" + col + "' to player_professions: "
                            + e.getMessage() + " (SQL state: " + e.getSQLState()
                            + ", error code: " + e.getErrorCode() + ")");
            throw e;
        }
    }

    private static boolean isColumnAlreadyExistsError(SQLException e) {
        if (e == null)
            return false;
        if (e.getErrorCode() == 1060)
            return true;
        if ("42S21".equals(e.getSQLState()))
            return true;
        String msg = e.getMessage();
        String lowerMsg = msg != null ? msg.toLowerCase() : "";
        if (lowerMsg.contains("duplicate column name"))
            return true;
        if (lowerMsg.contains("already exists"))
            return true;
        if ("X014".equals(e.getSQLState()))
            return true;
        return false;
    }

    @Override
    public CompletableFuture<FishingData> loadData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            // v1.6.0 — SELECT now includes the 12 new skill+stat columns.
            String sql = "SELECT fishing_xp, fishing_level, total_xp, discovered_fish, " +
                    "skill_points, skill_fast, skill_hunter, skill_double, skill_bait, balance, " +
                    "crafting_xp, crafting_level, total_crafting_xp, " +
                    "skill_big, skill_lucky, skill_stamina, " +
                    "total_catches, biggest_weight, biggest_fish_name, " +
                    "rarest_catch_rarity, rarest_catch_name, " +
                    "total_earnings, current_streak, best_streak, mutation_count " +
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

                        FishingData data = new FishingData(uuid, fishingXp, fishingLevel, totalXp, discoveredFish,
                                skillPoints, fast, hunter, dbl, bait, balance,
                                craftingXp, craftingLevel, totalCraftingXp);
                        // v1.6.0 — Restore the new skill + stat fields via setters
                        // (the existing 14-arg constructor only knows the 4 original skills).
                        // NOTE: bug #3 fix is required for skill values >5 to survive the
                        // setter's clampSkill() call — tracked separately.
                        data.setBigCatch(rs.getInt("skill_big"));
                        data.setLuckyCast(rs.getInt("skill_lucky"));
                        data.setStamina(rs.getInt("skill_stamina"));
                        data.setTotalCatches(rs.getInt("total_catches"));
                        data.setBiggestWeight(rs.getDouble("biggest_weight"));
                        data.setBiggestFishName(rs.getString("biggest_fish_name"));
                        data.setRarestCatchRarity(rs.getInt("rarest_catch_rarity"));
                        data.setRarestCatchName(rs.getString("rarest_catch_name"));
                        data.setTotalEarnings(rs.getDouble("total_earnings"));
                        data.setCurrentStreak(rs.getInt("current_streak"));
                        data.setBestStreak(rs.getInt("best_streak"));
                        data.setMutationCount(rs.getInt("mutation_count"));
                        data.markLoaded();
                        return data;
                    }
                }
            } catch (SQLException e) {
                String msg = plugin.getMessageManager().getPlainMessage("db_failed_load_data");
                plugin.getLogger().severe(msg.replace("%uuid%", uuid.toString()).replace("%error%", e.getMessage()));
                throw new CompletionException(e);
            }
            FishingData newData = new FishingData(uuid, 0, 1, 0, new HashSet<>(), 0, 0, 0, 0, 0, 0.0, 0, 1, 0);
            newData.markLoaded();
            return newData;
        }, dbExecutor);
    }

    @Override
    public CompletableFuture<Void> saveData(UUID uuid, FishingData data) {
        return CompletableFuture.runAsync(() -> {
            // v1.6.0 — INSERT/UPDATE now covers the 12 new skill+stat columns.
            String sql = "INSERT INTO player_professions (uuid, total_xp, fishing_xp, fishing_level, discovered_fish, "
                    +
                    "skill_points, skill_fast, skill_hunter, skill_double, skill_bait, balance, " +
                    "crafting_xp, crafting_level, total_crafting_xp, " +
                    "skill_big, skill_lucky, skill_stamina, " +
                    "total_catches, biggest_weight, biggest_fish_name, " +
                    "rarest_catch_rarity, rarest_catch_name, " +
                    "total_earnings, current_streak, best_streak, mutation_count) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
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
                    "total_crafting_xp = VALUES(total_crafting_xp), " +
                    "skill_big = VALUES(skill_big), " +
                    "skill_lucky = VALUES(skill_lucky), " +
                    "skill_stamina = VALUES(skill_stamina), " +
                    "total_catches = VALUES(total_catches), " +
                    "biggest_weight = VALUES(biggest_weight), " +
                    "biggest_fish_name = VALUES(biggest_fish_name), " +
                    "rarest_catch_rarity = VALUES(rarest_catch_rarity), " +
                    "rarest_catch_name = VALUES(rarest_catch_name), " +
                    "total_earnings = VALUES(total_earnings), " +
                    "current_streak = VALUES(current_streak), " +
                    "best_streak = VALUES(best_streak), " +
                    "mutation_count = VALUES(mutation_count);";

            // C-7 FIX: rethrow so exceptionally completes — caller can detect save failure.
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
                // v1.6.0 — bind the 12 new columns
                ps.setInt(15, data.getBigCatch());
                ps.setInt(16, data.getLuckyCast());
                ps.setInt(17, data.getStamina());
                ps.setInt(18, data.getTotalCatches());
                ps.setDouble(19, data.getBiggestWeight());
                ps.setString(20, data.getBiggestFishName());
                ps.setInt(21, data.getRarestCatchRarity());
                ps.setString(22, data.getRarestCatchName());
                ps.setDouble(23, data.getTotalEarnings());
                ps.setInt(24, data.getCurrentStreak());
                ps.setInt(25, data.getBestStreak());
                ps.setInt(26, data.getMutationCount());
                ps.executeUpdate();
            } catch (SQLException e) {
                String msg = plugin.getMessageManager().getPlainMessage("db_failed_save_data");
                plugin.getLogger().severe(msg.replace("%uuid%", uuid.toString()).replace("%error%", e.getMessage()));
                throw new CompletionException(e);
            }
        }, dbExecutor);
    }

    @Override
    public CompletableFuture<Void> batchSave(Collection<FishingData> dataCollection) {
        return CompletableFuture.runAsync(() -> {
            // v1.6.0 — batch INSERT/UPDATE mirrors saveData's column list.
            String sql = "INSERT INTO player_professions (uuid, total_xp, fishing_xp, fishing_level, discovered_fish, "
                    +
                    "skill_points, skill_fast, skill_hunter, skill_double, skill_bait, balance, " +
                    "crafting_xp, crafting_level, total_crafting_xp, " +
                    "skill_big, skill_lucky, skill_stamina, " +
                    "total_catches, biggest_weight, biggest_fish_name, " +
                    "rarest_catch_rarity, rarest_catch_name, " +
                    "total_earnings, current_streak, best_streak, mutation_count) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
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
                    "total_crafting_xp = VALUES(total_crafting_xp), " +
                    "skill_big = VALUES(skill_big), " +
                    "skill_lucky = VALUES(skill_lucky), " +
                    "skill_stamina = VALUES(skill_stamina), " +
                    "total_catches = VALUES(total_catches), " +
                    "biggest_weight = VALUES(biggest_weight), " +
                    "biggest_fish_name = VALUES(biggest_fish_name), " +
                    "rarest_catch_rarity = VALUES(rarest_catch_rarity), " +
                    "rarest_catch_name = VALUES(rarest_catch_name), " +
                    "total_earnings = VALUES(total_earnings), " +
                    "current_streak = VALUES(current_streak), " +
                    "best_streak = VALUES(best_streak), " +
                    "mutation_count = VALUES(mutation_count);";

            // C-7 FIX: rethrow on failure so exceptionally completes.
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
                        // v1.6.0 — bind the 12 new columns
                        ps.setInt(15, data.getBigCatch());
                        ps.setInt(16, data.getLuckyCast());
                        ps.setInt(17, data.getStamina());
                        ps.setInt(18, data.getTotalCatches());
                        ps.setDouble(19, data.getBiggestWeight());
                        ps.setString(20, data.getBiggestFishName());
                        ps.setInt(21, data.getRarestCatchRarity());
                        ps.setString(22, data.getRarestCatchName());
                        ps.setDouble(23, data.getTotalEarnings());
                        ps.setInt(24, data.getCurrentStreak());
                        ps.setInt(25, data.getBestStreak());
                        ps.setInt(26, data.getMutationCount());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    conn.commit();
                } catch (SQLException batchEx) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackEx) {
                        plugin.getLogger().severe("MySQL: rollback failed during batchSave: "
                                + rollbackEx.getMessage());
                    }
                    throw batchEx;
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } catch (SQLException ignored) {
                    }
                }
            } catch (SQLException e) {
                String msg = plugin.getMessageManager().getPlainMessage("db_failed_batch_save");
                plugin.getLogger().severe(msg.replace("%error%", e.getMessage()));
                throw new CompletionException(e);
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
