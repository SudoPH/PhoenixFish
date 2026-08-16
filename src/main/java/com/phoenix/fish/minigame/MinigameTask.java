package com.phoenix.fish.minigame;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.model.CustomFish;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class MinigameTask {

    private final PhoenixFish plugin;
    private final Player player;
    private final CustomFish fish;
    private final FishingSession session;

    private final int areaWidth;
    private final int rodWidth;
    private final double fishMoveChance;
    private final int fishMoveDistance;
    private final double progressRate;
    private final double tensionIncreaseRate;
    private final double tensionDecreaseRate;

    private volatile ScheduledTask task;

    private static final TextColor PROGRESS_COLOR = TextColor.color(0x2ECC71);
    private static final TextColor PROGRESS_EMPTY = TextColor.color(0x3B4A5A);
    private static final TextColor TENSION_LOW = TextColor.color(0x1ABC9C);
    private static final TextColor TENSION_MID = TextColor.color(0xF39C12);
    private static final TextColor TENSION_HIGH = TextColor.color(0xE74C3C);
    private static final TextColor GAME_BG = TextColor.color(0x4B5563);

    public MinigameTask(PhoenixFish plugin, Player player, CustomFish fish) {
        this.plugin = plugin;
        this.player = player;
        this.fish = fish;

        this.areaWidth = plugin.getConfig().getInt("minigame.area-width", 30);
        this.rodWidth = plugin.getConfig().getInt("minigame.rod-width", 2);
        this.fishMoveChance = plugin.getConfig().getDouble("minigame.fish-move-chance", 0.6);
        this.fishMoveDistance = plugin.getConfig().getInt("minigame.fish-move-distance", 2);
        this.progressRate = plugin.getConfig().getDouble("minigame.progress-rate", 2.0);
        this.tensionIncreaseRate = plugin.getConfig().getDouble("minigame.tension-increase-rate", 1.2);
        this.tensionDecreaseRate = plugin.getConfig().getDouble("minigame.tension-decrease-rate", 3.0);

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
            updateMinigame();
        }, null, 1L, 2L);
    }

    private void updateMinigame() {
        boolean isPulling = (System.currentTimeMillis() - session.getLastPullTick()) < 300;

        int playerBarCenter = session.getPlayerBarCenter() + (isPulling ? 1 : -1);
        playerBarCenter = Math.max(0, Math.min(areaWidth, playerBarCenter));

        int fishPos = session.getFishPosition();
        if (ThreadLocalRandom.current().nextDouble() < fishMoveChance) {
            fishPos += (ThreadLocalRandom.current().nextBoolean() ? fishMoveDistance : -fishMoveDistance);
        }
        fishPos = Math.max(0, Math.min(areaWidth, fishPos));

        double progress = session.getProgress();
        double tension = session.getTension();

        int distance = Math.abs(playerBarCenter - fishPos);
        boolean isHitting = distance <= rodWidth;

        if (isHitting) {
            progress += progressRate;
            tension -= tensionDecreaseRate;
        } else {
            tension += (tensionIncreaseRate * fish.fightStrength());
        }

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
        } else if (tension >= 100.0) {
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
        if (tension > 80)
            color = TENSION_HIGH;
        else if (tension > 50)
            color = TENSION_MID;

        return buildBar(filled, color, PROGRESS_EMPTY);
    }

    private Component buildGameArea(int playerBarCenter, int fishPos, boolean isHitting) {
        Component game = Component.empty();
        for (int i = 0; i <= areaWidth; i++) {
            if (i == fishPos) {
                game = game.append(Component.text(isHitting ? "🐠" : "🐟"));
            } else if (Math.abs(i - playerBarCenter) <= rodWidth) {
                game = game.append(Component.text("▮").color(isHitting ? PROGRESS_COLOR : TENSION_HIGH));
            } else {
                game = game.append(Component.text("·").color(GAME_BG));
            }
        }
        return game;
    }

    private void handleWin() {
        int amountToGive = calculateMultiCatchAmount();

        if (amountToGive > 1) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("%amount%", String.valueOf(amountToGive));
            player.sendActionBar(plugin.getMessageManager().getMessage("minigame_win_multiple", false, placeholders));
        } else {
            player.sendActionBar(plugin.getMessageManager().getMessage("minigame_win_single", false));
        }

        ItemStack caughtItem = fish.itemStack().clone();
        caughtItem.setAmount(amountToGive);

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(caughtItem);
        if (!overflow.isEmpty()) {
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }

        plugin.playCatchEffects(player, fish);

        if (plugin.isDiscoveryEnabled()) {
            FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
            if (data != null && data.discoverFish(fish.id())) {
                player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            }
        }

        if (plugin.isXpSystemEnabled() && fish.xpReward() > 0) {
            plugin.getCacheManager().addXpAndCheckLevel(player, fish.xpReward() * amountToGive);
        }
    }

    private int calculateMultiCatchAmount() {
        if (!plugin.isXpSystemEnabled() || !plugin.getConfig().getBoolean("xp-system.multi-catch.enabled", false)) {
            return 1;
        }

        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null)
            return 1;

        int level = data.getCurrentLevel();
        int level3x = plugin.getConfig().getInt("xp-system.multi-catch.level-3x", 999);
        int level2x = plugin.getConfig().getInt("xp-system.multi-catch.level-2x", 999);
        double chance3x = plugin.getConfig().getDouble("xp-system.multi-catch.chance-3x", 0.0);
        double chance2x = plugin.getConfig().getDouble("xp-system.multi-catch.chance-2x", 0.0);

        if (level >= level3x && ThreadLocalRandom.current().nextDouble() < chance3x)
            return 3;
        if (level >= level2x && ThreadLocalRandom.current().nextDouble() < chance2x)
            return 2;

        return 1;
    }

    public void cancel() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
        plugin.getFishingListener().removeMinigame(player.getUniqueId());
    }
}