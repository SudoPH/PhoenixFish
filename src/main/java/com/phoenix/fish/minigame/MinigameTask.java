package com.phoenix.fish.minigame;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.manager.BaitManager;
import com.phoenix.fish.model.CustomFish;
import com.phoenix.fish.util.FishRewardHelper;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.concurrent.ThreadLocalRandom;

/**
 * PhoenixFish minigame — balık tutma döngüsü.
 *
 * <p><b>v1.3.5 — Ekipman etkili minigame zorluğu:</b></p>
 *
 * <p>Eski sürümde minigame zorluğu ekipmandan bağımsızdı: epic balıklar her oltayla
 * aynı hızda kaçardı. Artık minigame, oltanın ve yemin {@code minigame:} bölümünden
 * okunan bonusları uyguluyor:</p>
 *
 * <ul>
 *   <li><b>Olta:</b> {@code bar-width-bonus}, {@code progress-rate-bonus},
 *       {@code tension-resistance}, {@code fish-slow-chance}</li>
 *   <li><b>Yem:</b> {@code fight-strength-reduction}, {@code fish-slow-bonus},
 *       {@code progress-bonus}</li>
 *   <li><b>Balık nadirliği:</b> {@code minigame.rarity-difficulty-multipliers.<rarity>}
 *       (örn. epic=1.6, legendary=2.0)</li>
 *   <li><b>Nadirlik başına tension cap:</b> {@code minigame.rarity-tension-cap.<rarity>}
 *       (epic=90, legendary=80 — yüksek nadirlik daha az tolere eder)</li>
 *   <li><b>Fast Catcher skill:</b> artık {@code xp-system.skill-per-level.fast-catcher-progress-bonus}
 *       config değerinden okunur (default 0.5, eski hardcoded değer)</li>
 * </ul>
 *
 * <p><b>Geriye dönük uyumluluk:</b> Tüm yeni alanların default değeri 0/no-op'tur.
 * Yani eski rods.yml, baits.yml, config.yml dosyalarıyla minigame davranışı
 * birebir aynı kalır.</p>
 */
public class MinigameTask {

    private final PhoenixFish plugin;
    private final Player player;
    private final CustomFish fish;
    private final FishingSession session;

    // Server-global config (read once at construction)
    private final int areaWidth;
    private final int rodWidthBase;
    private final double fishMoveChanceBase;
    private final int fishMoveDistance;
    private final double progressRateBase;
    private final double tensionIncreaseRateBase;
    private final double tensionDecreaseRate;

    // v1.3.5 — ekipman + nadirlik kaynaklı effective değerler (constructor'da hesaplanır)
    private final int effectiveRodWidth;
    private final double effectiveFishMoveChance;
    private final double effectiveProgressRate;
    private final double effectiveTensionIncreaseRate;
    private final double effectiveFishFightStrength;
    private final double effectiveTensionCap;

    private volatile ScheduledTask task;

    private static final TextColor PROGRESS_COLOR = TextColor.color(0x2ECC71);
    private static final TextColor PROGRESS_EMPTY = TextColor.color(0x3B4A5A);
    private static final TextColor TENSION_LOW = TextColor.color(0x1ABC9C);
    private static final TextColor TENSION_MID = TextColor.color(0xF39C12);
    private static final TextColor TENSION_HIGH = TextColor.color(0xE74C3C);
    private static final TextColor GAME_BG = TextColor.color(0x4B5563);

    /**
     * Eski constructor — geriye dönük uyumluluk için. rod ve bait null sayılır,
     * böylece minigame eski davranışla (ekipman etkisi yok) çalışır.
     */
    public MinigameTask(PhoenixFish plugin, Player player, CustomFish fish) {
        this(plugin, player, fish, null, null);
    }

