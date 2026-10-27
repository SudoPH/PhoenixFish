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

        // v1.5.0 (F-1) — Otomatik tekrarlanan turnuva planlama.
        // Eğer config'de tournament.schedule.daily veya tournament.schedule.weekly varsa,
        // bir sonraki turnuva zamanını hesapla ve scheduler'a kaydet.
        if (enabled) {
            scheduleRecurringTournaments();
        }
    }

    /**
     * v1.5.0 (F-1) — config.yml'deki tournament.schedule bölümünü okuyup
     * otomatik tekrarlanan turnuva zamanlayıcı kurar.
     *
     * Config formatı:
     *   tournament:
     *     schedule:
     *       daily:
     *         time: "20:00"   # her gün 20:00
     *         duration-minutes: 30
     *       weekly:
     *         day: SATURDAY    # MONDAY..SUNDAY
     *         time: "18:00"
     *         duration-minutes: 60
     */
    private void scheduleRecurringTournaments() {
        org.bukkit.configuration.ConfigurationSection sched =
                plugin.getConfig().getConfigurationSection("tournament.schedule");
        if (sched == null) return;

        // Daily schedule
        org.bukkit.configuration.ConfigurationSection daily = sched.getConfigurationSection("daily");
        if (daily != null) {
            String time = daily.getString("time");
            int duration = daily.getInt("duration-minutes", 30);
            if (time != null && !time.isEmpty()) {
                long delayTicks = calculateDelayTicksForToday(time);
                if (delayTicks >= 0) {
                    long periodTicks = 24L * 60 * 60 * 20;  // 24 saat (ticks)
                    Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                        if (!active) {
                            start(duration);
                            plugin.getLogger().info("Scheduled daily tournament started (duration: "
                                    + duration + " min).");
                        }
                    }, delayTicks, periodTicks);
                    plugin.getLogger().info("Daily tournament scheduled at " + time
                            + " (duration " + duration + " min, first start in "
                            + (delayTicks / 20 / 60) + " min).");
                }
            }
        }

        // Weekly schedule
        org.bukkit.configuration.ConfigurationSection weekly = sched.getConfigurationSection("weekly");
        if (weekly != null) {
            String dayStr = weekly.getString("day", "SATURDAY").toUpperCase();
            String time = weekly.getString("time");
            int duration = weekly.getInt("duration-minutes", 60);
            try {
                java.time.DayOfWeek dayOfWeek = java.time.DayOfWeek.valueOf(dayStr);
                long delayTicks = calculateWeeklyDelayTicks(dayOfWeek, time);
                if (delayTicks >= 0) {
                    long periodTicks = 7L * 24 * 60 * 60 * 20;  // 7 gün (ticks)
                    Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                        if (!active) {
                            start(duration);
                            plugin.getLogger().info("Scheduled weekly tournament started on "
                                    + dayOfWeek + " at " + time + " (duration: " + duration + " min).");
                        }
                    }, delayTicks, periodTicks);
                    plugin.getLogger().info("Weekly tournament scheduled on " + dayOfWeek
                            + " at " + time + " (duration " + duration + " min, first start in "
                            + (delayTicks / 20 / 60 / 60) + " hours).");
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid day-of-week '" + dayStr
                        + "' for weekly tournament schedule. Use MONDAY..SUNDAY.");
            }
        }
    }

    /**
     * Bugün belirtilen saat için tick sayısı hesapla (20 ticks = 1 saniye).
     */
    private long calculateDelayTicksForToday(String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            java.time.LocalTime targetTime = java.time.LocalTime.of(hour, minute);
            java.time.LocalTime now = java.time.LocalTime.now();
            long secondsUntil;
            if (now.isBefore(targetTime)) {
                secondsUntil = java.time.Duration.between(now, targetTime).getSeconds();
            } else {
                // Bugün geçti → yarın aynı saat
                secondsUntil = java.time.Duration.between(now, targetTime).getSeconds()
                        + (24 * 60 * 60);
            }
            return secondsUntil * 20;  // 20 ticks per second
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid time format '" + timeStr
                    + "'. Use HH:MM (e.g., 20:00).");
            return -1;
        }
    }

    /**
     * Belirtilen gün ve saat için tick sayısı hesapla.
     */
    private long calculateWeeklyDelayTicks(java.time.DayOfWeek targetDay, String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            java.time.LocalDateTime target = now
                    .with(java.time.temporal.TemporalAdjusters.nextOrSame(targetDay))
                    .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            if (target.isBefore(now) || target.isEqual(now)) {
                // Bu hafta geçti → bir sonraki hafta
                target = target.plusWeeks(1);
            }
            long secondsUntil = java.time.Duration.between(now, target).getSeconds();
            return secondsUntil * 20;
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid weekly schedule format.");
            return -1;
        }
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
