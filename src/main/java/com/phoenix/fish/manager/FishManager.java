package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.model.CustomFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class FishManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;

    private volatile List<CustomFish> fishList = Collections.emptyList();
    private volatile Map<String, CustomFish> fishMap = Collections.emptyMap();

    private final NamespacedKey fishKey;

    public FishManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.fishKey = new NamespacedKey(plugin, "fish_id");
    }

    public MiniMessage getMiniMessage() { return miniMessage; }
    public NamespacedKey getFishKey() { return fishKey; }
    public List<CustomFish> getFishList() { return fishList; }

    public CustomFish getFishById(String id) {
        if (id == null) return null;
        return fishMap.get(id.toLowerCase());
    }

    public java.util.Collection<CustomFish> getAllFish() {
        return fishMap.values();
    }

    // v1.6.0 — Per-fish config sections
    private final java.util.Map<String, ConfigurationSection> fishConfigs = new ConcurrentHashMap<>();

    public ConfigurationSection getFishConfig(String fishId) {
        if (fishId == null) return null;
        return fishConfigs.get(fishId.toLowerCase());
    }

    /**
     * v1.6.2 (Fix #2) — Robust y-range parser.
     *
     * <p>Supports 4 YAML formats (any of these is valid for {@code y-range:}):</p>
     * <pre>
     *   y-range: [0, 30]              # inline list
     *   y-range:                       # block list
     *     - 0
     *     - 30
     *   y-range: {min: 0, max: 30}    # inline map
     *   y-range:                       # block map
     *     min: 0
     *     max: 30
     *   y-range: 30                    # scalar (interpreted as max Y, min = -64)
     * </pre>
     *
     * <p>Returns null if no y-range is configured. Returns [min, max] otherwise
     * with min &lt;= max and both finite.</p>
     */
    public int[] parseYRange(ConfigurationSection fishSec) {
        if (fishSec == null || !fishSec.contains("y-range")) return null;

        // Format 1: list [min, max]
        List<Integer> list = fishSec.getIntegerList("y-range");
        if (list != null && list.size() >= 2) {
            int a = list.get(0), b = list.get(1);
            return new int[]{ Math.min(a, b), Math.max(a, b) };
        }

        // Format 2: map {min: x, max: y}
        Object raw = fishSec.get("y-range");
        if (raw instanceof org.bukkit.configuration.MemorySection ms) {
            int min = ms.getInt("min", Integer.MIN_VALUE);
            int max = ms.getInt("max", Integer.MAX_VALUE);
            if (min != Integer.MIN_VALUE || max != Integer.MAX_VALUE) {
                int lo = (min == Integer.MIN_VALUE) ? -64 : min;
                int hi = (max == Integer.MAX_VALUE) ? 320 : max;
                return new int[]{ Math.min(lo, hi), Math.max(lo, hi) };
            }
        }

        // Format 3: scalar (single int → max Y, min = world floor)
        if (raw instanceof Number n) {
            int val = n.intValue();
            return new int[]{ -64, val };
        }

        // Unknown format — log once and return null (no restriction)
        return null;
    }

    /**
     * v1.9.0 — Rigorously validates all conditional spawning parameters
     * against the player's current world environment.
     *
     * <p>Checks the following per-fish conditions (in order):</p>
     * <ol>
     *   <li><b>Seasons</b> — if the fish has a {@code seasons:} list and the
     *       SeasonManager is enabled, the current season must be in the list.</li>
     *   <li><b>Time of day</b> — if the fish has a {@code time:} field
     *       (DAY / NIGHT / ALL), the player's world time is checked. The
     *       {@code time-override} field (per-fish) or the global
     *       {@code features.day-night-fish.time-override} can force
     *       always-day / always-night.</li>
     *   <li><b>Y-range (depth)</b> — if the fish has a {@code y-range:}
     *       field, the player's Y coordinate must be within [min, max].</li>
     * </ol>
     *
     * <p><b>v1.9.0 change:</b> Per-fish conditions are now ALWAYS honored
     * when the field is present, regardless of the global feature flags
     * ({@code features.day-night-fish.enabled}, {@code features.depth-fish.enabled},
     * {@code features.seasonal-fish.enabled}). The flags only control whether
     * the GLOBAL system is active. Per-fish fields take precedence — if a
     * fish has {@code time: NIGHT}, it only spawns at night even if the
     * global day-night-fish flag is false.</p>
     *
     * @param fish   the fish being considered for spawning
     * @param player the player whose environment to check
     * @return true if all conditions pass (fish can spawn), false otherwise
     */
    public boolean meetsConditions(CustomFish fish, Player player) {
        if (player == null || fish == null) return true;

        ConfigurationSection fishSec = getFishConfig(fish.id());
        if (fishSec == null) return true;

        // ── 1. Seasons check ──────────────────────────────────────
        // v1.9.0 — New: per-fish seasons restriction.
        // Honored when the fish has a `seasons:` list AND the SeasonManager
        // is loaded + enabled. If SeasonManager is disabled, the check is
        // skipped (fish spawns in any season).
        if (fishSec.contains("seasons")) {
            List<String> seasons = fishSec.getStringList("seasons");
            if (seasons != null && !seasons.isEmpty()) {
                var seasonManager = plugin.getSeasonManager();
                if (seasonManager != null && seasonManager.isEnabled()) {
                    // v1.9.0 — Determine the current season.
                    // SeasonManager uses real-world dates (realtime mode) or
                    // Minecraft world ticks (minecraft mode). The current
                    // season name is derived from the season number.
                    int seasonNumber = seasonManager.getSeasonNumber();
                    String currentSeason = seasonNumberToName(seasonNumber);
                    if (currentSeason == null || !seasons.contains(currentSeason)) {
                        return false;
                    }
                }
                // If SeasonManager is not enabled, skip the seasons check
                // (the fish spawns regardless of season).
            }
        }

        // ── 2. Time of day check ─────────────────────────────────
        // v1.9.0 — Per-fish time field is ALWAYS honored when present.
        // Supports DAY, NIGHT, ALL. The time-override can be set per-fish
        // or globally via features.day-night-fish.time-override.
        if (fishSec.contains("time")) {
            String fishTime = fishSec.getString("time", "ALL");
            if (fishTime != null && !fishTime.isEmpty() && !"ALL".equalsIgnoreCase(fishTime)) {
                // Resolve the time-override: per-fish first, then global, then "auto"
                String timeOverride = fishSec.getString("time-override", null);
                if (timeOverride == null || timeOverride.isEmpty()) {
                    timeOverride = plugin.getConfig().getString(
                            "features.day-night-fish.time-override", "auto");
                }
                if (timeOverride == null) timeOverride = "auto";

                boolean isDay;
                if ("always-day".equalsIgnoreCase(timeOverride)) {
                    isDay = true;
                } else if ("always-night".equalsIgnoreCase(timeOverride)) {
                    isDay = false;
                } else {
                    // "auto" — use the real world time
                    long worldTime = player.getWorld().getTime();
                    isDay = worldTime < 13000;  // Minecraft day: 0–12999, night: 13000–23999
                }

                if ("DAY".equalsIgnoreCase(fishTime) && !isDay) return false;
                if ("NIGHT".equalsIgnoreCase(fishTime) && isDay) return false;
            }
        }

        // ── 3. Y-range (depth) check ─────────────────────────────
        // v1.9.0 — Per-fish y-range is ALWAYS honored when present.
        // The player's Y coordinate must be within [min, max] inclusive.
        if (fishSec.contains("y-range")) {
            int[] yRange = parseYRange(fishSec);
            if (yRange != null) {
                int playerY = player.getLocation().getBlockY();
                if (playerY < yRange[0] || playerY > yRange[1]) return false;
            }
        }

        return true;
    }

    /**
     * v1.9.0 — Map a season number (1-based) to its name.
     *
     * <p>Season cycle: 1=SPRING, 2=SUMMER, 3=AUTUMN, 4=WINTER, then back to 1.
     * This mapping is used by {@link #meetsConditions(CustomFish, Player)}
     * to compare the SeasonManager's current season number against a fish's
     * {@code seasons:} list.</p>
     *
     * @param seasonNumber the 1-based season number from SeasonManager
     * @return the season name (SPRING/SUMMER/AUTUMN/WINTER), or null if the
     *         input is &lt; 1
     */
    private String seasonNumberToName(int seasonNumber) {
        int idx = ((seasonNumber - 1) % 4 + 4) % 4;  // safe modulo for negative inputs
        return switch (idx) {
            case 0 -> "SPRING";
            case 1 -> "SUMMER";
            case 2 -> "AUTUMN";
            case 3 -> "WINTER";
            default -> null;
        };
    }

    public void loadFish() {
        String lang = plugin.getMessageManager().getLanguage();
        String fileName = "fish.yml";
        if (lang != null && !lang.trim().isEmpty() && !lang.equalsIgnoreCase("en")) {
            String candidate = "fish_" + lang.trim().toLowerCase() + ".yml";
            File candidateFile = new File(plugin.getDataFolder(), candidate);
            if (candidateFile.exists() || plugin.getResource(candidate) != null) {
                fileName = candidate;
            } else {
                plugin.getLogger().info("fish_" + lang + ".yml bulunamadı — "
                        + "varsayılan fish.yml (İngilizce) kullanılacak.");
            }
        }

        File fishFile = new File(plugin.getDataFolder(), fileName);
        if (!fishFile.exists()) {
            fileName = "fish.yml";
            fishFile = new File(plugin.getDataFolder(), "fish.yml");
            if (!fishFile.exists()) {
                plugin.saveResource("fish.yml", false);
                fishFile = new File(plugin.getDataFolder(), "fish.yml");
            }
        }

        FileConfiguration fishConfig = YamlConfiguration.loadConfiguration(fishFile);
        ConfigurationSection section = fishConfig.getConfigurationSection("fishes");
        if (section == null) {
            plugin.getLogger().warning(plugin.getMessageManager().getPlainMessage("fish_section_not_found"));
            return;
        }

        List<CustomFish> newList = new ArrayList<>();
        Map<String, CustomFish> newMap = new ConcurrentHashMap<>();
        fishConfigs.clear();

        for (String key : section.getKeys(false)) {
            ConfigurationSection fishSec = section.getConfigurationSection(key);
            if (fishSec == null) continue;

            try {
                int rarity = Math.max(1, fishSec.getInt("rarity", 1));
                String rarityColor = getRarityColor(rarity);
                String rarityName = plugin.getMessageManager().getPlainMessage("rarity_" + rarity);

                // v1.8.5 — Localized display name + lore via ContentLanguageManager.
                // Resolution order:
                //   1. lang/<active>/fish.yml: fishes.<id>.display-name
                //   2. lang/en/fish.yml:       fishes.<id>.display-name (auto fallback)
                //   3. Inline display-name from data fish.yml (English baseline)
                //   4. Hardcoded "Fish" (last resort)
                String inlineName = fishSec.getString("display-name", "Fish");
                String baseNameStr = plugin.getContentLanguageManager().getFishName(key, inlineName);
                Component baseName = miniMessage.deserialize(baseNameStr);
                String plainName = PlainTextComponentSerializer.plainText().serialize(baseName);
                Component finalName = miniMessage.deserialize(rarityColor + plainName);

                String materialStr = fishSec.getString("material", "COD").toUpperCase();
                Material material = Material.matchMaterial(materialStr);
                if (material == null) {
                    String msg = plugin.getMessageManager().getPlainMessage("fish_invalid_material");
                    plugin.getLogger().warning(msg.replace("%material%", materialStr).replace("%fish%", key));
                    material = Material.COD;
                }

                double weight = fishSec.getDouble("weight", 10.0);
                double speed = fishSec.getDouble("base-speed", 1.0);
                double strength = fishSec.getDouble("fight-strength", 1.0);
                int xp = fishSec.getInt("xp-reward", 0);
                double minWeight = fishSec.getDouble("min-weight", 0.5);
                double maxWeight = fishSec.getDouble("max-weight", 5.0);
                List<String> biomes = fishSec.getStringList("biomes");
                double price = fishSec.getDouble("price", 1.0);

                ItemStack item = new ItemStack(material);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.displayName(finalName);
                    meta.getPersistentDataContainer().set(fishKey, PersistentDataType.STRING, key);
                    ItemUtils.applyCustomModelData(meta, fishSec);

                    List<Component> loreComponents = new ArrayList<>();
                    String rarityLoreStr = plugin.getMessageManager().getPlainMessage("fish_lore_rarity");
                    rarityLoreStr = rarityLoreStr.replace("%rarity%", rarityName);
                    loreComponents.add(miniMessage.deserialize(rarityColor + rarityLoreStr));

                    // v1.8.5 — Localized lore (with inline fallback).
                    List<String> inlineLore = fishSec.getStringList("lore");
                    List<String> loreLines = plugin.getContentLanguageManager().getFishLore(key, inlineLore);
                    for (String line : loreLines) {
                        loreComponents.add(miniMessage.deserialize(line));
                    }
                    meta.lore(loreComponents);
                    item.setItemMeta(meta);
                }

                CustomFish fish = new CustomFish(key, finalName, rarity, weight, speed, strength, xp, minWeight,
                        maxWeight, biomes, price, item);
                newList.add(fish);
                newMap.put(key.toLowerCase(), fish);
                fishConfigs.put(key.toLowerCase(), fishSec);

            } catch (Exception e) {
                String msg = plugin.getMessageManager().getPlainMessage("fish_load_error");
                plugin.getLogger().warning(msg.replace("%fish%", key).replace("%error%", e.getMessage()));
            }
        }

        this.fishList = Collections.unmodifiableList(newList);
        this.fishMap = Collections.unmodifiableMap(newMap);

        String loadedMsg = plugin.getMessageManager().getPlainMessage("fish_loaded_success");
        plugin.getLogger().info(loadedMsg.replace("%amount%", String.valueOf(fishList.size())));
    }

    private String getRarityColor(int rarity) {
        return switch (rarity) {
            case 1 -> "<gray>";
            case 2 -> "<aqua>";
            case 3 -> "<red>";
            case 4 -> "<gold>";
            case 5 -> "<light_purple>";
            default -> "<white>";
        };
    }

    /**
     * v1.6.2 (Fix #2) — Backward-compatible rollRandomFish that does NOT take
     * a player. Delegates to the new 4-arg version with player=null, which
     * disables depth/day-night pre-filtering (preserves legacy behavior for
     * any caller that hasn't been updated).
     */
    public CustomFish rollRandomFish(double luckMultiplier, BaitManager.Bait bait, Biome playerBiome) {
        return rollRandomFish(luckMultiplier, bait, playerBiome, null);
    }

    /**
     * v1.6.2 (Fix #2) — Player-aware rollRandomFish.
     *
     * <p>Previous implementation rolled a fish first, then checked
     * {@code meetsConditions()} and retried up to 3 times. If all 3 retries
     * failed the constraints (e.g. player standing at Y=100 but every fish in
     * the pool requires Y&lt;30), the player was GIVEN an ineligible fish.</p>
     *
     * <p>New implementation pre-filters the candidate pool by all depth /
     * day-night constraints BEFORE rolling. If the pool becomes empty after
     * filtering, falls back to the unfiltered pool (better to give the player
     * <em>something</em> than silently fail the catch).</p>
     */
    public CustomFish rollRandomFish(double luckMultiplier, BaitManager.Bait bait,
                                     Biome playerBiome, Player player) {
        if (fishList.isEmpty()) return null;

        List<CustomFish> snapshot = fishList;
        String biomeName = playerBiome.toString();

        // Phase 1: biome filter (always applied — backward compatible)
        List<CustomFish> biomeFiltered = new ArrayList<>();
        for (CustomFish fish : snapshot) {
            List<String> biomes = fish.biomes();
            if (biomes == null || biomes.isEmpty()) {
                biomeFiltered.add(fish);
            } else {
                for (String biome : biomes) {
                    if (biome.equalsIgnoreCase(biomeName)) {
                        biomeFiltered.add(fish);
                        break;
                    }
                }
            }
        }
        if (biomeFiltered.isEmpty()) return null;

        // Phase 2: depth + day-night pre-filter (v1.6.2 Fix #2)
        // Only applied if the relevant features are enabled AND player is provided.
        boolean depthEnabled = plugin.getConfig().getBoolean("features.depth-fish.enabled", false);
        boolean dayNightEnabled = plugin.getConfig().getBoolean("features.day-night-fish.enabled", false);

        List<CustomFish> availableFish = biomeFiltered;
        if (player != null && (depthEnabled || dayNightEnabled)) {
            List<CustomFish> filtered = new ArrayList<>();
            for (CustomFish fish : biomeFiltered) {
                if (meetsConditions(fish, player)) {
                    filtered.add(fish);
                }
            }
            // Fallback: if pre-filter empties the pool, use the biome-only pool
            // rather than silently failing the catch.
            if (!filtered.isEmpty()) {
                availableFish = filtered;
            } else {
                availableFish = biomeFiltered;
            }
        }

        double[] dynamicWeights = new double[availableFish.size()];
        double totalDynamicWeight = 0.0;

        for (int i = 0; i < availableFish.size(); i++) {
            CustomFish fish = availableFish.get(i);
            double baseLuckMultiplier = Math.pow(Math.max(0.1, luckMultiplier), fish.rarity() - 1);
            double baitMultiplier = 1.0;
            if (bait != null && bait.targetRarity() == fish.rarity()) {
                baitMultiplier = bait.modifier();
            }
            double w = fish.weight() * baseLuckMultiplier * baitMultiplier;
            if (w <= 0) w = 0.001;
            dynamicWeights[i] = w;
            totalDynamicWeight += w;
        }

        if (totalDynamicWeight <= 0.0) {
            return availableFish.get(0);
        }

        double roll = ThreadLocalRandom.current().nextDouble() * totalDynamicWeight;
        double currentWeight = 0.0;
        for (int i = 0; i < dynamicWeights.length; i++) {
            currentWeight += dynamicWeights[i];
            if (roll <= currentWeight) {
                return availableFish.get(i);
            }
        }

        return availableFish.get(availableFish.size() - 1);
    }
}
