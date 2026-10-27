package com.phoenix.fish.placeholder;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.manager.TournamentManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * PlaceholderAPI expansion for PhoenixFish.
 *
 * <h2>Available Placeholders</h2>
 *
 * <h3>Player Progression</h3>
 * <ul>
 *   <li>{@code %phoenixfish_level%} — current fishing level</li>
 *   <li>{@code %phoenixfish_xp%} — XP in current level (resets on level-up)</li>
 *   <li>{@code %phoenixfish_xp_required%} — XP needed for next level (level × 100)</li>
 *   <li>{@code %phoenixfish_xp_progress_percent%} — progress % to next level (0-100)</li>
 *   <li>{@code %phoenixfish_total_xp%} — lifetime XP (never resets)</li>
 *   <li>{@code %phoenixfish_skillpoints%} — available skill points (unspent)</li>
 * </ul>
 *
 * <h3>Economy</h3>
 * <ul>
 *   <li>{@code %phoenixfish_balance%} — internal balance (2 decimals)</li>
 * </ul>
 *
 * <h3>Crafting</h3>
 * <ul>
 *   <li>{@code %phoenixfish_crafting_level%} — crafting level</li>
 *   <li>{@code %phoenixfish_crafting_xp%} — XP in current crafting level</li>
 * </ul>
 *
 * <h3>Skills (0-5 each)</h3>
 * <ul>
 *   <li>{@code %phoenixfish_skill_fast%} — Fast Catcher level</li>
 *   <li>{@code %phoenixfish_skill_hunter%} — Master Hunter level</li>
 *   <li>{@code %phoenixfish_skill_double%} — Double Catch level</li>
 *   <li>{@code %phoenixfish_skill_bait%} — Lucky Bait level</li>
 * </ul>
 *
 * <h3>Discovery</h3>
 * <ul>
 *   <li>{@code %phoenixfish_discovered_count%} — discovered fish species count</li>
 *   <li>{@code %phoenixfish_total_fish_count%} — total fish in config</li>
 *   <li>{@code %phoenixfish_discovered_percent%} — discovery % (0-100, 1 decimal)</li>
 * </ul>
 *
 * <h3>Records (per fish)</h3>
 * <ul>
 *   <li>{@code %phoenixfish_pb_<fish_id>%} — personal best weight for a fish (2 decimals)</li>
 *   <li>{@code %phoenixfish_sr_<fish_id>%} — server record weight for a fish (2 decimals)</li>
 * </ul>
 *
 * <p>Examples:</p>
 * <pre>{@code
 * %phoenixfish_pb_sea_bass%   → "2.45"
 * %phoenixfish_pb_leviathan%  → "0.00" (never caught)
 * %phoenixfish_sr_golden_fish% → "12.50"
 * }</pre>
 *
 * <h3>Mode</h3>
 * <ul>
 *   <li>{@code %phoenixfish_mode%} — "custom" or "vanilla"</li>
 * </ul>
 *
 * <h3>Tournament</h3>
 * <ul>
 *   <li>{@code %phoenixfish_tournament_active%} — "true" or "false"</li>
 *   <li>{@code %phoenixfish_tournament_score%} — player's current score (0 if not active)</li>
 *   <li>{@code %phoenixfish_tournament_leader%} — leader name (empty if no leader)</li>
 *   <li>{@code %phoenixfish_tournament_leader_score%} — leader's score</li>
 *   <li>{@code %phoenixfish_tournament_time_left%} — remaining seconds (0 if not active)</li>
 * </ul>
 *
 * <h2>Behavior Notes</h2>
 * <ul>
 *   <li>If XP system is disabled, progression placeholders return "0".</li>
 *   <li>If player data not yet loaded (just joined, async load in progress),
 *       progression placeholders return "0" — caller should retry shortly.</li>
 *   <li>Unknown placeholder → returns null (PAPI shows the literal placeholder).</li>
 *   <li>Offline player → still works for PB/SR (UUID-based lookup), but
 *       progression placeholders return "0" (data not cached for offline).</li>
 * </ul>
 */
public class PhoenixFishPlaceholder extends PlaceholderExpansion {

    private final PhoenixFish plugin;

    public PhoenixFishPlaceholder(PhoenixFish plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "phoenixfish";
    }

    @Override
    public @NotNull String getAuthor() {
        // plugin.yml'deki author field'ı. Birden fazla author varsa ilkini al.
        return plugin.getDescription().getAuthors().isEmpty()
                ? "Sudo"
                : plugin.getDescription().getAuthors().get(0);
    }

    @Override
    public @NotNull String getVersion() {
        // L-10 FIX: if Maven resource filtering failed, plugin.yml would literally contain
        // "${project.version}" as a string. Detect and fall back so the PAPI /papi info list
        // doesn't show a broken placeholder literal.
        String v = plugin.getDescription().getVersion();
        if (v == null || v.isEmpty() || v.contains("${")) {
            return "unknown";
        }
        return v;
    }

    @Override
    public boolean canRegister() {
        // Her zaman register olabilir — sadece PAPI'nin kendisi yüklü olmalı
        // (bu kontrol PhoenixFish.onEnable'da yapılıyor).
        return true;
    }

    @Override
    public boolean persist() {
        // Reload'lar sırasında expansion otomatik kayıt kalmasın diye persist=true.
        // PAPI /papi reload yaptığında expansion silinmez.
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            // H-24 FIX: return empty string instead of null for unknown/edge cases so
            // PAPI consumers (scoreboards, chat plugins) don't show literal %phoenixfish_...% text.
            return "";
        }

        String lower = params.toLowerCase();

