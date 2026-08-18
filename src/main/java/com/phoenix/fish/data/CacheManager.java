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

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class CacheManager implements Listener {

    private final PhoenixFish plugin;
    private final ConcurrentHashMap<UUID, FishingData> playerCache;
    private final AtomicLong sessionIdCounter = new AtomicLong(0);
    private final Map<UUID, Long> sessionGenerations = new ConcurrentHashMap<>();
    private BukkitRunnable autoSaveTask;

    public CacheManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.playerCache = new ConcurrentHashMap<>();
        startAutoSave();
    }

    public FishingData getData(UUID uuid) {
        return playerCache.get(uuid);
    }

    public Collection<FishingData> getAllCachedData() {
        return playerCache.values();
    }

    private void startAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }

        autoSaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (playerCache.isEmpty())
                    return;

                plugin.getDatabase().batchSave(playerCache.values());

                String message = plugin.getMessageManager().getPlainMessage("console_autosave");
                message = message.replace("%players%", String.valueOf(playerCache.size()));
                plugin.getLogger().info(message);
            }
        };
        autoSaveTask.runTaskTimerAsynchronously(plugin, 6000L, 6000L);
    }

    public void shutdown() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.isXpSystemEnabled())
            return;

        UUID uuid = event.getPlayer().getUniqueId();
        long gen = sessionIdCounter.incrementAndGet();
        sessionGenerations.put(uuid, gen);

        FishingData tempData = new FishingData(uuid, 0, 1, 0, null, 0, 0, 0, 0, 0, 0.0, 0, 1, 0);
        playerCache.put(uuid, tempData);

        plugin.getDatabase().loadData(uuid).thenAccept(loadedData -> {
            if (loadedData == null) {
                plugin.getLogger().warning("Could not load data for " + uuid + ", using temp data.");
                return;
            }

            playerCache.compute(uuid, (key, currentTempData) -> {
                Long currentGen = sessionGenerations.get(uuid);
                if (currentGen == null || currentGen != gen || currentTempData == null) {
                    return currentTempData;
                }

                if (currentTempData.getCurrentXp() > 0) {
                    loadedData.addXp(currentTempData.getCurrentXp());
                }
                if (currentTempData.getCraftingXp() > 0) {
                    loadedData.addCraftingXp(currentTempData.getCraftingXp());
                }

                return loadedData;
            });
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.isXpSystemEnabled())
            return;

        UUID uuid = event.getPlayer().getUniqueId();
        sessionGenerations.remove(uuid);

        FishingData data = playerCache.remove(uuid);
        if (data != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                plugin.getDatabase().saveData(uuid, data);
            });
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

        int leveled = 0;
        while (data.checkLevelUp()) {
            leveled++;
        }

        if (leveled > 0) {
            Map<String, String> placeholders = Map.of(
                    "%level%", String.valueOf(data.getCurrentLevel()),
                    "%levels%", String.valueOf(leveled));
            player.sendMessage(plugin.getMessageManager().getMessage("level_up", true, placeholders));
        }
    }

    public void addCraftingXpAndCheckLevel(Player player, int xpReward) {
        FishingData data = playerCache.get(player.getUniqueId());
        if (data == null)
            return;

        data.addCraftingXp(xpReward);

        int leveled = 0;
        while (data.checkCraftingLevelUp()) {
            leveled++;
        }

        if (leveled > 0) {
            Map<String, String> placeholders = Map.of(
                    "%level%", String.valueOf(data.getCraftingLevel()),
                    "%levels%", String.valueOf(leveled));
            player.sendMessage(plugin.getMessageManager().getMessage("crafting_level_up", true, placeholders));
        }
    }

    public void saveAll() {
        if (playerCache.isEmpty())
            return;
        plugin.getDatabase().batchSave(playerCache.values());
    }
}