    /**
     * Yeni constructor — rod ve bait'in minigame bonuslarını uygular.
     *
     * @param plugin      PhoenixFish instance
     * @param player      oynayan oyuncu
     * @param fish        yakalanan balık (fightStrength + rarity kullanılır)
     * @param rodConfig   rods.yml'den ilgili oltanın ConfigurationSection'u (null olabilir)
     * @param bait        oyuncunun off-hand'ındaki yem (null olabilir)
     */
    public MinigameTask(PhoenixFish plugin, Player player, CustomFish fish,
                          ConfigurationSection rodConfig, BaitManager.Bait bait) {
        if (plugin == null)
            throw new IllegalArgumentException("Plugin cannot be null");
        if (player == null)
            throw new IllegalArgumentException("Player cannot be null");
        if (fish == null)
            throw new IllegalArgumentException("Fish cannot be null");

        this.plugin = plugin;
        this.player = player;
        this.fish = fish;

        // ─── 1) Server-global config değerleri ───
        this.areaWidth = plugin.getConfig().getInt("minigame.area-width", 30);
        this.rodWidthBase = plugin.getConfig().getInt("minigame.rod-width", 2);
        this.fishMoveChanceBase = plugin.getConfig().getDouble("minigame.fish-move-chance", 0.6);
        this.fishMoveDistance = plugin.getConfig().getInt("minigame.fish-move-distance", 2);
        this.progressRateBase = plugin.getConfig().getDouble("minigame.progress-rate", 2.0);
        this.tensionIncreaseRateBase = plugin.getConfig().getDouble("minigame.tension-increase-rate", 1.2);
        this.tensionDecreaseRate = plugin.getConfig().getDouble("minigame.tension-decrease-rate", 3.0);

        // ─── 2) Olta bonusları (rods.yml → minigame: bölümü) ───
        // Hep default 0/no-op — eski oltalar hiç değişmez.
        int rodBarWidthBonus = 0;
        double rodProgressBonus = 0.0;
        double rodTensionResist = 0.0;          // [0.0, 1.0] — tension-increase-rate çarpanı (1 - x)
        double rodFishSlowMult = 1.0;            // fishMoveChance çarpanı (1.0 = değişmez)

        if (rodConfig != null) {
            ConfigurationSection mg = rodConfig.getConfigurationSection("minigame");
            if (mg != null) {
                rodBarWidthBonus = mg.getInt("bar-width-bonus", 0);
                rodProgressBonus = mg.getDouble("progress-rate-bonus", 0.0);
                rodTensionResist = Math.max(0.0, Math.min(0.95, mg.getDouble("tension-resistance", 0.0)));
                rodFishSlowMult = Math.max(0.0, Math.min(1.0, mg.getDouble("fish-slow-chance", 1.0)));
            }
        }

        // ─── 3) Yem bonusları (baits.yml → minigame: bölümü) ───
        // Sadece yemin target-rarity değeri yakalanan balığa uyuyorsa bonus uygula
        // (mevcut chance-modifier davranışıyla tutarlı).
        double baitFightReduction = 0.0;        // [0.0, 1.0] — fightStrength çarpanı (1 - x)
        double baitFishSlowBonus = 0.0;          // fishMoveChance additive reduction
        double baitProgressBonus = 0.0;          // progressRate additive

        if (bait != null && bait.targetRarity() == fish.rarity()) {
            // Yemin config section'ını BaitManager üzerinden al (yeni API)
            ConfigurationSection baitSec = null;
            try {
                baitSec = plugin.getBaitManager().getBaitConfig(bait.id());
            } catch (Throwable ignored) {
                // Eski PhoenixFish sürümleri bu metoda sahip olmayabilir — yumuşak düş.
            }
            if (baitSec != null) {
                ConfigurationSection mg = baitSec.getConfigurationSection("minigame");
                if (mg != null) {
                    baitFightReduction = Math.max(0.0, Math.min(0.95, mg.getDouble("fight-strength-reduction", 0.0)));
                    baitFishSlowBonus = Math.max(0.0, Math.min(0.95, mg.getDouble("fish-slow-bonus", 0.0)));
                    baitProgressBonus = mg.getDouble("progress-bonus", 0.0);
                }
            }
        }

        // ─── 4) Nadirlik kaynaklı zorluk çarpanı (config.yml) ───
        // rarity 1-5; default 1.0 → nadirlik etkisi yok
        double rarityMult = plugin.getConfig().getDouble(
                "minigame.rarity-difficulty-multipliers." + fish.rarity(), 1.0);
        rarityMult = Math.max(0.1, rarityMult);  // 0'a düşmesin

        // Nadirlik başına tension cap — default 100 (yani eski davranış)
        double rarityTensionCap = plugin.getConfig().getDouble(
                "minigame.rarity-tension-cap." + fish.rarity(), 100.0);
        rarityTensionCap = Math.max(10.0, Math.min(100.0, rarityTensionCap));

        // ─── 5) Effective değerleri hesapla ───
        this.effectiveRodWidth = Math.max(0, this.rodWidthBase + rodBarWidthBonus);

        // fishMoveChance: base × rodFishSlowMult - baitFishSlowBonus (min 0.05)
        double fmc = this.fishMoveChanceBase * rodFishSlowMult - baitFishSlowBonus;
        this.effectiveFishMoveChance = Math.max(0.05, Math.min(1.0, fmc));

        // progressRate: base + rod + bait
        this.effectiveProgressRate = this.progressRateBase + rodProgressBonus + baitProgressBonus;

        // tensionIncreaseRate: base × (1 - rodTensionResist)
        this.effectiveTensionIncreaseRate = this.tensionIncreaseRateBase * (1.0 - rodTensionResist);

        // effective fight strength: fish.fightStrength × rarityMult × (1 - baitFightReduction)
        this.effectiveFishFightStrength = fish.fightStrength() * rarityMult * (1.0 - baitFightReduction);

        // Effective tension cap (nadirlik bazlı)
        this.effectiveTensionCap = rarityTensionCap;

        int startPos = areaWidth / 2;
        this.session = new FishingSession(startPos, startPos, 0.0, 0.0);
    }

