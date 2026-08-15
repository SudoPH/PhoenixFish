package com.phoenix.fish.minigame;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.model.CustomFish;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
    private final MiniMessage miniMessage;

    private final int areaWidth;
    private final int rodWidth;
    private final double fishMoveChance;
    private final int fishMoveDistance;
    private final double progressRate;
    private final double tensionIncreaseRate;
    private final double tensionDecreaseRate;

    private volatile ScheduledTask task;

    public MinigameTask(PhoenixFish plugin, Player player, CustomFish fish) {
        this.plugin = plugin;
        this.player = player;
        this.fish = fish;
        this.miniMessage = MiniMessage.miniMessage();

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

        // UI Building
        int progressBars = (int) (progress / 10.0);
        StringBuilder progressSb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            progressSb.append(i < progressBars ? "<#2ECC71>▰</#2ECC71>" : "<#3B4A5A>▱</#3B4A5A>");
        }

        StringBuilder gameSb = new StringBuilder();
        String barColor = isHitting ? "<#2ECC71>" : "<#E74C3C>";
        String barClose = isHitting ? "</#2ECC71>" : "</#E74C3C>";

        for (int i = 0; i <= areaWidth; i++) {
            if (i == fishPos) {
                gameSb.append(isHitting ? "<bold>🐠</bold>" : "🐟");
            } else if (Math.abs(i - playerBarCenter) <= rodWidth) {
                gameSb.append(barColor).append("▮").append(barClose);
            } else {
                gameSb.append("<#4B5563>·</#4B5563>");
            }
        }

        int tensionBars = (int) (tension / 10.0);
        StringBuilder tensionSb = new StringBuilder();

        String tColor;
        String tClose;
        if (tension > 80) {
            tColor = "<#E74C3C><bold>";
            tClose = "</bold></#E74C3C>";
        } else if (tension > 50) {
            tColor = "<#F39C12>";
            tClose = "</#F39C12>";
        } else {
            tColor = "<#1ABC9C>";
            tClose = "</#1ABC9C>";
        }

        for (int i = 0; i < 10; i++) {
            tensionSb.append(i < tensionBars ? tColor + "▰" + tClose : "<#3B4A5A>▱</#3B4A5A>");
        }

        String uiString = "<#95A5A6>🎣</#95A5A6> " + progressSb + " <#7F8C8D>%" + (int) progress + "</#7F8C8D>" +
                "  <#4B5563>│</#4B5563>  " + gameSb +
                "  <#4B5563>│</#4B5563>  <#95A5A6>🧵</#95A5A6> " + tensionSb + " <#7F8C8D>%" + (int) tension
                + "</#7F8C8D>";

        player.sendActionBar(miniMessage.deserialize(uiString));

        if (progress >= 100.0) {
            cancel();
            handleWin();
        } else if (tension >= 100.0) {
            cancel();
            player.sendActionBar(plugin.getMessageManager().getMessage("minigame_lose", false));
        }
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