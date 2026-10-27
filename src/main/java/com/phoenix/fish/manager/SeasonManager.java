package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.6.0 — Fishing Seasons (Balıkçılık sezonları).
 *
 * <p>Belirli sürelerde (örn. 30 gün) bir sezon döner. Her yakalama
 * sezon puanı verir. Sezon sonu sıralama yapılır ve ödül dağıtılır.</p>
 *
 * <p>Config: features.seasons.enabled</p>
 */
public class SeasonManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final boolean enabled;

    /** Per-player season scores. */
    private final Map<UUID, Integer> scores = new ConcurrentHashMap<>();
    private long seasonStartTime;
    private int seasonNumber;
    private volatile boolean dirty = false;

    public SeasonManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.enabled = plugin.getConfig().getBoolean("features.seasons.enabled", false);
        if (enabled) {
            load();
            scheduleSeasonCheck();
            plugin.getLogger().info("SeasonManager enabled — season " + seasonNumber
                    + " (started " + new Date(seasonStartTime) + ")");
        }
    }

    public boolean isEnabled() { return enabled; }

    // =====================================================================
    //  Persistence
    // =====================================================================

    private File getFile() {
        return new File(plugin.getDataFolder(), "seasons.yml");
    }

    private void load() {
        File file = getFile();
        if (!file.exists()) {
            seasonStartTime = System.currentTimeMillis();
            seasonNumber = 1;
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        seasonStartTime = cfg.getLong("start-time", System.currentTimeMillis());
        seasonNumber = cfg.getInt("season-number", 1);
        ConfigurationSection scoresSec = cfg.getConfigurationSection("scores");
        if (scoresSec != null) {
            for (String uuidStr : scoresSec.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    scores.put(uuid, scoresSec.getInt(uuidStr, 0));
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    public synchronized void save() {
        if (!enabled || !dirty) return;
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("start-time", seasonStartTime);
        cfg.set("season-number", seasonNumber);
        for (Map.Entry<UUID, Integer> entry : scores.entrySet()) {
            cfg.set("scores." + entry.getKey().toString(), entry.getValue());
        }
        try {
            cfg.save(getFile());
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save seasons.yml: " + e.getMessage());
        }
    }

    // =====================================================================
    //  Score Management
    // =====================================================================

    public void addScore(Player player, int points) {
        if (!enabled || player == null || points <= 0) return;
        UUID uuid = player.getUniqueId();
        scores.merge(uuid, points, Integer::sum);
        dirty = true;
    }

    public int getScore(UUID uuid) {
        return scores.getOrDefault(uuid, 0);
    }

    public Map<UUID, Integer> getLeaderboard() {
        List<Map.Entry<UUID, Integer>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        Map<UUID, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> entry : sorted) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    // =====================================================================
    //  Season Cycle
    // =====================================================================

    private void scheduleSeasonCheck() {
        long durationDays = plugin.getConfig().getLong("features.seasons.duration-days", 30);
        long intervalTicks = 20L * 60 * 60; // Check every hour
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long elapsed = System.currentTimeMillis() - seasonStartTime;
            long durationMs = durationDays * 24L * 60 * 60 * 1000;
            if (elapsed >= durationMs) {
                endSeason();
            }
        }, intervalTicks, intervalTicks);
    }

    private void endSeason() {
        plugin.getLogger().info("Season " + seasonNumber + " ending — distributing rewards.");

        if (plugin.getConfig().getBoolean("features.seasons.announce-end", true)) {
            Bukkit.broadcast(miniMessage.deserialize(
                    "<gold><bold>★ Fishing Season " + seasonNumber + " has ended!</bold></gold>"));
        }

        // Leaderboard
        Map<UUID, Integer> leaderboard = getLeaderboard();
        ConfigurationSection rewards = plugin.getConfig().getConfigurationSection("features.seasons.end-rewards");
        int rank = 1;
        for (Map.Entry<UUID, Integer> entry : leaderboard.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;

            if (rewards != null) {
                ConfigurationSection rankSec = rewards.getConfigurationSection(String.valueOf(rank));
                if (rankSec != null) {
                    double money = rankSec.getDouble("money", 0);
                    if (money > 0) {
                        // v1.6.1 (bug #4 fix): CacheManager null guard.
                        var cache = plugin.getCacheManager();
                        if (cache != null) {
                            var data = cache.getData(player.getUniqueId());
                            if (data != null) data.addBalance(money);
                        }
                    }
                    for (String cmd : rankSec.getStringList("commands")) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                cmd.replace("%player%", player.getName()));
                    }
                    player.sendMessage(miniMessage.deserialize(
                            "<gold>Season Rank " + rank + ": <white>$" + String.format("%.2f", money)));
                }
            }
            rank++;
        }

        // Participation reward
        double participationMoney = plugin.getConfig().getDouble("features.seasons.participation-reward.money", 0);
        for (UUID uuid : scores.keySet()) {
            if (participationMoney > 0) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    // v1.6.1 (bug #4 fix): CacheManager null guard.
                    var cache = plugin.getCacheManager();
                    if (cache != null) {
                        var data = cache.getData(uuid);
                        if (data != null) data.addBalance(participationMoney);
                    }
                    p.sendMessage(miniMessage.deserialize(
                            "<gray>Season participation reward: <green>$"
                            + String.format("%.2f", participationMoney)));
                }
            }
        }

        // Start new season
        seasonNumber++;
        seasonStartTime = System.currentTimeMillis();
        scores.clear();
        dirty = true;
        save();

        Bukkit.broadcast(miniMessage.deserialize(
                "<gold><bold>★ Fishing Season " + seasonNumber + " has begun!</bold></gold>"));
    }

    public void shutdown() { save(); }
    public void clear(UUID uuid) { scores.remove(uuid); dirty = true; }

    public long getSeasonStartTime() { return seasonStartTime; }
    public int getSeasonNumber() { return seasonNumber; }

    public long getTimeRemaining() {
        long durationDays = plugin.getConfig().getLong("features.seasons.duration-days", 30);
        long durationMs = durationDays * 24L * 60 * 60 * 1000;
        long elapsed = System.currentTimeMillis() - seasonStartTime;
        return Math.max(0, durationMs - elapsed);
    }
}