    public FishingSession getSession() {
        return session;
    }

    public void start() {
        this.task = player.getScheduler().runAtFixedRate(plugin, t -> {
            if (!player.isOnline() || player.isDead()) {
                cancel();
                return;
            }
            // C-12 FIX: any exception in updateMinigame used to silently kill the scheduled task
            // without removing the player's entry from activeMinigames. The next PlayerFishEvent
            // would then hit "activeMinigames.containsKey(uuid) -> setCancelled(true); return"
            // and the player would be permanently unable to fish until quit.
            try {
                updateMinigame();
            } catch (Throwable ex) {
                plugin.getLogger().severe("MinigameTask crashed for " + player.getName()
                        + ": " + ex.getMessage());
                try {
                    cancel();
                } catch (Throwable cleanupEx) {
                    plugin.getLogger().severe("MinigameTask cleanup also failed for "
                            + player.getName() + ": " + cleanupEx.getMessage());
                    plugin.getFishingListener().removeMinigame(player.getUniqueId());
                }
            }
        }, null, 1L, 2L);
    }

    private void updateMinigame() {
        boolean isPulling = (System.currentTimeMillis() - session.getLastPullTick()) < 300;

        int playerBarCenter = session.getPlayerBarCenter() + (isPulling ? 1 : -1);
        playerBarCenter = Math.max(0, Math.min(areaWidth, playerBarCenter));

        int fishPos = session.getFishPosition();
        if (ThreadLocalRandom.current().nextDouble() < effectiveFishMoveChance) {
            fishPos += (ThreadLocalRandom.current().nextBoolean() ? fishMoveDistance : -fishMoveDistance);
        }
        fishPos = Math.max(0, Math.min(areaWidth, fishPos));

        double progress = session.getProgress();
        double tension = session.getTension();

        int distance = Math.abs(playerBarCenter - fishPos);
        boolean isHitting = distance <= effectiveRodWidth;

        // v1.3.5 — Fast Catcher per-level değeri artık config'den okunur
        // (default 0.5 — eski hardcoded değer). xp-system kapalıysa uygulanmaz.
        double actualProgressRate = effectiveProgressRate;
        if (plugin.isXpSystemEnabled()) {
            FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
            if (data != null && data.getFastCatcher() > 0) {
                double perLevel = plugin.getConfig().getDouble(
                        "xp-system.skill-per-level.fast-catcher-progress-bonus", 0.5);
                actualProgressRate += (data.getFastCatcher() * perLevel);
            }
        }

        if (isHitting) {
            progress += actualProgressRate;
            tension -= tensionDecreaseRate;
        } else {
            // v1.3.5 — Effective fight strength: fish.fightStrength × rarity × (1 - bait reduction)
            tension += (effectiveTensionIncreaseRate * effectiveFishFightStrength);
        }

        progress = Math.max(0, Math.min(100, progress));
        // v1.3.5 — tension cap nadirlik bazlı (epik balıklarda daha düşük)
        tension = Math.max(0, Math.min(effectiveTensionCap, tension));

        session.setPlayerBarCenter(playerBarCenter);
        session.setFishPosition(fishPos);
        session.setProgress(progress);
        session.setTension(tension);

        Component ui = Component.empty()
                .append(Component.text("🎣 ").color(NamedTextColor.GRAY))
                .append(buildBar((int) (progress / 10.0), PROGRESS_COLOR, PROGRESS_EMPTY))
                .append(Component.text(" %" + (int) progress).color(NamedTextColor.DARK_GRAY))
                .append(Component.text("  │  ").color(NamedTextColor.DARK_GRAY))
                .append(buildGameArea(playerBarCenter, fishPos, isHitting))
                .append(Component.text("  │  🧵 ").color(NamedTextColor.GRAY))
                .append(buildTensionBar((int) (tension / 10.0), tension))
                .append(Component.text(" %" + (int) tension).color(NamedTextColor.DARK_GRAY));

        player.sendActionBar(ui);

        if (progress >= 100.0) {
            cancel();
            handleWin();
        } else if (tension >= effectiveTensionCap) {
            cancel();
            player.sendActionBar(plugin.getMessageManager().getMessage("minigame_lose", false));
        }
    }

