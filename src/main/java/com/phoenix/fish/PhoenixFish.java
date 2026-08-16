package com.phoenix.fish;

import com.phoenix.fish.api.PhoenixFishAPI;
import com.phoenix.fish.command.FishCommand;
import com.phoenix.fish.data.CacheManager;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.listener.CatalogListener;
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
import com.phoenix.fish.manager.CatalogManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Collection;
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
    private CatalogManager catalogManager;

    @Override
    public void onEnable() {
        instance = this;

        PhoenixFishAPI.init(this);

        // Save essential config and language files first
        saveDefaultConfig();
        saveResource("messages_en.yml", false);
        saveResource("messages_tr.yml", false);

        this.messageManager = new MessageManager(this);

        loadPluginResources();

        this.fishManager = new FishManager(this);
        this.fishManager.loadFish();

        this.craftingManager = new CraftingManager(this);
        this.craftingManager.loadRecipes();

        this.baitManager = new BaitManager(this);
        this.baitManager.loadBaits();

        setupDatabaseSystem();

        this.fishingListener = new FishingListener(this);
        Bukkit.getPluginManager().registerEvents(fishingListener, this);

        this.catalogManager = new CatalogManager(this);
        this.catalogManager.init();
        Bukkit.getPluginManager().registerEvents(new CatalogListener(this), this);

        registerCommands();
    }

    @Override
    public void onDisable() {
        PhoenixFishAPI.shutdown();

        if (cacheManager != null && database != null) {
            getLogger().info(messageManager.getPlainMessage("plugin_saving_cache"));
            Collection<FishingData> allData = cacheManager.getAllCachedData();

            if (!allData.isEmpty()) {
                try {
                    database.batchSave(allData).get(10, TimeUnit.SECONDS);
                    getLogger().info(messageManager.getPlainMessage("plugin_cache_saved"));
                } catch (Exception e) {
                    String msg = messageManager.getPlainMessage("plugin_cache_save_failed");
                    getLogger().severe(msg.replace("%error%", e.getMessage()));
                }
            }
        }

        if (database != null) {
            try {
                database.close();
                getLogger().info(messageManager.getPlainMessage("plugin_db_closed"));
            } catch (Exception e) {
                String msg = messageManager.getPlainMessage("plugin_db_close_error");
                getLogger().severe(msg.replace("%error%", e.getMessage()));
            }
        }

        instance = null;
    }

    private void loadPluginResources() {
        try {
            saveResource("fish.yml", false);
            saveResource("rods.yml", false);
            saveResource("custom_recipes.yml", false);
            saveResource("baits.yml", false);
        } catch (IllegalArgumentException e) {
            String msg = messageManager.getPlainMessage("plugin_resource_save_error");
            getLogger().severe(msg.replace("%error%", e.getMessage()));
        }
    }

    private void setupDatabaseSystem() {
        boolean xpEnabled = getConfig().getBoolean("xp-system.enabled", false);
        boolean discoveryEnabled = getConfig().getBoolean("settings.discovery-enabled", true); // New setting

        if (xpEnabled || discoveryEnabled) {
            String type = getConfig().getString("xp-system.database.type", "sqlite");

            try {
                if ("mysql".equalsIgnoreCase(type)) {
                    this.database = new MySQLManager(this);
                } else {
                    this.database = new SQLiteManager(this);
                }

                this.database.init();

                String msg = messageManager.getPlainMessage("plugin_db_active");
                getLogger().info(msg.replace("%xp_status%", String.valueOf(xpEnabled))
                        .replace("%discovery_status%", String.valueOf(discoveryEnabled)));

                this.cacheManager = new CacheManager(this);
                Bukkit.getPluginManager().registerEvents(cacheManager, this);
            } catch (Exception e) {
                String msg = messageManager.getPlainMessage("plugin_db_init_failed");
                getLogger().severe(msg.replace("%error%", e.getMessage()));
                this.database = null;
            }
        } else {
            getLogger().info(messageManager.getPlainMessage("plugin_systems_disabled"));
        }
    }

    private void registerCommands() {
        PluginCommand command = getCommand("phoenixfish");
        if (command != null) {
            command.setExecutor(new FishCommand(this));
        } else {
            getLogger().severe(messageManager.getPlainMessage("plugin_cmd_not_defined"));
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
        return getConfig().getBoolean("xp-system.enabled", false) && database != null;
    }

    public boolean isDiscoveryEnabled() {
        return getConfig().getBoolean("settings.discovery-enabled", true) && database != null;
    }

    public CatalogManager getCatalogManager() {
        return catalogManager;
    }
}