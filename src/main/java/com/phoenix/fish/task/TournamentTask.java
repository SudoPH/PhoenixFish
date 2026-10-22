package com.phoenix.fish.task;

import com.phoenix.fish.PhoenixFish;
import org.bukkit.scheduler.BukkitRunnable;

public class TournamentTask extends BukkitRunnable {

    private final PhoenixFish plugin;
    private final double totalSeconds;
    private volatile int secondsLeft;

    public TournamentTask(PhoenixFish plugin, int minutes) {
        this.plugin = plugin;
        this.totalSeconds = minutes * 60.0;
        this.secondsLeft = minutes * 60;
    }

    @Override
    public void run() {
        if (!plugin.getTournamentManager().isActive()) {
            cancel();
            return;
        }

        if (secondsLeft <= 0) {
            plugin.getTournamentManager().stop(true);
            cancel();
            return;
        }

        secondsLeft--;

        if (secondsLeft % 5 == 0) {
            var bossBar = plugin.getTournamentManager().getBossBar();
            if (bossBar != null) {
                double progress = secondsLeft / totalSeconds;
                bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
            }
        }
    }

    // =========================================================================
    // FEATURE #11: PAPI için kalan süre getter'ı.
    //
    // %phoenixfish_tournament_time_left% placeholder'ı TournamentManager'ın
    // getTimeLeftSeconds() metodundan geliyor, o da bu metodu çağırıyor.
    //
    // Volatile olarak işaretli çünkü:
    //   - TournamentTask.run() main thread'de çalışır (BukkitRunnable)
    //   - getSecondsLeft() PAPI üzerinden herhangi bir thread'den çağrılabilir
    //   - Volatile, bir thread'in yazdığı değerin diğer thread'ler tarafından
    //     görülmesini garanti eder (visibility)
    // =========================================================================
    public int getSecondsLeft() {
        return secondsLeft;
    }
}
