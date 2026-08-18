package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.task.TournamentTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class TournamentManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();

    private boolean active = false;
    private final Map<UUID, Integer> scores = new HashMap<>();
    private BossBar bossBar;
    private BukkitTask task;

    private final boolean enabled;

    private UUID currentLeaderUUID = null;
    private int currentLeaderScore = -1;

    public TournamentManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.enabled = plugin.getConfig().getBoolean("tournament.enabled", false);
    }

    public boolean isActive() {
        return active;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void start(int durationMinutes) {
        if (!enabled || active)
            return;

        this.active = true;
        this.scores.clear();
        this.currentLeaderUUID = null;
        this.currentLeaderScore = -1;

        if (task != null && !task.isCancelled()) {
            task.cancel();
        }

        String title = plugin.getMessageManager().getPlainMessage("tournament_bossbar_start");
        String legacyTitle = legacySerializer.serialize(miniMessage.deserialize(title));

        this.bossBar = Bukkit.createBossBar(legacyTitle, BarColor.BLUE, BarStyle.SEGMENTED_10);
        this.bossBar.setProgress(1.0);

        for (Player player : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(player);
        }

        this.task = new TournamentTask(plugin, durationMinutes).runTaskTimer(plugin, 0L, 20L);
    }

    public void stop(boolean giveRewards) {
        if (!active)
            return;

        this.active = false;

        if (task != null && !task.isCancelled()) {
            task.cancel();
            task = null;
        }

        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        if (giveRewards) {
            rewardTopPlayers();
        }
        scores.clear();
    }

    public void addScore(Player player, int points) {
        if (!active || player == null)
            return;

        UUID uuid = player.getUniqueId();
        int newScore = scores.getOrDefault(uuid, 0) + points;
        scores.put(uuid, newScore);

        updateBossBarIfNeeded(uuid, newScore);
    }

    private void updateBossBarIfNeeded(UUID updatedPlayer, int newScore) {
        if (bossBar == null)
            return;

        if (updatedPlayer.equals(currentLeaderUUID)) {
            currentLeaderScore = newScore;
            updateBossBar();
        } else if (newScore > currentLeaderScore) {
            currentLeaderUUID = updatedPlayer;
            currentLeaderScore = newScore;
            updateBossBar();
        }
    }

    private void updateBossBar() {
        if (bossBar == null || currentLeaderUUID == null)
            return;

        Player topPlayer = Bukkit.getPlayer(currentLeaderUUID);
        if (topPlayer == null)
            return;

        String titleTemplate = plugin.getMessageManager().getPlainMessage("tournament_bossbar_progress");
        String titleStr = titleTemplate.replace("%leader%", topPlayer.getName())
                .replace("%score%", String.valueOf(currentLeaderScore));

        String legacyTitle = legacySerializer.serialize(miniMessage.deserialize(titleStr));
        bossBar.setTitle(legacyTitle);
    }

    public Map<UUID, Integer> getTopPlayers(int limit) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new));
    }

    private void rewardTopPlayers() {
        Map<UUID, Integer> top3 = getTopPlayers(3);
        if (top3.isEmpty()) {
            Bukkit.broadcast(plugin.getMessageManager().getMessage("tournament_no_winners", true));
            return;
        }

        Bukkit.broadcast(plugin.getMessageManager().getMessage("tournament_end_announcement", true));

        int rank = 1;
        for (Map.Entry<UUID, Integer> entry : top3.entrySet()) {
            UUID uuid = entry.getKey();
            int score = entry.getValue();
            Player player = Bukkit.getPlayer(uuid);
            String playerName = (player != null) ? player.getName() : "Bilinmeyen Oyuncu";

            Map<String, String> ph = new HashMap<>();
            ph.put("%rank%", String.valueOf(rank));
            ph.put("%player%", playerName);
            ph.put("%score%", String.valueOf(score));
            Bukkit.broadcast(plugin.getMessageManager().getMessage("tournament_winner_line", true, ph));

            String rewardPath = "tournament.rewards." + rank;

            if (plugin.getConfig().contains(rewardPath + ".commands")) {
                for (String cmd : plugin.getConfig().getStringList(rewardPath + ".commands")) {
                    String executedCmd = cmd.replace("%player%", playerName);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), executedCmd);
                }
            }

            if (plugin.getConfig().contains(rewardPath + ".item")) {
                String base64Item = plugin.getConfig().getString(rewardPath + ".item");
                ItemStack rewardItem = ItemUtils.base64ToItem(base64Item);

                if (rewardItem != null && player != null && player.isOnline()) {
                    Map<Integer, ItemStack> overflow = player.getInventory().addItem(rewardItem);
                    for (ItemStack drop : overflow.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), drop);
                    }
                }
            }
            rank++;
        }
    }

    public BossBar getBossBar() {
        return bossBar;
    }

    public void shutdown() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
            task = null;
        }
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        active = false;
        scores.clear();
    }
}