    private Component buildBar(int filled, TextColor filledColor, TextColor emptyColor) {
        Component bar = Component.empty();
        for (int i = 0; i < 10; i++) {
            if (i < filled) {
                bar = bar.append(Component.text("▰").color(filledColor));
            } else {
                bar = bar.append(Component.text("▱").color(emptyColor));
            }
        }
        return bar;
    }

    private Component buildTensionBar(int filled, double tension) {
        TextColor color = TENSION_LOW;
        // v1.3.5 — tension bar rengi effectiveTensionCap'e göre ölçeklensin,
        // böylece nadir balıklarda (cap 80) %75'te kırmızı gösterilir (kırmızı alan
        // daha çabuk görünsün diye).
        double ratio = effectiveTensionCap > 0 ? tension / effectiveTensionCap : 0;
        if (ratio > 0.8)
            color = TENSION_HIGH;
        else if (ratio > 0.5)
            color = TENSION_MID;
        return buildBar(filled, color, PROGRESS_EMPTY);
    }

    private Component buildGameArea(int playerBarCenter, int fishPos, boolean isHitting) {
        Component game = Component.empty();
        for (int i = 0; i <= areaWidth; i++) {
            if (i == fishPos) {
                game = game.append(Component.text(isHitting ? "🐠" : "🐟"));
            } else if (Math.abs(i - playerBarCenter) <= effectiveRodWidth) {
                game = game.append(Component.text("▮").color(isHitting ? PROGRESS_COLOR : TENSION_HIGH));
            } else {
                game = game.append(Component.text("·").color(GAME_BG));
            }
        }
        return game;
    }

    private void handleWin() {
        if (plugin.getFishingModeManager().isVanillaMode(player)) {
            // M-7 FIX: previously silent. Player finished the minigame and got nothing + no message.
            // Tell them their catch was aborted because they switched to vanilla mode mid-minigame.
            player.sendActionBar(plugin.getMessageManager().getMessage("minigame_aborted", false));
            plugin.getLogger().warning(
                    "MinigameTask.handleWin() aborted: player " + player.getName()
                            + " is now in VANILLA fishing mode (cancelled mid-minigame).");
            return;
        }

        FishRewardHelper.awardCatch(plugin, player, fish, FishRewardHelper.CatchSource.MINIGAME);
    }

    public void cancel() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
        plugin.getFishingListener().removeMinigame(player.getUniqueId());
    }
}
