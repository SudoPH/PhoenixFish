package com.phoenix.fish.minigame;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.model.CustomFish;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
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
        int amountToGive = calculateMultiCatchAmount();

        if (amountToGive > 1) {
            Map<String, String> placeholders = Map.of("%amount%", String.valueOf(amountToGive));
            player.sendActionBar(plugin.getMessageManager().getMessage("minigame_win_multiple", false, placeholders));
        } else {
            player.sendActionBar(plugin.getMessageManager().getMessage("minigame_win_single", false));
        }

        double weight = fish.minWeight()
                + (ThreadLocalRandom.current().nextDouble() * (fish.maxWeight() - fish.minWeight()));
        String formattedWeight = String.format("%.1f", weight);

        ItemStack fishItem = fish.itemStack();
        if (fishItem == null) {
            plugin.getLogger().warning("Fish itemStack is null for fish: " + fish.id());
            return;
        }

        ItemStack caughtItem = fishItem.clone();
        caughtItem.setAmount(amountToGive);

        ItemMeta meta = caughtItem.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore();
            if (lore == null)
                lore = new ArrayList<>();

            Map<String, String> weightPh = Map.of("%weight%", formattedWeight);
            Component weightLore = plugin.getMessageManager().getMessage("fish_lore_weight", false, weightPh);
            lore.add(weightLore);

            meta.lore(lore);
            caughtItem.setItemMeta(meta);
        }

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(caughtItem);
        if (!overflow.isEmpty()) {
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }

        plugin.playCatchEffects(player, fish);

        plugin.getRecordManager().checkRecords(player, fish, weight, formattedWeight);

        if (plugin.isDiscoveryEnabled()) {
            FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
            if (data != null && data.discoverFish(fish.id())) {
                playSoundFromConfig(player, "sounds.discover", Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            }
        }

        if (plugin.isXpSystemEnabled() && fish.xpReward() > 0) {
            plugin.getCacheManager().addXpAndCheckLevel(player, fish.xpReward() * amountToGive);
        }

        if (plugin.getTournamentManager().isActive()) {
            int points = (fish.rarity() >= 3) ? 5 : 1;
            plugin.getTournamentManager().addScore(player, points * amountToGive);
        }

        if (plugin.getApi() != null) {
            try {
                plugin.getApi().triggerFishCaught(player, fish, weight);
            } catch (Exception e) {
                plugin.getLogger().warning("Error triggering FishCaughtEvent: " + e.getMessage());
            }
        }
    }

    private int calculateMultiCatchAmount() {
        if (!plugin.isXpSystemEnabled()) {
            return 1;
        }

        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null || data.getDoubleCatch() == 0) {
            return 1;
        }

        double chance = data.getDoubleCatch() * 0.10;
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            return 2;
        }

        return 1;
    }

    public void cancel() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
        plugin.getFishingListener().removeMinigame(player.getUniqueId());
    }

    @SuppressWarnings({ "removal" })
    private void playSoundFromConfig(Player player, String path, Sound defaultSound, float volume, float pitch) {
        String soundName = plugin.getConfig().getString(path);
        Sound soundToPlay = defaultSound;

        if (soundName != null && !soundName.isEmpty()) {
            try {
                soundToPlay = Sound.valueOf(soundName.toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid sound in config: " + soundName + " at path: " + path);
            }
        }

        if (player.isOnline()) {
            player.playSound(player, soundToPlay, volume, pitch);
        }
    }
}