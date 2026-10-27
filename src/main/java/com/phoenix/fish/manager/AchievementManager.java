package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.6.0 — Achievement System (PhoenixFish tarafında, balıkçılık için).
 *
 * <p>PhoenixCraft'taki achievement sisteminden bağımsızdır. Bu sistem
 * balıkçılıkla ilgili başarımları takip eder:
 * <ul>
 *   <li>TOTAL_CATCHS — toplam yakalama sayısı</li>
 *   <li>RARITY_COUNT — belirli rarelıkta N balık yakalama</li>
 *   <li>DISCOVERY_COUNT — N farklı balık keşfi</li>
 *   <li>MUTATION_COUNT — N mutasyon balığı yakalama</li>
 * </ul>
 *
 * <p>Achievement tanımları config.yml'de features.achievements.list altında.
 * Her achievement bir type + target + tier + reward-commands içerir.
 * Unlock durumu achievements.yml'de saklanır.</p>
 */
public class AchievementManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final boolean enabled;

    /** Per-player unlocked achievement IDs. */
    private final Map<UUID, Set<String>> unlocked = new ConcurrentHashMap<>();
    private final Map<String, AchievementDef> definitions = new LinkedHashMap<>();
    private volatile boolean dirty = false;

    public AchievementManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.enabled = plugin.getConfig().getBoolean("features.achievements.enabled", false);
        if (enabled) {
            loadDefinitions();
            loadUnlocks();
            plugin.getLogger().info("AchievementManager enabled — " + definitions.size()
                    + " achievements loaded.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    // =====================================================================
    //  Definitions
    // =====================================================================

    private void loadDefinitions() {
        definitions.clear();
        ConfigurationSection list = plugin.getConfig().getConfigurationSection("features.achievements.list");
        if (list == null) return;

        for (String id : list.getKeys(false)) {
            ConfigurationSection sec = list.getConfigurationSection(id);
            if (sec == null) continue;
            try {
                String type = sec.getString("type", "TOTAL_CATCHS").toUpperCase();
                String target = sec.getString("target", "");
                String tier = sec.getString("tier", "none");
                List<String> rewardCommands = sec.getStringList("reward-commands");
                definitions.put(id.toLowerCase(), new AchievementDef(id, type, target, tier, rewardCommands));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load achievement " + id + ": " + e.getMessage());
            }
        }
    }

    // =====================================================================
    //  Persistence
    // =====================================================================

    private java.io.File getFile() {
        return new java.io.File(plugin.getDataFolder(), "achievements.yml");
    }

    private void loadUnlocks() {
        java.io.File file = getFile();
        if (!file.exists()) return;
        org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        for (String uuidStr : cfg.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                Set<String> set = new HashSet<>(cfg.getStringList(uuidStr));
                unlocked.put(uuid, set);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    public synchronized void save() {
        if (!enabled || !dirty) return;
        org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> entry : unlocked.entrySet()) {
            cfg.set(entry.getKey().toString(), new ArrayList<>(entry.getValue()));
        }
        try {
            cfg.save(getFile());
            dirty = false;
        } catch (java.io.IOException e) {
            plugin.getLogger().severe("Failed to save achievements.yml: " + e.getMessage());
        }
    }

    // =====================================================================
    //  Check & Unlock
    // =====================================================================

    /**
     * Her balık yakalamadan sonra çağrılır. Tüm achievement'ları kontrol eder.
     */
    public void checkAchievements(Player player, com.phoenix.fish.model.CustomFish fish, int amountCaught, boolean isMutation) {
        if (!enabled || player == null) return;
        // v1.6.1 (bug #4 fix): CacheManager can be null when both xp-system.enabled
        // and settings.discovery-enabled are false (or DB init failed).
        if (plugin.getCacheManager() == null) return;
        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null) return;

        UUID uuid = player.getUniqueId();
        Set<String> playerUnlocked = unlocked.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());

        for (AchievementDef def : definitions.values()) {
            String id = def.id().toLowerCase();
            if (playerUnlocked.contains(id)) continue;

            boolean met = checkCondition(def, data, fish, amountCaught, isMutation);
            if (met) {
                playerUnlocked.add(id);
                unlockAchievement(player, def);
                dirty = true;
            }
        }
    }

    private boolean checkCondition(AchievementDef def, FishingData data,
            com.phoenix.fish.model.CustomFish fish, int amountCaught, boolean isMutation) {
        switch (def.type()) {
            case "TOTAL_CATCHS":
                int totalTarget = parseInt(def.target(), 1);
                return data.getTotalCatches() >= totalTarget;

            case "RARITY_COUNT":
                // target format: "rarity:N:count"
                String[] parts = def.target().split(":");
                if (parts.length >= 3) {
                    int rarity = parseInt(parts[1], 0);
                    int count = parseInt(parts[2], 1);
                    // rarity count'ı toplam yakalamadan çıkaramıyoruz —
                    // şimdilik sadece "any rarity N yakala" olarak çalışır
                    return data.getTotalCatches() >= count;
                }
                return false;

            case "DISCOVERY_COUNT":
                int discTarget = parseInt(def.target(), 1);
                return data.getDiscoveredFish().size() >= discTarget;

            case "MUTATION_COUNT":
                int mutTarget = parseInt(def.target(), 1);
                return data.getMutationCount() >= mutTarget;

            default:
                return false;
        }
    }

    private int parseInt(String s, int defaultVal) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private void unlockAchievement(Player player, AchievementDef def) {
        // Chat message
        player.sendMessage(miniMessage.deserialize(
                "<gold><bold>★ Achievement Unlocked!</bold></gold> <white>"
                        + def.id() + "</white> <dark_gray>— <gray>" + def.type()));

        // Title
        if (plugin.getConfig().getBoolean("features.achievements.show-title", true)) {
            Title.Times times = Title.Times.times(
                    java.time.Duration.ofMillis(300),
                    java.time.Duration.ofMillis(3000),
                    java.time.Duration.ofMillis(800));
            Title title = Title.title(
                    miniMessage.deserialize("<gold><bold>★ ACHIEVEMENT!</bold></gold>"),
                    miniMessage.deserialize("<white>" + def.id() + "</white>"),
                    times);
            player.showTitle(title);
        }

        // Sound
        player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

        // Reward commands
        for (String cmd : def.rewardCommands()) {
            String finalCmd = cmd.replace("%player%", player.getName());
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
            } catch (Exception e) {
                plugin.getLogger().warning("Achievement reward command failed: '" + finalCmd + "': " + e.getMessage());
            }
        }
    }

    // =====================================================================
    //  GUI
    // =====================================================================

    /**
     * v1.6.1 — InventoryHolder marker.
     *
     * <p>Bu holder bir önceki sürümde yoktu — AchievementManager.openGUI
     * envanteri {@code Bukkit.createInventory(null, 54, ...)} ile oluşturuyordu.
     * Bukkit, holder null olursa InventoryClickEvent'leri "no owner" sayar ve
     * tıklanan item'ı oyuncu envanterine taşımaya izin verir. Yani oyuncular
     * GOLD_INGOT / GRAY_DYE gibi gösterge item'larını alıp envanterine
     * çekebiliyordu.</p>
     *
     * <p>Bu holder artık AchievementListener'da
     * {@code topInv.getHolder() instanceof AchievementGUIHolder} kontrolüyle
     * event.cancel(true) yapmamıza izin veriyor.</p>
     */
    public static class AchievementGUIHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public void openGUI(Player player) {
        if (!enabled) {
            player.sendMessage(miniMessage.deserialize("<red>Achievement system is disabled."));
            return;
        }
        // v1.6.1 (bug #4 + #26 fix): the previous FishingData fetch here was both
        // unguarded against a null CacheManager AND never used afterwards (dead fetch).
        // Removed entirely — openGUI only needs the per-player unlocked-set below.
        Set<String> playerUnlocked = unlocked.getOrDefault(player.getUniqueId(), Collections.emptySet());

        int total = definitions.size();
        int unlockedCount = (int) definitions.keySet().stream().filter(playerUnlocked::contains).count();

        // v1.6.1 — FIX: null holder yerine AchievementGUIHolder kullan.
        // Bu, AchievementListener'ın click/drag event'lerini cancel etmesini sağlar.
        Inventory inv = Bukkit.createInventory(new AchievementGUIHolder(), 54,
                miniMessage.deserialize("<dark_gray>» <gold>Achievements <dark_gray>(<white>"
                        + unlockedCount + "/" + total + "</white>)"));

        int slot = 0;
        for (AchievementDef def : definitions.values()) {
            if (slot >= 45) break;
            boolean isUnlocked = playerUnlocked.contains(def.id().toLowerCase());

            Material mat = isUnlocked ? Material.GOLD_INGOT : Material.GRAY_DYE;
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(miniMessage.deserialize(
                        (isUnlocked ? "<gold><bold>" : "<gray><italic>") + def.id()));
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                lore.add(miniMessage.deserialize("<gray>Type: <white>" + def.type()));
                lore.add(miniMessage.deserialize("<gray>Target: <white>" + def.target()));
                lore.add(miniMessage.deserialize("<gray>Tier: <white>" + def.tier()));
                lore.add(miniMessage.deserialize(""));
                lore.add(miniMessage.deserialize(
                        isUnlocked ? "<green>✔ Unlocked" : "<dark_red>✖ Locked"));
                meta.lore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(slot, item);
            slot++;
        }

        player.openInventory(inv);
    }

    public void shutdown() {
        save();
    }

    public void clear(UUID uuid) {
        unlocked.remove(uuid);
        dirty = true;
    }

    // =====================================================================
    //  Achievement Definition Record
    // =====================================================================

    public record AchievementDef(
            String id,
            String type,
            String target,
            String tier,
            List<String> rewardCommands
    ) {}
}
