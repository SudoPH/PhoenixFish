package com.phoenix.fish.data;

import com.phoenix.fish.PhoenixFish;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FishingData {

    private final UUID uuid;
    private volatile int currentXp;
    private volatile int currentLevel;
    private volatile int totalXp;

    private volatile int craftingXp;
    private volatile int craftingLevel;
    private volatile int totalCraftingXp;

    private final Set<String> discoveredFish;

    private volatile int skillPoints;
    private volatile int fastCatcher;
    private volatile int masterHunter;
    private volatile int doubleCatch;
    private volatile int luckyBait;
    // v1.6.0 — Yeni skill'ler
    private volatile int bigCatch;
    private volatile int luckyCast;
    private volatile int stamina;

    private double balance;

    // v1.6.0 — İstatistik alanları
    private volatile int totalCatches;
    private volatile double biggestWeight;
    private volatile String biggestFishName;
    private volatile int rarestCatchRarity;
    private volatile String rarestCatchName;
    private volatile double totalEarnings;
    private volatile int currentStreak;
    private volatile int bestStreak;
    private volatile int mutationCount;

    private volatile boolean loaded = false;

    public FishingData() {
        this(null, 0, 1, 0, null, 0, 0, 0, 0, 0, 0.0, 0, 1, 0);
    }

    public FishingData(UUID uuid, int currentXp, int currentLevel, int totalXp, Set<String> discoveredFish,
            int skillPoints, int fastCatcher, int masterHunter, int doubleCatch, int luckyBait, double balance,
            int craftingXp, int craftingLevel, int totalCraftingXp) {
        this.uuid = uuid;
        this.currentXp = Math.max(0, currentXp);
        this.currentLevel = Math.max(1, currentLevel);
        this.totalXp = Math.max(0, totalXp);

        this.craftingXp = Math.max(0, craftingXp);
        this.craftingLevel = Math.max(1, craftingLevel);
        this.totalCraftingXp = Math.max(0, totalCraftingXp);

        this.discoveredFish = ConcurrentHashMap.newKeySet();
        if (discoveredFish != null) {
            this.discoveredFish.addAll(discoveredFish);
        }

        this.skillPoints = Math.max(0, skillPoints);
        this.fastCatcher = clampSkill(fastCatcher);
        this.masterHunter = clampSkill(masterHunter);
        this.doubleCatch = clampSkill(doubleCatch);
        this.luckyBait = clampSkill(luckyBait);
        this.balance = Math.max(0.0, balance);
    }

    /**
     * v1.4.0 — Skill seviyesini config'den okunan max değere göre clamp'ler.
     * Eskiden hardcoded 5 idi, artık config'teki `xp-system.skill-max-level`
     * değerini kullanır (SkillManager tarafından setMaxSkillLevel metodu ile ayarlanır).
     * Config 10 olsa bile 5'te takılı kalıyordu → oyuncu skill puanı kaybediyordu.
     *
     * v1.6.1 (bug #3 fix) — Artık config değerini LAZILY okur. Eskiden setMaxSkillLevel()
     * yalnızca SkillManager.openMenu() içinden çağrılıyordu; bu yüzden DB'den yüklenen
     * skill değerleri (constructor + setter clampSkill çağrıları) maxSkillLevel=5
     * default'u ile clamp'lanıyordu. Şimdi: getInstance() hazır olur olmaz config
     * okunuyor, cachedConfigMaxSkillLevel static cache'inde tutuluyor. Reload'da
     * invalidateMaxSkillLevelCache() ile cache temizlenir.
     */
    private int clampSkill(int level) {
        int max = (maxSkillLevel > 0) ? maxSkillLevel : getCachedConfigMaxSkillLevel();
        return Math.min(max, Math.max(0, level));
    }

    /** v1.4.0 — SkillManager tarafından set'lenen config-driven max skill level. */
    private volatile int maxSkillLevel = 5;

    /**
     * v1.6.1 (bug #3 fix) — Tüm FishingData örnekleri tarafından paylaşılan config cache.
     * Config bir kez okunur, her clampSkill çağrısında tekrar okunmaz. Reload'da
     * invalidateMaxSkillLevelCache() çağrılarak temizlenir.
     */
    private static volatile int cachedConfigMaxSkillLevel = -1;

    private static int getCachedConfigMaxSkillLevel() {
        if (cachedConfigMaxSkillLevel > 0) return cachedConfigMaxSkillLevel;
        PhoenixFish plugin = PhoenixFish.getInstance();
        if (plugin == null || plugin.getConfig() == null) {
            // Plugin henüz init olmadı — geçici olarak default 5 döndür.
            // Bu durum yalnızca test/early-init sırasında olabilir.
            return 5;
        }
        int configVal = plugin.getConfig().getInt("xp-system.skill-max-level", 5);
        cachedConfigMaxSkillLevel = Math.max(1, Math.min(10, configVal));
        return cachedConfigMaxSkillLevel;
    }

    /**
     * v1.6.1 (bug #3 fix) — Reload sonrası config değişikliğinin etkili olması için
     * cache'i temizler. PhoenixFish.reloadAll() tarafından çağrılır.
     */
    public static void invalidateMaxSkillLevelCache() {
        cachedConfigMaxSkillLevel = -1;
    }

    /**
     * SkillManager plugin init sırasında bunu çağırır — config'teki
     * xp-system.skill-max-level değerini FishingData'ya iletir.
     *
     * v1.6.1 (bug #3 fix) — Aynı zamanda static cache'i de günceller, böylece
     * henüz setMaxSkillLevel çağrılmamış olan diğer FishingData örnekleri de
     * doğru max değerini kullanır.
     */
    public void setMaxSkillLevel(int max) {
        int clamped = Math.max(1, Math.min(10, max));
        this.maxSkillLevel = clamped;
        cachedConfigMaxSkillLevel = clamped;
    }

    public int getMaxSkillLevel() {
        return maxSkillLevel;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void markLoaded() {
        this.loaded = true;
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getCurrentXp() {
        return currentXp;
    }

    public int getCurrentLevel() {
        return currentLevel;
    }

    public int getTotalXp() {
        return totalXp;
    }

    public int getCraftingXp() {
        return craftingXp;
    }

    public int getCraftingLevel() {
        return craftingLevel;
    }

    public int getTotalCraftingXp() {
        return totalCraftingXp;
    }

    public Set<String> getDiscoveredFish() {
        return Collections.unmodifiableSet(discoveredFish);
    }

    public boolean hasDiscovered(String fishId) {
        return discoveredFish.contains(fishId);
    }

    public boolean discoverFish(String fishId) {
        return discoveredFish.add(fishId);
    }

    public int getSkillPoints() {
        return skillPoints;
    }

    public int getFastCatcher() {
        return fastCatcher;
    }

    public int getMasterHunter() {
        return masterHunter;
    }

    public int getDoubleCatch() {
        return doubleCatch;
    }

    public int getLuckyBait() {
        return luckyBait;
    }

    public void setCurrentXp(int currentXp) {
        this.currentXp = Math.max(0, currentXp);
    }

    public void setCurrentLevel(int currentLevel) {
        this.currentLevel = Math.max(1, currentLevel);
    }

    public void setTotalXp(int totalXp) {
        this.totalXp = Math.max(0, totalXp);
    }

    public void setCraftingXp(int craftingXp) {
        this.craftingXp = Math.max(0, craftingXp);
    }

    public void setCraftingLevel(int craftingLevel) {
        this.craftingLevel = Math.max(1, craftingLevel);
    }

    public void setTotalCraftingXp(int totalCraftingXp) {
        this.totalCraftingXp = Math.max(0, totalCraftingXp);
    }

    public void setSkillPoints(int skillPoints) {
        this.skillPoints = Math.max(0, skillPoints);
    }

    /**
     * v1.5.0 (F-4) — Skill tree re-spec.
     * Tüm skill'leri 0'a sıfırlar, skill puanlarını geri verir (refunds).
     * Skill puanı = (fastCatcher + masterHunter + doubleCatch + luckyBait).
     */
    public int respecAllSkills() {
        int refunded = this.fastCatcher + this.masterHunter
                + this.doubleCatch + this.luckyBait;
        this.fastCatcher = 0;
        this.masterHunter = 0;
        this.doubleCatch = 0;
        this.luckyBait = 0;
        this.skillPoints = Math.max(0, this.skillPoints + refunded);
        return refunded;
    }

    public void setFastCatcher(int level) {
        this.fastCatcher = clampSkill(level);
    }

    public void setMasterHunter(int level) {
        this.masterHunter = clampSkill(level);
    }

    public void setDoubleCatch(int level) {
        this.doubleCatch = clampSkill(level);
    }

    public void setLuckyBait(int level) {
        this.luckyBait = clampSkill(level);
    }

    // v1.6.0 — Yeni skill getter/setter'ları
    public int getBigCatch() { return bigCatch; }
    public void setBigCatch(int level) { this.bigCatch = clampSkill(level); }
    public int getLuckyCast() { return luckyCast; }
    public void setLuckyCast(int level) { this.luckyCast = clampSkill(level); }
    public int getStamina() { return stamina; }
    public void setStamina(int level) { this.stamina = clampSkill(level); }

    // v1.6.0 — İstatistik getter/setter'ları
    public int getTotalCatches() { return totalCatches; }
    public void setTotalCatches(int v) { this.totalCatches = Math.max(0, v); }
    public double getBiggestWeight() { return biggestWeight; }
    public void setBiggestWeight(double v) { this.biggestWeight = Math.max(0, v); }
    public String getBiggestFishName() { return biggestFishName; }
    public void setBiggestFishName(String v) { this.biggestFishName = v; }
    public int getRarestCatchRarity() { return rarestCatchRarity; }
    public void setRarestCatchRarity(int v) { this.rarestCatchRarity = Math.max(0, v); }
    public String getRarestCatchName() { return rarestCatchName; }
    public void setRarestCatchName(String v) { this.rarestCatchName = v; }
    public double getTotalEarnings() { return totalEarnings; }
    public void setTotalEarnings(double v) { this.totalEarnings = Math.max(0, v); }
    public int getCurrentStreak() { return currentStreak; }
    public void setCurrentStreak(int v) { this.currentStreak = Math.max(0, v); }
    public int getBestStreak() { return bestStreak; }
    public void setBestStreak(int v) { this.bestStreak = Math.max(0, v); }
    public int getMutationCount() { return mutationCount; }
    public void setMutationCount(int v) { this.mutationCount = Math.max(0, v); }

    public synchronized double getBalance() {
        return balance;
    }

    public synchronized void setBalance(double balance) {
        this.balance = Math.max(0.0, balance);
    }

    public synchronized void addBalance(double amount) {
        if (amount > 0)
            this.balance += amount;
    }

    public synchronized boolean withdrawBalance(double amount) {
        if (amount > 0 && this.balance >= amount) {
            this.balance -= amount;
            return true;
        }
        return false;
    }

    public synchronized void addXp(int amount) {
        if (amount <= 0)
            return;
        this.currentXp += amount;
        this.totalXp += amount;
    }

    public synchronized boolean checkLevelUp() {
        int requiredXp = this.currentLevel * 100;
        if (this.currentXp >= requiredXp) {
            this.currentXp -= requiredXp;
            this.currentLevel++;
            this.skillPoints++;
            return true;
        }
        return false;
    }

    public synchronized void addCraftingXp(int amount) {
        if (amount <= 0)
            return;
        // Faz 0.9.0: apply global XP multiplier from config
        PhoenixFish plugin = PhoenixFish.getInstance();
        if (plugin != null) {
            double globalMult = plugin.getConfig().getDouble(
                    "xp-system.crafting-xp.global-multiplier", 1.0);
            if (globalMult != 1.0) {
                amount = (int) Math.round(amount * globalMult);
            }
        }
        this.craftingXp += amount;
        this.totalCraftingXp += amount;

        // Faz 0.9.0: cap XP at max level (prevent infinite accumulation)
        if (this.craftingLevel >= 100) {
            this.craftingXp = 0;
        }
    }

    public synchronized boolean checkCraftingLevelUp() {
        // Faz 0.9.0: Exponential XP formula (config-driven)
        // Formula: base × level^exponent × tierMultiplier
        // Falls back to linear (level × 100) if config not loaded
        int requiredXp = getCraftingXpRequired();
        if (this.craftingXp >= requiredXp) {
            this.craftingXp -= requiredXp;
            this.craftingLevel++;
            return true;
        }
        return false;
    }

    /**
     * Faz 0.9.0: Calculate XP required for next crafting level.
     * Uses exponential formula: base × level^exponent × tierMultiplier
     * Falls back to linear (level × 100) if PhoenixFish instance is null.
     *
     * Config values are cached after first read and invalidated on reload.
     */
    private static volatile double cachedBase = -1;
    private static volatile double cachedExponent = -1;
    private static volatile double cachedGlobalMult = -1;
    private static volatile Map<String, Double> cachedTierMultipliers = null;

    public static void invalidateXpCache() {
        cachedBase = -1;
        cachedExponent = -1;
        cachedGlobalMult = -1;
        cachedTierMultipliers = null;
    }

    private void loadXpConfigCache() {
        if (cachedBase >= 0) return;
        PhoenixFish plugin = PhoenixFish.getInstance();
        if (plugin == null) return;

        cachedBase = plugin.getConfig().getDouble("xp-system.crafting-xp.base", 50.0);
        cachedExponent = plugin.getConfig().getDouble("xp-system.crafting-xp.exponent", 1.5);
        cachedGlobalMult = plugin.getConfig().getDouble("xp-system.crafting-xp.global-multiplier", 1.0);

        cachedTierMultipliers = new java.util.concurrent.ConcurrentHashMap<>();
        var tierMultipliers = plugin.getConfig().getConfigurationSection(
                "xp-system.crafting-xp.tier-multipliers");
        if (tierMultipliers != null) {
            for (String key : tierMultipliers.getKeys(false)) {
                cachedTierMultipliers.put(key, tierMultipliers.getDouble(key, 1.0));
            }
        }
    }

    public int getCraftingXpRequired() {
        PhoenixFish plugin = PhoenixFish.getInstance();
        if (plugin == null) return this.craftingLevel * 100;

        loadXpConfigCache();

        double base = cachedBase >= 0 ? cachedBase : 50.0;
        double exponent = cachedExponent >= 0 ? cachedExponent : 1.5;
        double globalMult = cachedGlobalMult >= 0 ? cachedGlobalMult : 1.0;

        // Find tier multiplier
        double tierMult = 1.0;
        if (cachedTierMultipliers != null) {
            for (Map.Entry<String, Double> entry : cachedTierMultipliers.entrySet()) {
                String[] parts = entry.getKey().split("-");
                if (parts.length == 2) {
                    try {
                        int min = Integer.parseInt(parts[0].trim());
                        int max = Integer.parseInt(parts[1].trim());
                        if (this.craftingLevel >= min && this.craftingLevel <= max) {
                            tierMult = entry.getValue();
                            break;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        double required = base * Math.pow(this.craftingLevel, exponent) * tierMult * globalMult;
        return Math.max(1, (int) Math.round(required));
    }

    public synchronized int checkAllLevelUps() {
        int leveled = 0;
        // v1.8.0 — config-driven fishing XP curve (was hardcoded level × 100).
        // Formula: requiredXp = base × level^exponent
        // Defaults (base=100, exp=1.0) preserve the old behavior.
        PhoenixFish plugin = PhoenixFish.getInstance();
        double base = (plugin != null)
                ? plugin.getConfig().getDouble("xp-system.fishing-xp.base", 100.0)
                : 100.0;
        double exponent = (plugin != null)
                ? plugin.getConfig().getDouble("xp-system.fishing-xp.exponent", 1.0)
                : 1.0;
        while (leveled < 1000) {
            int required = (int) Math.round(base * Math.pow(this.currentLevel, exponent));
            if (required < 1) required = 1;
            if (this.currentXp < required) break;
            this.currentXp -= required;
            this.currentLevel++;
            this.skillPoints++;
            leveled++;
        }
        return leveled;
    }

    public synchronized int checkAllCraftingLevelUps() {
        int leveled = 0;
        while (leveled < 1000) {
            int req = getCraftingXpRequired();
            if (this.craftingXp < req) break;
            this.craftingXp -= req;
            this.craftingLevel++;
            leveled++;
        }
        return leveled;
    }

    public synchronized void mergeTransientFrom(FishingData temp) {
        if (temp == null)
            return;

        if (temp.currentXp > 0) {
            this.currentXp += temp.currentXp;
            this.totalXp += temp.totalXp;
        }
        if (temp.craftingXp > 0) {
            this.craftingXp += temp.craftingXp;
            this.totalCraftingXp += temp.totalCraftingXp;
        }
        if (temp.skillPoints > 0) {
            this.skillPoints = Math.max(0, this.skillPoints + temp.skillPoints);
        }
        if (temp.fastCatcher > 0)
            this.fastCatcher = clampSkill(this.fastCatcher + temp.fastCatcher);
        if (temp.masterHunter > 0)
            this.masterHunter = clampSkill(this.masterHunter + temp.masterHunter);
        if (temp.doubleCatch > 0)
            this.doubleCatch = clampSkill(this.doubleCatch + temp.doubleCatch);
        if (temp.luckyBait > 0)
            this.luckyBait = clampSkill(this.luckyBait + temp.luckyBait);
        if (temp.balance > 0)
            this.balance += temp.balance;
        if (!temp.discoveredFish.isEmpty()) {
            this.discoveredFish.addAll(temp.discoveredFish);
        }
    }

    public synchronized boolean tryUpgradeSkill(String skill, int maxLevel) {
        if (skill == null || skillPoints <= 0)
            return false;
        switch (skill) {
            case "fast":
                if (fastCatcher >= maxLevel)
                    return false;
                fastCatcher = clampSkill(fastCatcher + 1);
                break;
            case "hunter":
                if (masterHunter >= maxLevel)
                    return false;
                masterHunter = clampSkill(masterHunter + 1);
                break;
            case "double":
                if (doubleCatch >= maxLevel)
                    return false;
                doubleCatch = clampSkill(doubleCatch + 1);
                break;
            case "bait":
                if (luckyBait >= maxLevel)
                    return false;
                luckyBait = clampSkill(luckyBait + 1);
                break;
            // v1.6.0 — Yeni skill'ler
            case "big":
                if (bigCatch >= maxLevel)
                    return false;
                bigCatch = clampSkill(bigCatch + 1);
                break;
            case "lucky":
                if (luckyCast >= maxLevel)
                    return false;
                luckyCast = clampSkill(luckyCast + 1);
                break;
            case "stamina":
                if (stamina >= maxLevel)
                    return false;
                stamina = clampSkill(stamina + 1);
                break;
            default:
                return false;
        }
        skillPoints = Math.max(0, skillPoints - 1);
        return true;
    }
}
