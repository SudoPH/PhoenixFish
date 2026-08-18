package com.phoenix.fish;

import com.phoenix.fish.api.PhoenixFishAPI;
import com.phoenix.fish.command.FishCommand;
import com.phoenix.fish.data.CacheManager;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.listener.CatalogListener;
import com.phoenix.fish.listener.FishingListener;
import com.phoenix.fish.listener.SellListener;
import com.phoenix.fish.listener.SkillListener;
import com.phoenix.fish.manager.*;
import com.phoenix.fish.manager.database.IDatabase;
import com.phoenix.fish.manager.database.MySQLManager;
import com.phoenix.fish.manager.database.SQLiteManager;
import com.phoenix.fish.model.CustomFish;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class PhoenixFish extends JavaPlugin {

    private static volatile PhoenixFish instance;

    private FishManager fishManager;
    private CraftingManager craftingManager;
    private BaitManager baitManager;
    private IDatabase database;
    private FishingListener fishingListener;
    private CacheManager cacheManager;
    private MessageManager messageManager;
    private CatalogManager catalogManager;
    private TournamentManager tournamentManager;
    private RecordManager recordManager;
    private SkillManager skillManager;
    private EconomyManager economyManager;

    private PhoenixFishAPI api;

    private boolean xpEnabled;
    private boolean discoveryEnabled;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        saveResource("messages_en.yml", false);
        saveResource("messages_tr.yml", false);

        this.messageManager = new MessageManager(this);
        loadPluginResources();

        this.xpEnabled = getConfig().getBoolean("xp-system.enabled", false);
        this.discoveryEnabled = getConfig().getBoolean("settings.discovery-enabled", true);

        this.fishManager = new FishManager(this);
        this.fishManager.loadFish();

        this.craftingManager = new CraftingManager(this);
        this.craftingManager.loadRecipes();

        this.baitManager = new BaitManager(this);
        this.baitManager.loadBaits();

        this.recordManager = new RecordManager(this);
        this.tournamentManager = new TournamentManager(this);
        this.economyManager = new EconomyManager(this);
        this.skillManager = new SkillManager(this);

        setupDatabaseSystem();

        this.fishingListener = new FishingListener(this);
        Bukkit.getPluginManager().registerEvents(fishingListener, this);

        this.catalogManager = new CatalogManager(this);
        this.catalogManager.init();

        Bukkit.getPluginManager().registerEvents(new CatalogListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SkillListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SellListener(this), this);

        registerCommands();

        this.api = new PhoenixFishAPI(this);
        Bukkit.getServicesManager().register(PhoenixFishAPI.class, this.api, this,
                org.bukkit.plugin.ServicePriority.Normal);

        getLogger().info("PhoenixFish başarıyla etkinleştirildi!");
    }

    @Override
    public void onDisable() {
        if (this.api != null) {
            Bukkit.getServicesManager().unregister(PhoenixFishAPI.class, this.api);
        }

        if (tournamentManager != null)
            tournamentManager.shutdown();
        if (fishingListener != null)
            fishingListener.shutdown();

        if (cacheManager != null && database != null) {
            String logPrefix = messageManager != null ? messageManager.getPlainMessage("plugin_saving_cache")
                    : "Saving cache...";
            getLogger().info(logPrefix);

            Collection<FishingData> allData = cacheManager.getAllCachedData();
            if (!allData.isEmpty()) {
                try {
                    database.batchSave(allData).get(5, TimeUnit.SECONDS);
                    if (messageManager != null)
                        getLogger().info(messageManager.getPlainMessage("plugin_cache_saved"));
                } catch (TimeoutException e) {
                    getLogger().warning("Cache save timed out, some data may be lost.");
                } catch (Exception e) {
                    String msg = messageManager != null ? messageManager.getPlainMessage("plugin_cache_save_failed")
                            : "Cache save failed: %error%";
                    getLogger().severe(msg.replace("%error%", e.getMessage()));
                }
            }
        }

        if (database != null) {
            try {
                database.close();
                if (messageManager != null)
                    getLogger().info(messageManager.getPlainMessage("plugin_db_closed"));
            } catch (Exception e) {
                String msg = messageManager != null ? messageManager.getPlainMessage("plugin_db_close_error")
                        : "DB close error: %error%";
                getLogger().severe(msg.replace("%error%", e.getMessage()));
            }
        }

        if (cacheManager != null) {
            cacheManager.shutdown();
        }

        instance = null;
    }

    private void loadPluginResources() {
        try {
            saveResource("fish.yml", false);
            saveResource("rods.yml", false);
            saveResource("custom_recipes.yml", false);
            saveResource("baits.yml", false);

            if (getConfig().getString("settings.language", "en").equalsIgnoreCase("tr")) {
                if (getResource("fish_tr.yml") != null) {
                    saveResource("fish_tr.yml", false);
                } else {
                    getLogger().warning("fish_tr.yml bulunamadı, varsayılan fish.yml kullanılacak.");
                }
            }
        } catch (IllegalArgumentException e) {
            String msg = messageManager != null ? messageManager.getPlainMessage("plugin_resource_save_error")
                    : "Resource save error: %error%";
            getLogger().severe(msg.replace("%error%", e.getMessage()));
        }
    }

    private void setupDatabaseSystem() {
        if (!xpEnabled && !discoveryEnabled) {
            getLogger().info(messageManager != null ? messageManager.getPlainMessage("plugin_systems_disabled")
                    : "Systems disabled");
            return;
        }

        String type = getConfig().getString("xp-system.database.type", "sqlite");

        try {
            if ("mysql".equalsIgnoreCase(type)) {
                this.database = new MySQLManager(this);
            } else {
                this.database = new SQLiteManager(this);
            }

            this.database.init();

            String msg = messageManager != null ? messageManager.getPlainMessage("plugin_db_active")
                    : "Database active (XP: %xp_status%, Discovery: %discovery_status%)";
            getLogger().info(msg.replace("%xp_status%", String.valueOf(xpEnabled)).replace("%discovery_status%",
                    String.valueOf(discoveryEnabled)));

            this.cacheManager = new CacheManager(this);
            Bukkit.getPluginManager().registerEvents(cacheManager, this);

        } catch (Exception e) {
            String msg = messageManager != null ? messageManager.getPlainMessage("plugin_db_init_failed")
                    : "Database init failed: %error%";
            getLogger().severe(msg.replace("%error%", e.getMessage()));
            this.database = null;
            this.cacheManager = null;
        }
    }

    private void registerCommands() {
        PluginCommand command = getCommand("phoenixfish");
        if (command != null) {
            command.setExecutor(new FishCommand(this));
        } else {
            String msg = messageManager != null ? messageManager.getPlainMessage("plugin_cmd_not_defined")
                    : "Command not defined in plugin.yml";
            getLogger().severe(msg);
        }
    }

    public void playCatchEffects(Player player, CustomFish fish) {
        if (fish == null || player == null)
            return;

        if (fish.rarity() >= 4) {
            @SuppressWarnings("null")
            Map<String, String> ph = Collections.singletonMap("%fish_name%",
                    PlainTextComponentSerializer.plainText().serialize(fish.name()));

            String titleKey = fish.rarity() >= 5 ? "catch_title_legendary" : "catch_title_epic";
            String subKey = fish.rarity() >= 5 ? "catch_subtitle_legendary" : "catch_subtitle_epic";

            Title.Times times = Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3000),
                    Duration.ofMillis(1000));
            Title title = Title.title(
                    messageManager.getMessage(titleKey, false, ph),
                    messageManager.getMessage(subKey, false, ph),
                    times);

            player.showTitle(title);
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        } else if (fish.rarity() == 3) {
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
        }
    }

    public static PhoenixFish getInstance() {
        return instance;
    }

    public PhoenixFishAPI getApi() {
        return api;
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

    public CatalogManager getCatalogManager() {
        return catalogManager;
    }

    public TournamentManager getTournamentManager() {
        return tournamentManager;
    }

    public RecordManager getRecordManager() {
        return recordManager;
    }

    public SkillManager getSkillManager() {
        return skillManager;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public boolean isXpSystemEnabled() {
        return xpEnabled && database != null;
    }

    public boolean isDiscoveryEnabled() {
        return discoveryEnabled && database != null;
    }
}