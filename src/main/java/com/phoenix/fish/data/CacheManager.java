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

    private static final int MAX_LEVEL_UP_ITERATIONS = 1000;

    private final PhoenixFish plugin;
    private final ConcurrentHashMap<UUID, FishingData> playerCache;
    private final AtomicLong sessionIdCounter = new AtomicLong(0);
    private final Map<UUID, Long> sessionGenerations = new ConcurrentHashMap<>();
    private BukkitRunnable autoSaveTask;

    public CacheManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.playerCache = new ConcurrentHashMap<>();
        startAutoSave();
        loadOnlinePlayers();
    }

    public FishingData getData(UUID uuid) {
        return playerCache.get(uuid);
    }

    public Collection<FishingData> getAllCachedData() {
        return playerCache.values();
    }

    public Collection<FishingData> getSavableData() {
        return playerCache.values().stream()
                .filter(FishingData::isLoaded)
                .toList();
    }

    private void startAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }

        autoSaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                Collection<FishingData> savable = getSavableData();
                if (savable.isEmpty())
                    return;

                plugin.getDatabase().batchSave(savable);

                String message = plugin.getMessageManager().getPlainMessage("console_autosave");
                message = message.replace("%players%", String.valueOf(savable.size()));
                plugin.getLogger().info(message);
            }
        };
        autoSaveTask.runTaskTimerAsynchronously(plugin, 1200L, 6000L);
    }

    public void shutdown() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
    }

    private void loadOnlinePlayers() {
        // v1.6.1 (bug #5 fix): load players when EITHER xp-system OR discovery is enabled.
        // Previously this was guarded by isXpSystemEnabled() only, so discovery-only mode
        // (xp=false + discovery=true) had a DB but no cached data — getData() always
        // returned null and discovery/contracts/economy/sell silently did nothing.
        if (!plugin.isXpSystemEnabled() && !plugin.isDiscoveryEnabled())
            return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            initiateLoad(player.getUniqueId());
        }
    }

    private void initiateLoad(UUID uuid) {
        long gen = sessionIdCounter.incrementAndGet();
        sessionGenerations.put(uuid, gen);

        FishingData tempData = new FishingData(uuid, 0, 1, 0, null, 0, 0, 0, 0, 0, 0.0, 0, 1, 0);
        playerCache.put(uuid, tempData);

        final long expectedGen = gen;
        plugin.getDatabase().loadData(uuid).thenAccept(loadedData -> {
            playerCache.compute(uuid, (key, currentTemp) -> {
                if (loadedData == null) {
                    if (currentTemp != null)
                        currentTemp.markLoaded();
                    return currentTemp;
                }

                loadedData.markLoaded();

                Long currentGen = sessionGenerations.get(uuid);
                if (currentGen == null) {
                    if (currentTemp != null) {
                        loadedData.mergeTransientFrom(currentTemp);
                        loadedData.checkAllLevelUps();
                        loadedData.checkAllCraftingLevelUps();
                    }
                    plugin.getDatabase().saveData(uuid, loadedData);
                    return null;
                }
                if (currentGen != expectedGen || currentTemp == null) {
                    return currentTemp;
                }

                loadedData.mergeTransientFrom(currentTemp);
                loadedData.checkAllLevelUps();
                loadedData.checkAllCraftingLevelUps();
                return loadedData;
            });
        }).exceptionally(ex -> {
            plugin.getLogger().severe("Failed to load data for " + uuid
                    + ", session will NOT be saved: " + ex.getMessage());
            playerCache.compute(uuid, (key, currentTemp) -> {
                if (currentTemp != null)
                    currentTemp.markLoaded();
                return currentTemp;
            });
            return null;
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // v1.6.1 (bug #5 fix): same as loadOnlinePlayers — accept either flag.
        if (!plugin.isXpSystemEnabled() && !plugin.isDiscoveryEnabled())
            return;
        initiateLoad(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // v1.6.1 (bug #5 fix): same guard so quit-time save runs in discovery-only mode too.
        if (!plugin.isXpSystemEnabled() && !plugin.isDiscoveryEnabled())
            return;

        UUID uuid = event.getPlayer().getUniqueId();
        FishingData data = playerCache.get(uuid);

        if (data != null && data.isLoaded()) {
            playerCache.remove(uuid);
            sessionGenerations.remove(uuid);
            plugin.getDatabase().saveData(uuid, data);
            return;
        }

        sessionGenerations.remove(uuid);
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
        while (data.checkLevelUp() && leveled < MAX_LEVEL_UP_ITERATIONS) {
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

        // H-7 FIX: bounded loop for crafting level-ups too.
        int leveled = 0;
        while (data.checkCraftingLevelUp() && leveled < MAX_LEVEL_UP_ITERATIONS) {
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
        Collection<FishingData> savable = getSavableData();
        if (savable.isEmpty())
            return;
        plugin.getDatabase().batchSave(savable);
    }
}