        if (lower.startsWith("tournament_")) {
            return handleTournamentPlaceholder(player, lower);
        }

        if (lower.startsWith("pb_")) {
            String fishId = params.substring(3);
            double pb = plugin.getRecordManager().getPersonalRecord(player.getUniqueId(), fishId);
            // L-8 FIX: Locale.US so Turkish locale doesn't produce "2,45" for the placeholder value.
            return String.format(Locale.US, "%.2f", pb);
        }
        if (lower.startsWith("sr_")) {
            String fishId = params.substring(3);
            double sr = plugin.getRecordManager().getServerRecord(fishId);
            return String.format(Locale.US, "%.2f", sr);
        }

        if (lower.equals("mode")) {
            Player online = player.getPlayer();
            if (online != null) {
                return plugin.getFishingModeManager().isVanillaMode(online)
                        ? "vanilla"
                        : "custom";
            }
            return "custom";
        }

        if (plugin.isXpSystemEnabled() && plugin.getCacheManager() != null) {
            FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
            if (data != null) {
                return handleProgressionPlaceholder(data, lower);
            }
        }

        switch (lower) {
            case "level":
            case "xp":
            case "xp_required":
            case "xp_progress_percent":
            case "total_xp":
            case "skillpoints":
            case "crafting_level":
            case "crafting_xp":
            case "skill_fast":
            case "skill_hunter":
            case "skill_double":
            case "skill_bait":
            case "discovered_count":
            case "total_fish_count":
            case "discovered_percent":
            // v1.6.0 — Yeni placeholder'lar
            case "total_catches":
            case "biggest_fish":
            case "biggest_weight":
            case "rarest_fish":
            case "current_streak":
            case "best_streak":
            case "skill_big":
            case "skill_lucky":
            case "skill_stamina":
            case "mutation_count":
            case "total_earnings":
                return "0";
            default:
                // H-24 FIX: return "" (empty) for unknown placeholder rather than null.
                // PAPI consumers commonly interpret null as "leave the literal text" which leads
                // to ugly %phoenixfish_unknown% showing up in scoreboards and chat.
                return "";
        }
    }

    private String handleProgressionPlaceholder(FishingData data, String lower) {
        switch (lower) {
            case "level":
                return String.valueOf(data.getCurrentLevel());

            case "xp":
                return String.valueOf(data.getCurrentXp());

            case "xp_required":
                return String.valueOf(data.getCurrentLevel() * 100);

            case "xp_progress_percent":
                int required = data.getCurrentLevel() * 100;
                if (required <= 0)
                    return "0";
                double progress = (data.getCurrentXp() * 100.0) / required;
                if (progress > 100.0)
                    progress = 100.0;
                // L-8 FIX: Locale.US so the percent is always formatted with a dot decimal separator.
                return String.format(Locale.US, "%.1f", progress);

            case "total_xp":
                return String.valueOf(data.getTotalXp());

            case "skillpoints":
                return String.valueOf(data.getSkillPoints());

            case "crafting_level":
                return String.valueOf(data.getCraftingLevel());

            case "crafting_xp":
                return String.valueOf(data.getCraftingXp());

            case "skill_fast":
                return String.valueOf(data.getFastCatcher());

            case "skill_hunter":
                return String.valueOf(data.getMasterHunter());

            case "skill_double":
                return String.valueOf(data.getDoubleCatch());

            case "skill_bait":
                return String.valueOf(data.getLuckyBait());

            case "discovered_count":
                return String.valueOf(data.getDiscoveredFish().size());

            case "total_fish_count":
                return String.valueOf(plugin.getFishManager().getFishList().size());

            case "discovered_percent":
                int total = plugin.getFishManager().getFishList().size();
                int discovered = data.getDiscoveredFish().size();
                if (total == 0)
                    return "0";
                return String.format(Locale.US, "%.1f", (discovered * 100.0) / total);

            // v1.6.0 — Yeni placeholder'lar
            case "total_catches":
                return String.valueOf(data.getTotalCatches());

            case "biggest_fish":
                return data.getBiggestFishName() != null ? data.getBiggestFishName() : "—";

            case "biggest_weight":
                return String.format(Locale.US, "%.1f", data.getBiggestWeight());

            case "rarest_fish":
                return data.getRarestCatchName() != null ? data.getRarestCatchName() : "—";

            case "current_streak":
                return String.valueOf(data.getCurrentStreak());

            case "best_streak":
                return String.valueOf(data.getBestStreak());

            case "skill_big":
                return String.valueOf(data.getBigCatch());

            case "skill_lucky":
                return String.valueOf(data.getLuckyCast());

            case "skill_stamina":
                return String.valueOf(data.getStamina());

            case "mutation_count":
                return String.valueOf(data.getMutationCount());

            case "total_earnings":
                return String.format(Locale.US, "%.2f", data.getTotalEarnings());

            default:
                return "";
        }
    }

    private String handleTournamentPlaceholder(OfflinePlayer player, String lower) {
        TournamentManager tm = plugin.getTournamentManager();
        String sub = lower.substring("tournament_".length());

        switch (sub) {
            case "active":
                return String.valueOf(tm.isActive());

            case "score":
                if (!tm.isActive())
                    return "0";
                return String.valueOf(tm.getScore(player.getUniqueId()));

            case "leader":
                if (!tm.isActive())
                    return "";
                return tm.getLeaderName();

            case "leader_score":
                if (!tm.isActive())
                    return "0";
                return String.valueOf(tm.getLeaderScore());

            case "time_left":
                if (!tm.isActive())
                    return "0";
                return String.valueOf(tm.getTimeLeftSeconds());

            default:
                return "";
        }
    }
}
