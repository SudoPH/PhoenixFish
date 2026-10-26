package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BaitManager {

    private static final double MIN_MODIFIER = 0.1;
    private static final double MAX_MODIFIER = 10.0;

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final Map<String, Bait> baits = new ConcurrentHashMap<>();
    private final Map<ItemFixer.ItemIdentifier, Bait> baitItemCache = new ConcurrentHashMap<>();
    /** v1.3.5 — Yem başına orijinal ConfigurationSection (minigame: bonuslarını okumak için). */
    private final Map<String, ConfigurationSection> baitConfigs = new ConcurrentHashMap<>();
    private final NamespacedKey baitKey;

    public BaitManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.baitKey = new NamespacedKey(plugin, "bait_id");
    }

    public void loadBaits() {
        baits.clear();
        baitItemCache.clear();
        baitConfigs.clear();

        // v1.3.5 — Dil bazlı bait dosyası yükleme.
        // Sıra: baits_<dil>.yml → baits.yml (fallback).
        // Dil bazlı dosya yoksa doğrudan baits.yml kullanılır.
        String lang = null;
        try {
            lang = plugin.getMessageManager().getLanguage();
        } catch (Throwable ignored) {
            // MessageManager henüz yüklenmediyse null bırak.
        }
        String fileName = "baits.yml";
        if (lang != null && !lang.trim().isEmpty() && !lang.equalsIgnoreCase("en")) {
            String candidate = "baits_" + lang.trim().toLowerCase() + ".yml";
            File candidateFile = new File(plugin.getDataFolder(), candidate);
            if (candidateFile.exists() || plugin.getResource(candidate) != null) {
                fileName = candidate;
            }
        }

        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            fileName = "baits.yml";
            file = new File(plugin.getDataFolder(), "baits.yml");
            if (!file.exists()) {
                plugin.saveResource("baits.yml", false);
                file = new File(plugin.getDataFolder(), "baits.yml");
            }
        }

        plugin.getLogger().info("Loading baits from: " + fileName);

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("baits");
        if (section == null) {
            plugin.getLogger().warning("No 'baits' section found in " + fileName);
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec == null)
                continue;

            try {
                Component name = miniMessage.deserialize(sec.getString("display-name", "<white>Bait</white>"));
                int targetRarity = Math.max(1, sec.getInt("target-rarity", 1));

                double modifier = sec.getDouble("chance-modifier", 1.0);
                if (modifier < MIN_MODIFIER) {
                    plugin.getLogger().warning("Bait " + key + " chance-modifier " + modifier
                            + " too low, clamping to " + MIN_MODIFIER);
                    modifier = MIN_MODIFIER;
                } else if (modifier > MAX_MODIFIER) {
                    plugin.getLogger().warning("Bait " + key + " chance-modifier " + modifier
                            + " too high, clamping to " + MAX_MODIFIER);
                    modifier = MAX_MODIFIER;
                }

                String materialStr = sec.getString("material", "STICK").toUpperCase();
                Material material = Material.matchMaterial(materialStr);
                if (material == null) {
                    plugin.getLogger().warning("Invalid material " + materialStr + " for bait " + key);
                    material = Material.STICK;
                }

                ItemStack item = new ItemStack(material);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.displayName(name);
                    meta.getPersistentDataContainer().set(baitKey, PersistentDataType.STRING, key);
                    ItemUtils.applyCustomModelData(meta, sec);

                    if (sec.contains("lore")) {
                        List<Component> loreComponents = new ArrayList<>();
                        for (String line : sec.getStringList("lore")) {
                            loreComponents.add(miniMessage.deserialize(line));
                        }
                        meta.lore(loreComponents);
                    }
                    item.setItemMeta(meta);
                }

                Bait bait = new Bait(key, targetRarity, modifier, item);
                baits.put(key, bait);
                baitConfigs.put(key, sec);  // v1.3.5 — minigame bonusları için sakla

                int cmd = ItemUtils.getCMD(item);
                if (cmd > 0) {
                    ItemFixer.ItemIdentifier identifier = new ItemFixer.ItemIdentifier(material, cmd);
                    // H-9 FIX: detect duplicate (material, cmd) keys and warn — previously the second
                    // entry silently overwrote the first, leaving legacy items resolving to the wrong bait.
                    if (baitItemCache.putIfAbsent(identifier, bait) != null) {
                        plugin.getLogger().warning("Duplicate bait cache key for bait " + key
                                + " (material=" + material + ", cmd=" + cmd
                                + ") — conflicts with an earlier bait. Legacy items may resolve wrong.");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load bait " + key + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info("Loaded " + baits.size() + " baits.");
    }

    /**
     * v1.3.5 — Yemin orijinal ConfigurationSection'unu döner (baits.yml'den).
     * MinigameTask, yemin {@code minigame:} bölümündeki bonusları okumak için kullanır.
     *
     * @param id yem id'si
     * @return yemin ConfigurationSection'u, ya da yem yoksa null
     */
    public ConfigurationSection getBaitConfig(String id) {
        if (id == null) return null;
        return baitConfigs.get(id);
    }

    public Bait getBaitFromItem(ItemStack item) {
        if (item == null)
            return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return null;

        String baitId = meta.getPersistentDataContainer().get(baitKey, PersistentDataType.STRING);
        if (baitId != null) {
            return baits.get(baitId);
        }

        int cmd = ItemUtils.getCMD(meta);
        if (cmd <= 0)
            return null;

        return baitItemCache.get(new ItemFixer.ItemIdentifier(item.getType(), cmd));
    }

    public ItemStack getBaitItem(String id) {
        Bait bait = baits.get(id);
        return bait != null ? bait.item().clone() : null;
    }

    public NamespacedKey getBaitKey() {
        return baitKey;
    }

    public Map<String, Bait> getBaits() {
        return Collections.unmodifiableMap(baits);
    }

    public record Bait(String id, int targetRarity, double modifier, ItemStack item) {
    }
}
