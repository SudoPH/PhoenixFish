package com.phoenix.fish.minigame;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.model.CustomFish;
import com.phoenix.fish.util.FishRewardHelper;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

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
        if (plugin == null)
            throw new IllegalArgumentException("Plugin cannot be null");
        if (player == null)
            throw new IllegalArgumentException("Player cannot be null");
        if (fish == null)
            throw new IllegalArgumentException("Fish cannot be null");

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
        if (ThreadLocalRandom.current().nextDouble() < fishMoveChance) {
            fishPos += (ThreadLocalRandom.current().nextBoolean() ? fishMoveDistance : -fishMoveDistance);
        }
        fishPos = Math.max(0, Math.min(areaWidth, fishPos));

        double progress = session.getProgress();
        double tension = session.getTension();

        int distance = Math.abs(playerBarCenter - fishPos);
        boolean isHitting = distance <= rodWidth;

        double actualProgressRate = progressRate;
        if (plugin.isXpSystemEnabled()) {
            FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
            if (data != null && data.getFastCatcher() > 0) {
                actualProgressRate += (data.getFastCatcher() * 0.5);
            }
        }

        if (isHitting) {
            progress += actualProgressRate;
            tension -= tensionDecreaseRate;
        } else {
            tension += (tensionIncreaseRate * fish.fightStrength());
        }

        progress = Math.max(0, Math.min(100, progress));
        tension = Math.max(0, Math.min(100, tension));

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
