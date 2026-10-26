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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class TournamentManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();

    private volatile boolean active = false;
    // C-9 + H-15 FIX: ConcurrentHashMap so region-thread writes (FishRewardHelper.awardCatch)
    // and PAPI-thread reads (PhoenixFishPlaceholder.onRequest) don't race on HashMap's internal table.
    // getOrDefault/put on a plain HashMap can spin readers into an infinite loop during a resize.
    private final Map<UUID, Integer> scores = new ConcurrentHashMap<>();
    private volatile BossBar bossBar;

    private TournamentTask tournamentTask;
    private BukkitTask bukkitTask;

    private final boolean enabled;

    private volatile UUID currentLeaderUUID = null;
    private volatile int currentLeaderScore = -1;

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

    public synchronized void start(int durationMinutes) {
        if (!enabled || active)
            return;

        this.active = true;
        this.scores.clear();
        this.currentLeaderUUID = null;
        this.currentLeaderScore = -1;

        if (bukkitTask != null && !bukkitTask.isCancelled()) {
            bukkitTask.cancel();
        }
        tournamentTask = null;

        String title = plugin.getMessageManager().getPlainMessage("tournament_bossbar_start");
        String legacyTitle = legacySerializer.serialize(miniMessage.deserialize(title));

        this.bossBar = Bukkit.createBossBar(legacyTitle, BarColor.BLUE, BarStyle.SEGMENTED_10);
        this.bossBar.setProgress(1.0);

        for (Player player : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(player);
        }

        this.tournamentTask = new TournamentTask(plugin, durationMinutes);
        this.bukkitTask = this.tournamentTask.runTaskTimer(plugin, 0L, 20L);
    }

    public synchronized void stop(boolean giveRewards) {
        if (!active)
            return;

        this.active = false;

        if (bukkitTask != null && !bukkitTask.isCancelled()) {
            bukkitTask.cancel();
            bukkitTask = null;
        }
        tournamentTask = null;

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
        if (!active || player == null || points <= 0)
            return;

        UUID uuid = player.getUniqueId();
        // C-9 FIX: atomic merge — read-modify-write on a concurrent map is still racy without it.
        int newScore = scores.merge(uuid, points, Integer::sum);

        updateBossBarIfNeeded(uuid, newScore);
    }

    public int getScore(UUID uuid) {
        if (!active || uuid == null)
            return 0;
        return scores.getOrDefault(uuid, 0);
    }

    public String getLeaderName() {
        if (!active || currentLeaderUUID == null)
            return "";
        Player leader = Bukkit.getPlayer(currentLeaderUUID);
        return leader != null ? leader.getName() : "";
    }

    public int getLeaderScore() {
        if (!active || currentLeaderScore < 0)
            return 0;
        return currentLeaderScore;
    }

    public int getTimeLeftSeconds() {
        if (!active || tournamentTask == null)
            return 0;
        return tournamentTask.getSecondsLeft();
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
        if (limit <= 0)
            return new LinkedHashMap<>();

        // C-9 FIX: take a snapshot before sorting so concurrent puts during the sort
        // don't trigger a ConcurrentModificationException or unstable ordering.
        List<Map.Entry<UUID, Integer>> snapshot = new ArrayList<>(scores.entrySet());
        snapshot.sort(Map.Entry.<UUID, Integer>comparingByValue().reversed());

        return snapshot.stream()
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

            String playerName = (player != null)
                    ? player.getName()
                    : plugin.getMessageManager().getPlainMessage("tournament_unknown_player");

            Map<String, String> ph = new java.util.HashMap<>();
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

                if (rewardItem != null) {
                    if (player != null && player.isOnline()) {
                        Map<Integer, ItemStack> overflow = player.getInventory().addItem(rewardItem);
                        for (ItemStack drop : overflow.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), drop);
                        }
                    } else {
                        // M-3 FIX: previously silent. Log so admin knows the item was undeliverable.
                        plugin.getLogger().warning("Tournament rank " + rank
                                + " winner " + playerName + " is offline — item reward NOT delivered. "
                                + "Manually give it to the player if needed.");
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
        if (bukkitTask != null && !bukkitTask.isCancelled()) {
            bukkitTask.cancel();
            bukkitTask = null;
        }
        tournamentTask = null;
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        active = false;
        scores.clear();
    }
}
