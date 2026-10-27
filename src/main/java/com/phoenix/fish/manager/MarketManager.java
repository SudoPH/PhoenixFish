package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.6.0 — Dynamic Fish Pricing (Arz-Talep sistemi).
 *
 * <p>Her balığın fiyatı, satış miktarına göre dinamik olarak değişir:
 * <ul>
 *   <li>Her satışta fiyat %3 düşer (decay-per-sale: 0.97)</li>
 *   <li>Saatte %1 toparlanır (recovery-per-hour: 0.01)</li>
 *   <li>En fazla %70 düşer (min-multiplier: 0.3)</li>
 *   <li>En fazla %50 yükselir (max-multiplier: 1.5)</li>
 * </ul>
 *
 * <p>Fiyat çarpanları fish_market.yml'de saklanır. EconomyManager
 * calculatePrice() metoduna çarpan uygular.</p>
 *
 * <p>Config: features.dynamic-pricing.enabled</p>
 */
public class MarketManager {

    private final PhoenixFish plugin;
    private final boolean enabled;

    /** fishId → current price multiplier (1.0 = normal). */
    private final Map<String, Double> multipliers = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;
    private long lastRecoveryTime;

    public MarketManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("features.dynamic-pricing.enabled", false);
        if (enabled) {
            load();
            lastRecoveryTime = System.currentTimeMillis();
            plugin.getLogger().info("MarketManager enabled — dynamic pricing active.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    // =====================================================================
    //  Persistence
    // =====================================================================

    private File getFile() {
        return new File(plugin.getDataFolder(), "fish_market.yml");
    }

    private void load() {
        File file = getFile();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = cfg.getConfigurationSection("multipliers");
        if (sec != null) {
            for (String fishId : sec.getKeys(false)) {
                multipliers.put(fishId.toLowerCase(), sec.getDouble(fishId, 1.0));
            }
        }
        lastRecoveryTime = cfg.getLong("last-recovery", System.currentTimeMillis());
    }

    public synchronized void save() {
        if (!enabled || !dirty) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<String, Double> entry : multipliers.entrySet()) {
            cfg.set("multipliers." + entry.getKey(), entry.getValue());
        }
        cfg.set("last-recovery", lastRecoveryTime);
        try {
            cfg.save(getFile());
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save fish_market.yml: " + e.getMessage());
        }
    }

    // =====================================================================
    //  Price Multiplier Logic
    // =====================================================================

    /**
     * Belirli bir balık için güncel fiyat çarpanını döndürür.
     * 1.0 = normal fiyat, 0.3 = %70 düşük, 1.5 = %50 yüksek.
     */
    public double getMultiplier(String fishId) {
        if (!enabled || fishId == null) return 1.0;
        return multipliers.getOrDefault(fishId.toLowerCase(), 1.0);
    }

    /**
     * Satış sonrası çarpanı düşür (arz arttı → fiyat düşer).
     */
    public void applyDecay(String fishId, int amountSold) {
        if (!enabled || fishId == null) return;

        // Recovery uygula (saatler geçtiyse)
        applyRecovery();

        double decayRate = plugin.getConfig().getDouble("features.dynamic-pricing.decay-per-sale", 0.97);
        double minMult = plugin.getConfig().getDouble("features.dynamic-pricing.min-multiplier", 0.3);

        String key = fishId.toLowerCase();
        double current = multipliers.getOrDefault(key, 1.0);

        // Her satış için decay uygula (amount kadar)
        for (int i = 0; i < amountSold; i++) {
            current *= decayRate;
        }
        current = Math.max(minMult, current);
        multipliers.put(key, current);
        dirty = true;
    }

    /**
     * Saatlik toparlanma — süre geçtikçe fiyatlar normale döner.
     */
    public void applyRecovery() {
        if (!enabled) return;

        long now = System.currentTimeMillis();
        long hoursPassed = (now - lastRecoveryTime) / (60 * 60 * 1000);
        if (hoursPassed <= 0) return;

        double recoveryRate = plugin.getConfig().getDouble("features.dynamic-pricing.recovery-per-hour", 0.01);
        double maxMult = plugin.getConfig().getDouble("features.dynamic-pricing.max-multiplier", 1.5);

        for (Map.Entry<String, Double> entry : multipliers.entrySet()) {
            double current = entry.getValue();
            for (long h = 0; h < hoursPassed; h++) {
                // 1.0'dan uzaklaşıyorsa normale çek
                if (current < 1.0) {
                    current += recoveryRate;
                    if (current > 1.0) current = 1.0;
                } else if (current > 1.0) {
                    current -= recoveryRate;
                    if (current < 1.0) current = 1.0;
                }
            }
            entry.setValue(Math.min(maxMult, Math.max(0.1, current)));
        }
        lastRecoveryTime = now;
        dirty = true;
    }

    public void shutdown() {
        save();
    }
}
