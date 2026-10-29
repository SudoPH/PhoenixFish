package com.phoenix.fish;

import com.phoenix.fish.api.PhoenixFishAPI;
import com.phoenix.fish.command.FishCommand;
import com.phoenix.fish.command.FishTabCompleter;
import com.phoenix.fish.data.CacheManager;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.event.PhoenixFishReloadEvent;
import com.phoenix.fish.listener.AchievementContractListener;
import com.phoenix.fish.listener.CatalogListener;
import com.phoenix.fish.listener.FishingListener;
import com.phoenix.fish.listener.SellListener;
import com.phoenix.fish.listener.SkillListener;
import com.phoenix.fish.manager.*;
import com.phoenix.fish.manager.database.IDatabase;
import com.phoenix.fish.manager.database.MySQLManager;
import com.phoenix.fish.manager.database.SQLiteManager;
import com.phoenix.fish.model.CustomFish;
import com.phoenix.fish.placeholder.PhoenixFishPlaceholder;
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
    // v1.8.5 — Content (fish/baits/rods/contracts/achievements/skills) localization layer.
    private com.phoenix.fish.manager.ContentLanguageManager contentLanguageManager;
    private CatalogManager catalogManager;
    private TournamentManager tournamentManager;
    private RecordManager recordManager;
    private SkillManager skillManager;
    private EconomyManager economyManager;
    private FishingModeManager fishingModeManager;
    private com.phoenix.fish.manager.AchievementManager achievementManager;
    private com.phoenix.fish.manager.MarketManager marketManager;
    private com.phoenix.fish.manager.ContractManager contractManager;
    private com.phoenix.fish.manager.SeasonManager seasonManager;

    private PhoenixFishAPI api;
    private PhoenixFishPlaceholder placeholderExpansion;

    // v1.6.2 (Fix #4) — no longer final; re-read on /pf reload so toggling
    // xp-system.enabled / settings.discovery-enabled takes effect immediately.
    private volatile boolean xpEnabled;
    private volatile boolean discoveryEnabled;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        // v1.8.5 — Message file extraction is handled entirely by MessageManager
        // and ContentLanguageManager (auto-extract bundled lang/<code>/messages.yml).
        // No manual saveResource calls here anymore.

        this.messageManager = new MessageManager(this);
        this.contentLanguageManager = new com.phoenix.fish.manager.ContentLanguageManager(this);
        loadPluginResources();

        this.xpEnabled = getConfig().getBoolean("xp-system.enabled", false);
        this.discoveryEnabled = getConfig().getBoolean("settings.discovery-enabled", true);

        this.fishManager = new FishManager(this);
        this.fishManager.loadFish();

        this.craftingManager = new CraftingManager(this);
        this.baitManager = new BaitManager(this);
        this.baitManager.loadBaits();

        this.recordManager = new RecordManager(this);
        this.tournamentManager = new TournamentManager(this);
        this.economyManager = new EconomyManager(this);
        this.skillManager = new SkillManager(this);
        this.achievementManager = new com.phoenix.fish.manager.AchievementManager(this);
        this.marketManager = new com.phoenix.fish.manager.MarketManager(this);
        this.contractManager = new com.phoenix.fish.manager.ContractManager(this);
        this.seasonManager = new com.phoenix.fish.manager.SeasonManager(this);

        this.fishingModeManager = new FishingModeManager(this);

        setupDatabaseSystem();

        this.fishingListener = new FishingListener(this);
        Bukkit.getPluginManager().registerEvents(fishingListener, this);

        this.craftingManager.loadRecipes();

        this.catalogManager = new CatalogManager(this);
        this.catalogManager.init();

        Bukkit.getPluginManager().registerEvents(new CatalogListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SkillListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SellListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AchievementContractListener(this), this);

        registerCommands();

        this.api = new PhoenixFishAPI(this);
        Bukkit.getServicesManager().register(PhoenixFishAPI.class, this.api, this,
                org.bukkit.plugin.ServicePriority.Normal);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.placeholderExpansion = new PhoenixFishPlaceholder(this);
            this.placeholderExpansion.register();
            getLogger().info("PlaceholderAPI hooked successfully! Placeholders: %phoenixfish_*%");
        } else {
            getLogger().info("PlaceholderAPI not installed — placeholder support disabled. "
                    + "Install PlaceholderAPI to use %phoenixfish_*% placeholders.");
        }

        getLogger().info("PhoenixFish başarıyla etkinleştirildi!");
    }

    @Override
    public void onDisable() {
        if (this.api != null) {
            Bukkit.getServicesManager().unregister(PhoenixFishAPI.class, this.api);
        }

        if (placeholderExpansion != null) {
            try {
                placeholderExpansion.unregister();
            } catch (Exception ignored) {
            }
            placeholderExpansion = null;
        }

        if (tournamentManager != null)
            tournamentManager.shutdown();
        if (fishingListener != null)
            fishingListener.shutdown();

        if (cacheManager != null) {
            cacheManager.shutdown();
        }

        if (achievementManager != null) achievementManager.save();
        if (marketManager != null) marketManager.shutdown();
        if (contractManager != null) contractManager.shutdown();
        if (seasonManager != null) seasonManager.shutdown();

        if (cacheManager != null && database != null) {
            String logPrefix = messageManager != null ? messageManager.getPlainMessage("plugin_saving_cache")
                    : "Saving cache...";
            getLogger().info(logPrefix);

            Collection<FishingData> allData = cacheManager.getSavableData();
            if (!allData.isEmpty()) {
                try {
                    database.batchSave(allData).get(10, TimeUnit.SECONDS);
                    if (messageManager != null)
                        getLogger().info(messageManager.getPlainMessage("plugin_cache_saved"));
                } catch (TimeoutException e) {
                    getLogger().severe("Cache save TIMED OUT after 10s — some player data may be lost. "
                            + "Check DB latency or increase pool size.");
                } catch (Exception e) {
                    String msg = messageManager != null ? messageManager.getPlainMessage("plugin_cache_save_failed")
                            : "Cache save FAILED: %error%";
                    getLogger().severe(msg.replace("%error%", e.getMessage()));
                }
            }
        }

        if (recordManager != null) {
            recordManager.flush();
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

        Bukkit.getScheduler().cancelTasks(this);

        instance = null;
    }

    private void loadPluginResources() {
        try {
            saveResource("fish.yml", false);
            saveResource("rods.yml", false);
            saveResource("custom_recipes.yml", false);
            saveResource("baits.yml", false);

            String configuredLang = getConfig().getString("settings.language", "en");
            if (configuredLang != null && !configuredLang.trim().isEmpty()
                    && !configuredLang.equalsIgnoreCase("en")) {
                String fishResource = "fish_" + configuredLang.trim().toLowerCase() + ".yml";
                if (getResource(fishResource) != null) {
                    saveResource(fishResource, false);
                } else {
                    getLogger().info(fishResource + " bulunamadı (jar'da yok) — "
                            + "varsayılan fish.yml kullanılacak. Bu uyarı normal: çeviri "
                            + "dosyası sadece istenen dile sahip PhoenixFish sürümlerinde gelir.");
                }

                for (String prefix : new String[]{"rods_", "baits_", "custom_recipes_"}) {
                    String resource = prefix + configuredLang.trim().toLowerCase() + ".yml";
                    if (getResource(resource) != null) {
                        saveResource(resource, false);
                        getLogger().info("Extracted bundled resource: " + resource);
                    }
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
            getLogger().info("Database not initialized: both xp-system.enabled and "
                    + "settings.discovery-enabled are false. Set either to true to enable persistence.");
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

    /**
     * v1.6.2 (Fix #4) — Lazily initialize the database during /pf reload if the
     * admin just enabled xp-system.enabled or settings.discovery-enabled (which
     * were previously false at startup, leaving database=null).
     *
     * <p>Without this, a server admin who boots with xp-system disabled and
     * later flips the config would see "skills_disabled" forever, because
     * {@link #isXpSystemEnabled()} checks {@code database != null}.</p>
     *
     * <p>This method is idempotent: if DB is already initialized it returns
     * immediately.</p>
     */
    private synchronized void ensureDatabaseForReload() {
        boolean wantXp = getConfig().getBoolean("xp-system.enabled", false);
        boolean wantDiscovery = getConfig().getBoolean("settings.discovery-enabled", true);

        if (!wantXp && !wantDiscovery) {
            // Both disabled — that's a valid state, nothing to do.
            return;
        }

        if (this.database != null && this.cacheManager != null) {
            // Already initialized. Caller will update the volatile flags.
            return;
        }

        // Transition: DB needs to be (re-)initialized.
        getLogger().info("Reload detected persistence flag transition — initializing database now.");
        // Reset stale references first in case database was non-null but cacheManager was null.
        if (database != null) {
            try { database.close(); } catch (Throwable ignored) { }
            database = null;
        }
        cacheManager = null;
        setupDatabaseSystem();
    }

    private void registerCommands() {
        PluginCommand command = getCommand("phoenixfish");
        if (command != null) {
            FishCommand executor = new FishCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(new FishTabCompleter(this));
        } else {
            String msg = messageManager != null ? messageManager.getPlainMessage("plugin_cmd_not_defined")
                    : "Command not defined in plugin.yml";
            getLogger().severe(msg);
        }
    }

    public void playCatchEffects(Player player, CustomFish fish) {
        if (fish == null || player == null)
            return;

        if (getConfig().getBoolean("features.minigame-effects.enabled", true)) {
            String rarityKey = "features.minigame-effects.rarity_" + fish.rarity();
            org.bukkit.configuration.ConfigurationSection effectSec =
                    getConfig().getConfigurationSection(rarityKey);
            if (effectSec != null) {
                String particleStr = effectSec.getString("particle", "none");
                if (!"none".equalsIgnoreCase(particleStr) && !particleStr.isEmpty()) {
                    try {
                        org.bukkit.Particle particle = org.bukkit.Particle.valueOf(particleStr.toUpperCase());
                        int count = effectSec.getInt("particle_count", 10);
                        player.getWorld().spawnParticle(particle, player.getLocation().add(0, 1, 0),
                                count, 0.5, 0.5, 0.5, 0.05);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                String soundStr = effectSec.getString("sound", "");
                if (!soundStr.isEmpty()) {
                    try {
                        org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundStr.toUpperCase());
                        float volume = (float) effectSec.getDouble("sound_volume", 1.0);
                        float pitch = (float) effectSec.getDouble("sound_pitch", 1.0);
                        player.playSound(player, sound, volume, pitch);
                    } catch (IllegalArgumentException ignored) { }
                }
                if (effectSec.getBoolean("shake", false)) {
                    player.setVelocity(player.getVelocity().add(
                            new org.bukkit.util.Vector(0, 0.15, 0)));
                }
                if (effectSec.getBoolean("title", false)) {
                    String titleText = effectSec.getString("title_text", "");
                    if (!titleText.isEmpty()) {
                        Map<String, String> ph = Collections.singletonMap("%fish_name%",
                                PlainTextComponentSerializer.plainText().serialize(fish.name()));
                        Title.Times times = Title.Times.times(
                                Duration.ofMillis(500), Duration.ofMillis(3000),
                                Duration.ofMillis(1000));
                        Title title = Title.title(
                                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                        .deserialize(titleText),
                                messageManager.getMessage(
                                        fish.rarity() >= 5 ? "catch_subtitle_legendary" : "catch_subtitle_epic",
                                        false, ph),
                                times);
                        player.showTitle(title);
                    }
                }
                return;
            }
        }

        if (fish.rarity() >= 4) {
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

    /**
     * v1.6.2 (Fix #4) — Comprehensive reload.
     *
     * <p>Previous reload path had three defects:</p>
     * <ol>
     *   <li>{@code xpEnabled} / {@code discoveryEnabled} were set ONCE in
     *       {@code onEnable()} and never updated. Toggling them in config.yml
     *       + /pf reload silently kept the old values → {@code isXpSystemEnabled()}
     *       kept returning false → {@code /pf skills} replied "skills_disabled".</li>
     *   <li>The four v1.6.0 managers ({@link AchievementManager}, {@link MarketManager},
     *       {@link ContractManager}, {@link SeasonManager}) declared
     *       {@code private final boolean enabled} — only AchievementManager reload
     *       tried (fragile) reflection to mutate it; the other three were never
     *       re-read. Each manager now has a {@code reload()} method that refreshes
     *       its enabled flag AND re-loads definitions/state from the new config.</li>
     *   <li>Database was never re-initialized. An admin who booted with
     *       xp-system disabled and later enabled it saw "still disabled" forever,
     *       because {@code isXpSystemEnabled()} checks {@code database != null}.</li>
     * </ol>
     *
     * <p>Fix: (a) refresh both volatile flags, (b) lazily init DB if needed,
     * (c) call {@code reload()} on every manager, (d) keep the existing async
     * execution context from {@code FishCommand.handleReload}.</p>
     */
    public void reloadAll() {
        long start = System.currentTimeMillis();

        reloadConfig();

        // ─── (a) Refresh the persistence flags from the new config ───
        boolean newDiscoveryEnabled = getConfig().getBoolean("settings.discovery-enabled", true);
        if (newDiscoveryEnabled != this.discoveryEnabled) {
            getLogger().info("settings.discovery-enabled: " + this.discoveryEnabled
                    + " → " + newDiscoveryEnabled);
        }
        boolean newXpEnabled = getConfig().getBoolean("xp-system.enabled", false);
        if (newXpEnabled != this.xpEnabled) {
            getLogger().info("xp-system.enabled: " + this.xpEnabled + " → " + newXpEnabled);
        }

        // ─── (b) Lazily init DB if the admin just enabled persistence ───
        ensureDatabaseForReload();

        // ─── (c) Now safe to update the volatile flags ───
        this.discoveryEnabled = newDiscoveryEnabled;
        this.xpEnabled = newXpEnabled;

        if (messageManager != null) messageManager.reload();
        // v1.8.5 — Reload ALL content language packs (fish/baits/rods/contracts/
        // achievements/skills) so /pf reload (and /pf settings language <code>)
        // pick up language changes immediately.
        if (contentLanguageManager != null) contentLanguageManager.reload();
        if (fishManager != null) fishManager.loadFish();
        if (baitManager != null) baitManager.loadBaits();
        if (fishingListener != null && fishingListener.getItemFixer() != null) {
            fishingListener.getItemFixer().reload();
        }
        if (craftingManager != null) craftingManager.loadRecipes();
        if (catalogManager != null) catalogManager.init();

        // v1.6.2 (Fix #4) — Reload the four v1.6.0 managers properly.
        // Each reload() re-reads its enabled flag from the freshly-loaded config,
        // re-loads definitions/state if newly enabled, and saves old state if
        // newly disabled. No reflection, no stale flags.
        if (achievementManager != null) achievementManager.reload();
        if (marketManager != null) marketManager.reload();
        if (contractManager != null) contractManager.reload();
        if (seasonManager != null) seasonManager.reload();

        // v1.6.2 (Fix #4) — SkillManager.reload() re-reads skill-max-level.
        if (skillManager != null) skillManager.reload();

        if (fishingListener != null && fishingListener.getItemFixer() != null) {
            try {
                java.util.concurrent.atomic.AtomicInteger totalFixed = new java.util.concurrent.atomic.AtomicInteger(0);
                org.bukkit.Bukkit.getScheduler().runTask(this, () -> {
                    for (org.bukkit.entity.Player onlinePlayer : org.bukkit.Bukkit.getOnlinePlayers()) {
                        try {
                            int[] result = fishingListener.getItemFixer().fixInventory(onlinePlayer.getInventory());
                            if (result != null && result.length >= 2) {
                                totalFixed.addAndGet(result[0] + result[1]);
                            }
                        } catch (Throwable ignored) { }
                    }
                    if (totalFixed.get() > 0) {
                        getLogger().info("Inventory fix applied after reload: " + totalFixed.get()
                                + " items updated across online players.");
                    }
                });
            } catch (Throwable ignored) { }
        }

        long elapsed = System.currentTimeMillis() - start;
        getLogger().info("PhoenixFish config reloaded in " + elapsed + "ms.");

        FishingData.invalidateXpCache();
        FishingData.invalidateMaxSkillLevelCache();

        try {
            Bukkit.getPluginManager().callEvent(new PhoenixFishReloadEvent(elapsed));
        } catch (Exception e) {
            getLogger().warning("Failed to dispatch PhoenixFishReloadEvent: " + e.getMessage());
        }
    }

    public static PhoenixFish getInstance() { return instance; }
    public PhoenixFishAPI getApi() { return api; }
    public FishManager getFishManager() { return fishManager; }
    public CraftingManager getCraftingManager() { return craftingManager; }
    public BaitManager getBaitManager() { return baitManager; }
    public IDatabase getDatabase() { return database; }
    public FishingListener getFishingListener() { return fishingListener; }
    public CacheManager getCacheManager() { return cacheManager; }
    public MessageManager getMessageManager() { return messageManager; }

    // v1.8.5 — Content (fish/baits/rods/contracts/achievements/skills) localization layer.
    public com.phoenix.fish.manager.ContentLanguageManager getContentLanguageManager() {
        return contentLanguageManager;
    }
    public CatalogManager getCatalogManager() { return catalogManager; }
    public TournamentManager getTournamentManager() { return tournamentManager; }
    public RecordManager getRecordManager() { return recordManager; }
    public SkillManager getSkillManager() { return skillManager; }
    public EconomyManager getEconomyManager() { return economyManager; }
    public FishingModeManager getFishingModeManager() { return fishingModeManager; }
    public com.phoenix.fish.manager.AchievementManager getAchievementManager() { return achievementManager; }
    public com.phoenix.fish.manager.MarketManager getMarketManager() { return marketManager; }
    public com.phoenix.fish.manager.ContractManager getContractManager() { return contractManager; }
    public com.phoenix.fish.manager.SeasonManager getSeasonManager() { return seasonManager; }

    public boolean isXpSystemEnabled() {
        return xpEnabled && database != null;
    }

    public boolean isDiscoveryEnabled() {
        return discoveryEnabled && database != null;
    }

    // =====================================================================
    //  v1.8.5 — Config auto-saver
    //  Used by /pf settings to write back to config.yml and trigger a
    //  full reloadAll() so the change takes effect immediately.
    // =====================================================================

    /**
     * v1.8.5 — Set a config value, persist it to disk, then trigger a
     * full reloadAll() so all managers pick up the new value.
     *
     * <p>This is the single entry point used by {@code /pf settings}.
     * It mutates the in-memory config, writes it back to {@code config.yml},
     * and reloads every manager — no server restart required.</p>
     *
     * @param path dotted config path (e.g. "language", "features.skill-stamina.enabled")
     * @param value the new value (String / Boolean / Integer / Double)
     */
    public synchronized void setConfigValueAndReload(String path, Object value) {
        getConfig().set(path, value);
        try {
            saveConfig();
            getLogger().info("Config updated: " + path + " = " + value);
        } catch (Exception e) {
            getLogger().severe("Failed to save config.yml after updating " + path + ": " + e.getMessage());
            return;
        }
        // Trigger the full reload chain — same as /pf reload.
        reloadAll();
    }

    /**
     * v1.8.5 — Set a config value AND persist it, but DO NOT trigger a
     * reload. Used when the caller wants to batch multiple changes and
     * trigger the reload manually afterward.
     */
    public synchronized void setConfigValue(String path, Object value) {
        getConfig().set(path, value);
    }

    /**
     * v1.8.5 — Persist the current in-memory config to disk. Called
     * after one or more {@link #setConfigValue} calls.
     */
    public synchronized void saveConfigNow() {
        saveConfig();
    }
}
