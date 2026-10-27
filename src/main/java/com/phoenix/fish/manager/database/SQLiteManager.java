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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SQLiteManager implements IDatabase {

    private final PhoenixFish plugin;
    private final Object connectionLock = new Object();
    private volatile Connection connection;
    private volatile ExecutorService dbExecutor;

    public SQLiteManager(PhoenixFish plugin) {
        this.plugin = plugin;
    }

    @Override
    public void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(plugin.getDataFolder(), "phoenix_fish.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            synchronized (connectionLock) {
                this.connection = DriverManager.getConnection(url);
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL;");
                    // v1.5.0 (F-5) — WAL checkpoint ayarları.
                    // WAL mode büyük sunucularda dosya boyutunu büyütür.
                    // `wal_autocheckpoint=1000` → her 1000 sayfa değişiklikte otomatik checkpoint.
                    // 1000 sayfa ≈ 4 MB (varsayılan sayfa boyutu 4096 byte).
                    // Bu, WAL dosyasının çok büyümesini engeller, performansı korur.
                    stmt.execute("PRAGMA wal_autocheckpoint=1000;");
                    // synchronous=NORMAL → fsync'i azaltır, daha hızlı write ama biraz daha riskli.
                    // FULL (default) en güvenli ama yavaş. NORMAL genelde yeterli WAL ile.
                    stmt.execute("PRAGMA synchronous=NORMAL;");
                    // cache -200MB → büyük sunucularda memory cache (genelde 2MB default).
                    // Bu sayede SELECT'ler memory'den gelir, disk I/O azalır.
                    stmt.execute("PRAGMA cache_size=-20000;");  // -20000 = 20 MB
                }
                createTable();
                addMissingColumns();
            }

            this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PhoenixFish-SQLite-Worker");
                t.setDaemon(true);
                return t;
            });

            plugin.getLogger().info(plugin.getMessageManager().getPlainMessage("db_connected_sqlite"));
        } catch (Exception e) {
            String msg = plugin.getMessageManager().getPlainMessage("db_failed_connect_sqlite");
            plugin.getLogger().severe(msg.replace("%error%", e.getMessage()));

            synchronized (connectionLock) {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (SQLException ignored) {
                    }
                    this.connection = null;
                }
            }
            throw new IllegalStateException("SQLite init failed", e);
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
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private void addMissingColumns() {
        // v1.6.0 — Refactored to handle three column types cleanly (INT / DOUBLE / TEXT).
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
        for (String col : intColumns)   addColumnIfMissing(col, "INT DEFAULT 0");
        for (String col : doubleColumns) addColumnIfMissing(col, "DOUBLE DEFAULT 0.0");
        for (String col : textColumns)  addColumnIfMissing(col, "TEXT DEFAULT NULL");
    }

    private void addColumnIfMissing(String col, String type) {
        String sql = "ALTER TABLE player_professions ADD COLUMN " + col + " " + type + ";";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            plugin.getLogger().info("SQLite migration: added column '" + col + "' to player_professions.");
        } catch (SQLException e) {
            if (isColumnAlreadyExistsError(e)) {
                // expected — silent
            } else {
                plugin.getLogger().warning(
                        "SQLite: could not add column '" + col + "' to player_professions: "
                                + e.getMessage() + " (SQL state: " + e.getSQLState()
                                + ", error code: " + e.getErrorCode() + ")");
            }
        }
    }

    private static boolean isColumnAlreadyExistsError(SQLException e) {
        if (e == null)
            return false;
        String msg = e.getMessage();
        String lowerMsg = msg != null ? msg.toLowerCase() : "";
        if (lowerMsg.contains("duplicate column name"))
            return true;
        if (lowerMsg.contains("already exists"))
            return true;
        if ("X014".equals(e.getSQLState()))
            return true;
        if ("42S21".equals(e.getSQLState()))
            return true;
        if (e.getErrorCode() == 1060)
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

            synchronized (connectionLock) {
                if (connection == null) {
                    throw new CompletionException(new SQLException("SQLite connection is not initialized"));
                }
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            HashSet<String> discoveredFish = new HashSet<>();
                            String discoveredStr = rs.getString("discovered_fish");
                            if (discoveredStr != null && !discoveredStr.isEmpty()) {
                                discoveredFish.addAll(Arrays.asList(discoveredStr.split(",")));
                            }

                            FishingData data = new FishingData(uuid,
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
                    String msg = plugin.getMessageManager().getPlainMessage("db_failed_load_data_sqlite");
                    plugin.getLogger().severe(msg.replace("%uuid%", uuid.toString()).replace("%error%", e.getMessage()));
                    throw new CompletionException(e);
                }
            }

            FishingData fresh = new FishingData(uuid, 0, 1, 0, new HashSet<>(), 0, 0, 0, 0, 0, 0.0, 0, 1, 0);
            fresh.markLoaded();
            return fresh;
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
                    "total_crafting_xp = EXCLUDED.total_crafting_xp, " +
                    "skill_big = EXCLUDED.skill_big, " +
                    "skill_lucky = EXCLUDED.skill_lucky, " +
                    "skill_stamina = EXCLUDED.skill_stamina, " +
                    "total_catches = EXCLUDED.total_catches, " +
                    "biggest_weight = EXCLUDED.biggest_weight, " +
                    "biggest_fish_name = EXCLUDED.biggest_fish_name, " +
                    "rarest_catch_rarity = EXCLUDED.rarest_catch_rarity, " +
                    "rarest_catch_name = EXCLUDED.rarest_catch_name, " +
                    "total_earnings = EXCLUDED.total_earnings, " +
                    "current_streak = EXCLUDED.current_streak, " +
                    "best_streak = EXCLUDED.best_streak, " +
                    "mutation_count = EXCLUDED.mutation_count;";

            synchronized (connectionLock) {
                if (connection == null) {
                    throw new CompletionException(new SQLException("SQLite connection is not initialized"));
                }
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
                    String msg = plugin.getMessageManager().getPlainMessage("db_failed_save_data_sqlite");
                    plugin.getLogger().severe(msg.replace("%uuid%", uuid.toString()).replace("%error%", e.getMessage()));
                    throw new CompletionException(e);
                }
            }
        }, dbExecutor);
    }

    @Override
    public CompletableFuture<Void> batchSave(Collection<FishingData> dataCollection) {
        return CompletableFuture.runAsync(() -> {
            if (dataCollection == null || dataCollection.isEmpty())
                return;

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
                    "total_crafting_xp = EXCLUDED.total_crafting_xp, " +
                    "skill_big = EXCLUDED.skill_big, " +
                    "skill_lucky = EXCLUDED.skill_lucky, " +
                    "skill_stamina = EXCLUDED.skill_stamina, " +
                    "total_catches = EXCLUDED.total_catches, " +
                    "biggest_weight = EXCLUDED.biggest_weight, " +
                    "biggest_fish_name = EXCLUDED.biggest_fish_name, " +
                    "rarest_catch_rarity = EXCLUDED.rarest_catch_rarity, " +
                    "rarest_catch_name = EXCLUDED.rarest_catch_name, " +
                    "total_earnings = EXCLUDED.total_earnings, " +
                    "current_streak = EXCLUDED.current_streak, " +
                    "best_streak = EXCLUDED.best_streak, " +
                    "mutation_count = EXCLUDED.mutation_count;";

            synchronized (connectionLock) {
                if (connection == null) {
                    throw new CompletionException(new SQLException("SQLite connection is not initialized"));
                }
                boolean committed = false;
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
                    connection.commit();
                    committed = true;
                } catch (SQLException e) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackEx) {
                        plugin.getLogger().severe("SQLite: rollback failed during batchSave: "
                                + rollbackEx.getMessage());
                    }
                    String msg = plugin.getMessageManager().getPlainMessage("db_failed_batch_save_sqlite");
                    plugin.getLogger().severe(msg.replace("%error%", e.getMessage()));
                    throw new CompletionException(e);
                } finally {
                    if (!committed) {
                        try {
                            connection.setAutoCommit(true);
                        } catch (SQLException ignored) {
                        }
                    }
                }
                // Restore autoCommit on success path too
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
        synchronized (connectionLock) {
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
}
