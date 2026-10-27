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
    private CatalogManager catalogManager;
    private TournamentManager tournamentManager;
    private RecordManager recordManager;
    private SkillManager skillManager;
    private EconomyManager economyManager;
    private FishingModeManager fishingModeManager;
    // v1.6.0 — Yeni manager
    private com.phoenix.fish.manager.AchievementManager achievementManager;
    private com.phoenix.fish.manager.MarketManager marketManager;
    private com.phoenix.fish.manager.ContractManager contractManager;
    private com.phoenix.fish.manager.SeasonManager seasonManager;

    private PhoenixFishAPI api;

    private PhoenixFishPlaceholder placeholderExpansion;

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

        // v1.3.7 — ÖNEMLİ: CraftingManager ve BaitManager constructor'larıBurada çağrılır,
        // ama loadRecipes()/loadBaits() FishingListener oluşturulduktan SONRA çağrılmalı.
        // Çünkü createItem() metodu ItemFixer'dan rod display-name'ini okuyor.
        // Eğer loadRecipes() burada çağrılırsa, ItemFixer henüz null olur → "Olta" görünür.
        this.craftingManager = new CraftingManager(this);

        this.baitManager = new BaitManager(this);
        this.baitManager.loadBaits();

        this.recordManager = new RecordManager(this);
        this.tournamentManager = new TournamentManager(this);
        this.economyManager = new EconomyManager(this);
        this.skillManager = new SkillManager(this);
        // v1.6.0 — AchievementManager + MarketManager
        this.achievementManager = new com.phoenix.fish.manager.AchievementManager(this);
        this.marketManager = new com.phoenix.fish.manager.MarketManager(this);
        this.contractManager = new com.phoenix.fish.manager.ContractManager(this);
        this.seasonManager = new com.phoenix.fish.manager.SeasonManager(this);

        this.fishingModeManager = new FishingModeManager(this);

        setupDatabaseSystem();

        this.fishingListener = new FishingListener(this);
        Bukkit.getPluginManager().registerEvents(fishingListener, this);

        // v1.3.7 — Şimdi FishingListener ve ItemFixer hazır, tarifleri yükleyebiliriz.
        // createItem() artık ItemFixer'a erişebilir → rod ingredient'larına
        // gerçek display-name set edilir (örn. "Cosmic Rod" / "宇宙鱼竿").
        this.craftingManager.loadRecipes();

        this.catalogManager = new CatalogManager(this);
        this.catalogManager.init();

        Bukkit.getPluginManager().registerEvents(new CatalogListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SkillListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SellListener(this), this);

        // v1.6.1 — Achievement & Contract GUI'lerini item hırsızlığına karşı korur.
        // Önceki sürümde bu GUI'ler Bukkit.createInventory(null, ...) ile açıldığı için
        // oyuncular başarılmış PAPER/GOLD_INGOT gibi item'ları fare ile çekebiliyordu.
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

        // Disable order: cancel scheduled tasks FIRST so they don't enqueue more DB work
        // while we're draining the queue. Then save cache, then flush records, then close DB.
        if (cacheManager != null) {
            cacheManager.shutdown();
        }

        // v1.6.0 — AchievementManager + MarketManager save
        if (achievementManager != null) {
            achievementManager.save();
        }
        if (marketManager != null) {
            marketManager.shutdown();
        }
        if (contractManager != null) {
            contractManager.shutdown();
        }
        if (seasonManager != null) {
            seasonManager.shutdown();
        }

        if (cacheManager != null && database != null) {
            String logPrefix = messageManager != null ? messageManager.getPlainMessage("plugin_saving_cache")
                    : "Saving cache...";
            getLogger().info(logPrefix);

            Collection<FishingData> allData = cacheManager.getSavableData();
            if (!allData.isEmpty()) {
                try {
                    // C-7/C-8 FIX: with rethrow-on-failure in DB managers, batchSave().get() now throws
                    // ExecutionException on save failure — we log it loudly instead of claiming success.
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

        // C-11 FIX: flush records synchronously so personal bests / server records set just before
        // shutdown are not lost in a pending async save (RecordManager.saveFile used ForkJoinPool commonPool).
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

        // M-15 FIX: explicitly cancel every plugin task (fixall runnables, ContainerFixTask, etc.)
        // so none runs one extra tick into shutdown touching the now-closed DB or nulled instance.
        Bukkit.getScheduler().cancelTasks(this);

        instance = null;
    }

    private void loadPluginResources() {
        try {
            saveResource("fish.yml", false);
            saveResource("rods.yml", false);
            saveResource("custom_recipes.yml", false);
            saveResource("baits.yml", false);
            saveResource("contract_templates.yml", false);

            // v1.3.5 — Dinamik dil desteği: her dil için fish_<dil>.yml dosyasını
            // jar'da ara ve varsa çıkar. Hardcoded "tr" kontrolü kalktı.
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

                // v1.3.5 — Diğer dil bazlı dosyalar (rods_, baits_, custom_recipes_)
                // Eğer jar'da varsa çıkar; yoksa sessizce geç.
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
        // M-26 FIX: clearer diagnostic when DB is intentionally skipped.
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

        // v1.6.0 — Config-driven görsel efektler (features.minigame-effects.enabled ise)
        if (getConfig().getBoolean("features.minigame-effects.enabled", true)) {
            String rarityKey = "features.minigame-effects.rarity_" + fish.rarity();
            org.bukkit.configuration.ConfigurationSection effectSec =
                    getConfig().getConfigurationSection(rarityKey);
            if (effectSec != null) {
                // Particle
                String particleStr = effectSec.getString("particle", "none");
                if (!"none".equalsIgnoreCase(particleStr) && !particleStr.isEmpty()) {
                    try {
                        org.bukkit.Particle particle = org.bukkit.Particle.valueOf(particleStr.toUpperCase());
                        int count = effectSec.getInt("particle_count", 10);
                        player.getWorld().spawnParticle(particle, player.getLocation().add(0, 1, 0),
                                count, 0.5, 0.5, 0.5, 0.05);
                    } catch (IllegalArgumentException ignored) {
                        // Invalid particle name — skip
                    }
                }
                // Sound
                String soundStr = effectSec.getString("sound", "");
                if (!soundStr.isEmpty()) {
                    try {
                        org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundStr.toUpperCase());
                        float volume = (float) effectSec.getDouble("sound_volume", 1.0);
                        float pitch = (float) effectSec.getDouble("sound_pitch", 1.0);
                        player.playSound(player, sound, volume, pitch);
                    } catch (IllegalArgumentException ignored) { }
                }
                // Shake (knockback effect)
                if (effectSec.getBoolean("shake", false)) {
                    player.setVelocity(player.getVelocity().add(
                            new org.bukkit.util.Vector(0, 0.15, 0)));
                }
                // Title
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
                return;  // Config-driven efektler uygulandı, eski kodu atla
            }
        }

        // Eski davranış (config-driven efektler kapalıysa veya rarity için section yoksa)
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

    public void reloadAll() {
        long start = System.currentTimeMillis();

        reloadConfig();

        boolean newDiscoveryEnabled = getConfig().getBoolean("settings.discovery-enabled", true);
        if (newDiscoveryEnabled != this.discoveryEnabled) {
            getLogger().warning(
                    "settings.discovery-enabled changed from " + this.discoveryEnabled
                            + " to " + newDiscoveryEnabled
                            + " but this requires a server restart to take effect.");
        }

        if (messageManager != null) {
            messageManager.reload();
        }

        if (fishManager != null) {
            fishManager.loadFish();
        }

        if (baitManager != null) {
            baitManager.loadBaits();
        }

        if (fishingListener != null && fishingListener.getItemFixer() != null) {
            fishingListener.getItemFixer().reload();
        }

        if (craftingManager != null) {
            craftingManager.loadRecipes();
        }

        if (catalogManager != null) {
            catalogManager.init();
        }

        // v1.6.0 — AchievementManager + MarketManager reload
        if (achievementManager != null) {
            // Definitions re-read from config
            try {
                achievementManager.save();
                var field = achievementManager.getClass().getDeclaredField("enabled");
                field.setAccessible(true);
                field.setBoolean(achievementManager, getConfig().getBoolean("features.achievements.enabled", false));
            } catch (Throwable ignored) { }
        }
        if (marketManager != null) {
            try {
                marketManager.save();
                marketManager.applyRecovery();
            } catch (Throwable ignored) { }
        }

        // v1.7.0 — ContractManager reload (re-reads contract_templates.yml)
        if (contractManager != null) {
            contractManager.reload();
        }

        // v1.3.7 — Reload sonrası envanter fix.
        // Dil değiştiyse (örn. en → zh_cn), oyuncuların envanterindeki eski olta/yemler
        // hâlâ ESKİ dildeki display-name'i taşıyor. Yeni açılan /pcraft menüsü güncel veriyi
        // gösterir, ama eldeki eşyalar eski adıyla kalır. Bu fix, reload sonrası tüm online
        // oyuncuların envanterini tarayıp eski olta/yemleri yeni dildeki display-name'le günceller.
        //
        // v1.4.0 (C-1 fix) — Envanter mutation Bukkit API'sinde thread-safe DEĞİL.
        // reloadAll() async thread'te çağrıldığı için (FishCommand.handleReload → runTaskAsynchronously),
        // envanter fix'i main thread'e taşımalı. Yoksa ConcurrentModificationException
        // veya envanter eşyalarının çoğalması/kaybolması riski var.
        if (fishingListener != null && fishingListener.getItemFixer() != null) {
            // Envanter mutation'ı main thread'e atla. Bukkit.getScheduler().runTask çağrısı
            // async context'ten çağrılabilir, lambda bir sonraki main-thread tick'te çalışır.
            try {
                java.util.concurrent.atomic.AtomicInteger totalFixed = new java.util.concurrent.atomic.AtomicInteger(0);
                org.bukkit.Bukkit.getScheduler().runTask(this, () -> {
                    for (org.bukkit.entity.Player onlinePlayer : org.bukkit.Bukkit.getOnlinePlayers()) {
                        try {
                            int[] result = fishingListener.getItemFixer().fixInventory(onlinePlayer.getInventory());
                            if (result != null && result.length >= 2) {
                                totalFixed.addAndGet(result[0] + result[1]);
                            }
                        } catch (Throwable ignored) {
                            // Bir oyuncunun envanteri fix edilemezse sessizce geç.
                        }
                    }
                    if (totalFixed.get() > 0) {
                        getLogger().info("Inventory fix applied after reload: " + totalFixed.get()
                                + " items updated across online players.");
                    }
                });
            } catch (Throwable ignored) {
                // Scheduler meşgulse veya plugin disable ise sessizce geç.
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        getLogger().info("PhoenixFish config reloaded in " + elapsed + "ms.");

        // Faz 0.9.0: invalidate XP formula cache on reload
        FishingData.invalidateXpCache();
        // v1.6.1 (bug #3 fix): invalidate skill-max-level cache so config changes
        // to xp-system.skill-max-level take effect on /pf reload.
        FishingData.invalidateMaxSkillLevelCache();

        // Notify external consumers (e.g. PhoenixCraft) — Faz 1 / 1.3.2 (Madde 2)
        // They should invalidate caches and close any open GUIs showing stale data.
        try {
            Bukkit.getPluginManager().callEvent(new PhoenixFishReloadEvent(elapsed));
        } catch (Exception e) {
            getLogger().warning("Failed to dispatch PhoenixFishReloadEvent: " + e.getMessage());
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

    public FishingModeManager getFishingModeManager() {
        return fishingModeManager;
    }

    // v1.6.0 — AchievementManager getter
    public com.phoenix.fish.manager.AchievementManager getAchievementManager() {
        return achievementManager;
    }
    // v1.6.0 — MarketManager getter
    public com.phoenix.fish.manager.MarketManager getMarketManager() {
        return marketManager;
    }
    // v1.6.0 — ContractManager getter
    public com.phoenix.fish.manager.ContractManager getContractManager() {
        return contractManager;
    }
    // v1.6.0 — SeasonManager getter
    public com.phoenix.fish.manager.SeasonManager getSeasonManager() {
        return seasonManager;
    }

    public boolean isXpSystemEnabled() {
        return xpEnabled && database != null;
    }

    public boolean isDiscoveryEnabled() {
        return discoveryEnabled && database != null;
    }
}
