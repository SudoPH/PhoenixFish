package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
        // v1.9.1 — Rod-bound bait keys. Initialized here (not in field
        // initializers) because field initializers run before the
        // constructor assigns `plugin`, causing "variable plugin might
        // not have been initialized" compile errors.
        this.rodBaitKey = new NamespacedKey(plugin, "rod_bait_id");
        this.rodBaitChargesKey = new NamespacedKey(plugin, "rod_bait_charges");
    }

    public void loadBaits() {
        baits.clear();
        baitItemCache.clear();
        baitConfigs.clear();

        // v1.8.5 — Bait data (material, target-rarity, chance-modifier, custom-model-data)
        // is always loaded from baits.yml at the data folder root. The localized
        // display-name + lore are looked up per-bait via ContentLanguageManager.
        File file = new File(plugin.getDataFolder(), "baits.yml");
        if (!file.exists()) {
            plugin.saveResource("baits.yml", false);
            file = new File(plugin.getDataFolder(), "baits.yml");
        }

        plugin.getLogger().info("Loading baits from: baits.yml (lang="
                + plugin.getContentLanguageManager().getLanguage() + ")");

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("baits");
        if (section == null) {
            plugin.getLogger().warning("No 'baits' section found in baits.yml");
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec == null)
                continue;

            try {
                // v1.8.5 — Localized display-name + lore via ContentLanguageManager.
                String inlineName = sec.getString("display-name", "<white>Bait</white>");
                String localizedName = plugin.getContentLanguageManager().getBaitName(key, inlineName);
                Component name = miniMessage.deserialize(localizedName);
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

                    // v1.8.5 — Localized lore (with inline fallback).
                    List<String> inlineLore = sec.getStringList("lore");
                    List<String> loreLines = plugin.getContentLanguageManager().getBaitLore(key, inlineLore);
                    if (!loreLines.isEmpty()) {
                        List<Component> loreComponents = new ArrayList<>(loreLines.size());
                        for (String line : loreLines) {
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

    /**
     * v1.9.1 — Resolve the active bait from an ItemStack.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>PDC key {@code bait_id} on the item's meta → lookup in the baits map</li>
     *   <li>Fallback by (material, custom-model-data) → lookup in the baitItemCache</li>
     * </ol>
     *
     * <p><b>v1.9.1 null-safety fixes:</b></p>
     * <ul>
     *   <li>If the PDC key references a bait ID that no longer exists in the
     *       baits map (e.g. the bait was removed from baits.yml but the item
     *       still has the old key), returns null instead of returning null
     *       silently — the caller already null-checks, so this is safe.</li>
     *   <li>If the item has no ItemMeta (e.g. air or a vanilla item), returns
     *       null without throwing.</li>
     *   <li>The baitItemCache fallback is also null-guarded.</li>
     * </ul>
     *
     * @param item the item to check (may be null — returns null)
     * @return the resolved Bait, or null if the item is not a bait
     */
    public Bait getBaitFromItem(ItemStack item) {
        if (item == null || item.getType().isAir())
            return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return null;

        // Path 1: PDC key lookup
        String baitId = meta.getPersistentDataContainer().get(baitKey, PersistentDataType.STRING);
        if (baitId != null) {
            Bait b = baits.get(baitId);
            if (b != null) return b;
            // v1.9.1 — Stale PDC key (bait removed from config). Fall through
            // to the CMD-based cache lookup as a last resort.
        }

        // Path 2: (material, custom-model-data) cache lookup
        int cmd = ItemUtils.getCMD(meta);
        if (cmd <= 0)
            return null;
        Bait cached = baitItemCache.get(new ItemFixer.ItemIdentifier(item.getType(), cmd));
        return cached;  // may be null — caller null-checks
    }

    // =====================================================================
    //  v1.9.1 — Rod-bound bait API
    //  Allows players to "equip" a bait onto a rod via right-click. The
    //  bait's ID is stored in the rod's PDC under the rodBaitKey. When the
    //  rod is used for fishing, the bound bait takes effect (consuming
    //  charges from the player's bait stack in the off-hand or inventory).
    // =====================================================================

    private final NamespacedKey rodBaitKey;
    private final NamespacedKey rodBaitChargesKey;

    /**
     * v1.9.1 — Bind a bait to a fishing rod.
     *
     * <p>Stores the bait ID in the rod's PDC under {@code rod_bait_id}.
     * The rod's existing custom NBT data (luck multiplier, custom model
     * data, display name, lore) is preserved — only the bait key is added.</p>
     *
     * @param rodItem the fishing rod ItemStack (must have ItemMeta)
     * @param baitId  the bait ID to bind (must exist in the baits map)
     * @return true if the bait was bound successfully, false if the rod
     *         has no meta or the bait ID is unknown
     */
    public boolean bindBaitToRod(ItemStack rodItem, String baitId) {
        if (rodItem == null || baitId == null) return false;
        if (!rodItem.getType().name().endsWith("FISHING_ROD")) return false;
        if (!baits.containsKey(baitId)) return false;
        ItemMeta meta = rodItem.getItemMeta();
        if (meta == null) return false;

        // v1.9.1 — Only set the bait key; preserve all other PDC keys + meta.
        meta.getPersistentDataContainer().set(rodBaitKey, PersistentDataType.STRING, baitId);
        rodItem.setItemMeta(meta);
        return true;
    }

    /**
     * v1.9.1 — Get the bait bound to a fishing rod (if any).
     *
     * @param rodItem the fishing rod ItemStack
     * @return the bound Bait, or null if no bait is bound / rod is invalid
     */
    public Bait getBaitFromRod(ItemStack rodItem) {
        if (rodItem == null || rodItem.getType().isAir()) return null;
        ItemMeta meta = rodItem.getItemMeta();
        if (meta == null) return null;
        String baitId = meta.getPersistentDataContainer().get(rodBaitKey, PersistentDataType.STRING);
        if (baitId == null) return null;
        return baits.get(baitId);  // may be null if bait was removed from config
    }

    /**
     * v1.9.1 — Clear the bound bait from a fishing rod.
     *
     * @param rodItem the fishing rod ItemStack
     * @return true if a bait was cleared, false if no bait was bound
     */
    public boolean clearBaitFromRod(ItemStack rodItem) {
        if (rodItem == null) return false;
        ItemMeta meta = rodItem.getItemMeta();
        if (meta == null) return false;
        if (!meta.getPersistentDataContainer().has(rodBaitKey, PersistentDataType.STRING)) {
            return false;
        }
        meta.getPersistentDataContainer().remove(rodBaitKey);
        rodItem.setItemMeta(meta);
        return true;
    }

    /**
     * v1.9.1 — Resolve the effective bait for a fishing cast.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>If the rod has a bound bait (via {@link #bindBaitToRod}), use it.</li>
     *   <li>Otherwise, check the player's off-hand for a bait item.</li>
     *   <li>Otherwise, return null (no bait active).</li>
     * </ol>
     *
     * <p>This is the single entry point FishingListener should use. It
     * unifies the rod-bound bait path (v1.9.1) with the legacy off-hand
     * bait path (v1.3.5+).</p>
     *
     * @param player the player casting
     * @param rodItem the fishing rod in the player's main hand
     * @return the effective Bait, or null if no bait is active
     */
    public Bait getEffectiveBait(Player player, ItemStack rodItem) {
        if (player == null) return null;

        // Path 1: rod-bound bait (v1.9.1)
        if (rodItem != null) {
            Bait rodBait = getBaitFromRod(rodItem);
            if (rodBait != null) return rodBait;
        }

        // Path 2: off-hand bait (legacy v1.3.5+ path)
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && !offHand.getType().isAir()) {
            Bait offHandBait = getBaitFromItem(offHand);
            if (offHandBait != null) return offHandBait;
        }

        return null;
    }

    public NamespacedKey getRodBaitKey() {
        return rodBaitKey;
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
