package com.phoenix.fish.data;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.event.PhoenixXPGainEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the in-memory caching of player fishing data.
 * Stores data in a thread-safe ConcurrentHashMap to reduce database load.
 */
public class CacheManager implements Listener {

    private final PhoenixFish plugin;
    private final ConcurrentHashMap<UUID, FishingData> playerCache;

    public CacheManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.playerCache = new ConcurrentHashMap<>();
        startAutoSave();
    }

    public FishingData getData(UUID uuid) {
        return playerCache.get(uuid);
    }

    private void startAutoSave() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (playerCache.isEmpty())
                    return;

                // Toplu kaydetme (Batch Save) ile MySQL yükü azaltılır
                plugin.getDatabase().batchSave(playerCache.values());
                plugin.getLogger().info("Auto-saved fishing data for " + playerCache.size() + " online players.");
            }
        }.runTaskTimerAsynchronously(plugin, 6000L, 6000L); // 5 dakika
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.isXpSystemEnabled())
            return;

        UUID uuid = event.getPlayer().getUniqueId();

        FishingData tempData = new FishingData(uuid, 0, 1, 0);
        playerCache.put(uuid, tempData);

        plugin.getDatabase().loadData(uuid).thenAccept(loadedData -> {
            if (Bukkit.getPlayer(uuid) == null) {
                playerCache.remove(uuid);
                plugin.getDatabase().saveData(uuid, loadedData);
                return;
            }

            int xpGainedDuringLoad = tempData.getCurrentXp();
            if (xpGainedDuringLoad > 0) {
                loadedData.addXp(xpGainedDuringLoad);
            }
            playerCache.put(uuid, loadedData);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.isXpSystemEnabled())
            return;

        UUID uuid = event.getPlayer().getUniqueId();
        FishingData data = playerCache.remove(uuid);
        if (data != null) {
            plugin.getDatabase().saveData(uuid, data);
        }
    }

    public void addXpAndCheckLevel(Player player, int xpReward) {
        FishingData data = playerCache.get(player.getUniqueId());
        if (data == null)
            return;

        PhoenixXPGainEvent event = new PhoenixXPGainEvent(player.getUniqueId(), xpReward, "FISHING");
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled())
            return;

        data.addXp(xpReward);

        while (data.checkLevelUp()) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("%level%", String.valueOf(data.getCurrentLevel()));
            player.sendMessage(plugin.getMessageManager().getMessage("level_up", true, placeholders));
        }
    }

    public java.util.Collection<FishingData> getAllCachedData() {
        return playerCache.values();
    }
}