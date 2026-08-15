package com.phoenix.fish;

import com.phoenix.fish.api.PhoenixFishAPI;
import com.phoenix.fish.command.FishCommand;
import com.phoenix.fish.data.CacheManager;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.listener.FishingListener;
import com.phoenix.fish.manager.BaitManager;
import com.phoenix.fish.manager.CraftingManager;
import com.phoenix.fish.manager.FishManager;
import com.phoenix.fish.manager.MessageManager;
import com.phoenix.fish.manager.database.IDatabase;
import com.phoenix.fish.manager.database.MySQLManager;
import com.phoenix.fish.manager.database.SQLiteManager;
import com.phoenix.fish.model.CustomFish;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class PhoenixFish extends JavaPlugin {

    private static PhoenixFish instance;

    private FishManager fishManager;
    private CraftingManager craftingManager;
    private BaitManager baitManager;
    private IDatabase database;
    private FishingListener fishingListener;
    private CacheManager cacheManager;
    private MessageManager messageManager;

    @Override
    public void onEnable() {
        instance = this;

        PhoenixFishAPI.init(this);

        loadPluginResources();

        this.messageManager = new MessageManager(this);

        this.fishManager = new FishManager(this);
        this.fishManager.loadFish();

        this.craftingManager = new CraftingManager(this);
        this.craftingManager.loadRecipes();

        this.baitManager = new BaitManager(this);
        this.baitManager.loadBaits();

        setupDatabaseSystem();

        this.fishingListener = new FishingListener(this);
        Bukkit.getPluginManager().registerEvents(fishingListener, this);

        registerCommands();
    }

    @Override
    public void onDisable() {
        PhoenixFishAPI.shutdown();

        if (cacheManager != null && database != null) {
            getLogger().info("Saving all cached player data...");

            java.util.Collection<FishingData> allData = cacheManager.getAllCachedData();

            if (!allData.isEmpty()) {
                try {
                    database.batchSave(allData).get(10, TimeUnit.SECONDS);
                    getLogger().info("Successfully saved all player data.");
                } catch (Exception e) {
                    getLogger().severe("Failed to save batch data during shutdown: " + e.getMessage());
                }
            }
        }

        if (database != null) {
            try {
                database.close();
                getLogger().info("Database connection closed successfully.");
            } catch (Exception e) {
                getLogger().severe("An error occurred while closing the database: " + e.getMessage());
            }
        }

        instance = null;
    }

    private void loadPluginResources() {
        try {
            saveDefaultConfig();
            saveResource("fish.yml", false);
            saveResource("rods.yml", false);
            saveResource("custom_recipes.yml", false);
            saveResource("baits.yml", false);
            saveResource("messages_en.yml", false);
            saveResource("messages_tr.yml", false);
        } catch (IllegalArgumentException e) {
            getLogger().severe("Could not save default resources: " + e.getMessage());
        }
    }

    private void setupDatabaseSystem() {
        if (getConfig().getBoolean("xp-system.enabled", false)) {
            String type = getConfig().getString("xp-system.database.type", "sqlite");
            try {
                if ("mysql".equalsIgnoreCase(type)) {
                    this.database = new MySQLManager(this);
                } else {
                    this.database = new SQLiteManager(this);
                }
                this.database.init();
                getLogger().info("XP System is active! Database type: " + type.toUpperCase());
                this.cacheManager = new CacheManager(this);
                Bukkit.getPluginManager().registerEvents(cacheManager, this);
            } catch (Exception e) {
                getLogger().severe("Failed to initialize the database! XP System disabled. Reason: " + e.getMessage());
                this.database = null;
            }
        } else {
            getLogger().info("XP System is disabled. Operating in fishing-only mode.");
        }
    }

    private void registerCommands() {
        PluginCommand command = getCommand("phoenixfish");
        if (command != null) {
            command.setExecutor(new FishCommand(this));
        } else {
            getLogger().severe(
                    "Command 'phoenixfish' is not defined in plugin.yml! The /phoenixfish command will not work.");
        }
    }

    public void playCatchEffects(Player player, CustomFish fish) {
        if (fish.rarity() >= 4) {
            Map<String, String> ph = new HashMap<>();
            ph.put("%fish_name%", PlainTextComponentSerializer.plainText().serialize(fish.name()));

            String titleKey = fish.rarity() >= 5 ? "catch_title_legendary" : "catch_title_epic";
            String subKey = fish.rarity() >= 5 ? "catch_subtitle_legendary" : "catch_subtitle_epic";

            Title.Times times = Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3000),
                    Duration.ofMillis(1000));
            Title title = Title.title(
                    messageManager.getMessage(titleKey, false, ph),
                    messageManager.getMessage(subKey, false, ph),
                    times);

            player.showTitle(title);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        } else if (fish.rarity() == 3) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
        }
    }

    public static PhoenixFish getInstance() {
        return instance;
    }

    public FishManager getFishManager() {
        return fishManager;
    }

    public CraftingManager getCraftingManager() {
        return craftingManager;
    }

    public BaitManager getBaitManager() {
        return baitManager;
    }

    public IDatabase getDatabase() {
        return database;
    }

    public FishingListener getFishingListener() {
        return fishingListener;
    }

    public CacheManager getCacheManager() {
        return cacheManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public boolean isXpSystemEnabled() {
        return database != null;
    }
}