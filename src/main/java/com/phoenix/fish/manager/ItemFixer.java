package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ItemFixer {

    public enum FixResult {
        NONE, ROD, BAIT, FAILED
    }

    public record ItemIdentifier(Material material, int customModelData) {
    }

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final NamespacedKey rodKey;
    private final NamespacedKey baitKey;

    private final Map<ItemIdentifier, ConfigurationSection> rodCache;
    private final Map<String, ConfigurationSection> rodIdCache;

    public ItemFixer(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.rodKey = new NamespacedKey(plugin, "luck_multiplier");
        this.baitKey = new NamespacedKey(plugin, "bait_id");
        this.rodCache = new ConcurrentHashMap<>();
        this.rodIdCache = new ConcurrentHashMap<>();

        cacheRodConfigurations();
    }

    private void cacheRodConfigurations() {
        // v1.8.5 — Rod data is always loaded from rods.yml at the data folder root.
        // The localized display-name + lore are looked up per-rod via ContentLanguageManager.
        File rodFile = new File(plugin.getDataFolder(), "rods.yml");
        if (!rodFile.exists()) {
            plugin.saveResource("rods.yml", false);
            rodFile = new File(plugin.getDataFolder(), "rods.yml");
        }

        plugin.getLogger().info("Loading rods from: rods.yml (lang="
                + plugin.getContentLanguageManager().getLanguage() + ")");

        FileConfiguration rodConfig = YamlConfiguration.loadConfiguration(rodFile);
        ConfigurationSection rodsSec = rodConfig.getConfigurationSection("rods");
        if (rodsSec == null)
            return;

        for (String key : rodsSec.getKeys(false)) {
            ConfigurationSection sec = rodsSec.getConfigurationSection(key);
            if (sec == null)
                continue;

            String matStr = sec.getString("material", "FISHING_ROD").toUpperCase();
            Material mat = Material.matchMaterial(matStr);
            if (mat == null)
                continue;

            int cmd = sec.getInt("custom-model-data", 0);

            ItemIdentifier identifier = new ItemIdentifier(mat, cmd);
            if (rodCache.putIfAbsent(identifier, sec) != null) {
                plugin.getLogger().warning("Duplicate rod cache key for rod " + key
                        + " (material=" + mat + ", cmd=" + cmd
                        + ") — conflicts with an earlier rod. Legacy items may resolve wrong.");
            }
            rodIdCache.put(key.toLowerCase(), sec);
        }
    }

    public void reload() {
        rodCache.clear();
        rodIdCache.clear();
        cacheRodConfigurations();
        plugin.getLogger().info("ItemFixer: rods.yml reloaded ("
                + rodIdCache.size() + " rods cached).");
    }

    public int[] fixInventory(Inventory inv) {
        if (inv == null)
            return new int[] { 0, 0 };
        return fixContents(inv.getContents(), inv);
    }

    public int[] fixContents(ItemStack[] contents, Inventory inv) {
        int rods = 0, baits = 0;
        if (contents == null)
            return new int[] { 0, 0 };

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir())
                continue;

            FixResult result = fixItem(item);
            if (result == FixResult.ROD) {
                rods++;
                if (inv != null)
                    inv.setItem(i, item);
            } else if (result == FixResult.BAIT) {
                baits++;
                if (inv != null)
                    inv.setItem(i, item);
            }
        }

        return new int[] { rods, baits };
    }

    public FixResult fixItem(ItemStack itemInHand) {
        if (itemInHand == null || itemInHand.getType().isAir())
            return FixResult.NONE;

        ItemMeta meta = itemInHand.getItemMeta();
        if (meta == null)
            return FixResult.NONE;

        boolean isRod = meta.getPersistentDataContainer().has(rodKey, PersistentDataType.DOUBLE);
        boolean isBait = meta.getPersistentDataContainer().has(baitKey, PersistentDataType.STRING);

        if (isRod)
            return fixRod(itemInHand, meta);
        if (isBait)
            return fixBait(itemInHand, meta);

        if (itemInHand.getType() == Material.FISHING_ROD) {
            ConfigurationSection rodSec = findRodByItem(itemInHand);
            if (rodSec != null) {
                double luck = rodSec.getDouble("luck-multiplier", 1.0);
                meta.getPersistentDataContainer().set(rodKey, PersistentDataType.DOUBLE, luck);
                return fixRod(itemInHand, meta);
            }
        }

        BaitManager.Bait detectedBait = plugin.getBaitManager().getBaitFromItem(itemInHand);
        if (detectedBait != null) {
            meta.getPersistentDataContainer().set(baitKey, PersistentDataType.STRING, detectedBait.id());
            return fixBait(itemInHand, meta);
        }

        return FixResult.NONE;
    }

    public ConfigurationSection findRodByItem(ItemStack item) {
        int itemCmd = ItemUtils.getCMD(item);
        ItemIdentifier identifier = new ItemIdentifier(item.getType(), itemCmd);
        return rodCache.get(identifier);
    }

    /**
     * v1.3.6 — Material + custom-model-data'dan rod'un ConfigurationSection'unu bul.
     * CraftingManager.createItem() tarif ingredient'ları için kullanır — böylece
     * tarif ekranında "FISHING_ROD ×1" yerine "宇宙鱼竿 ×1" (Çince) gibi
     * gerçek özel olta adı görünür.
     *
     * @param material rod'un Material'ı (genelde FISHING_ROD)
     * @param cmd rod'un custom-model-data değeri
     * @return rod'un ConfigurationSection'u, ya da bulunamazsa null
     */
    public ConfigurationSection findRodConfig(Material material, int cmd) {
        if (material == null) return null;
        // v1.4.0 — cmd == 0 demek "özel model yok" demek (vanilla oltalar).
        // Bu durumda herhangi bir cached rod'a yanlışlıkla match etmesin diye null dön.
        // Aksi halde rods.yml'de cmd=0 olan ilk cached rod, vanilla oltalarla yanlış
        // match olur → /pf fix vanilla oltayı bozar (display-name + luck PDC ekler).
        if (cmd == 0) return null;
        ItemIdentifier identifier = new ItemIdentifier(material, cmd);
        return rodCache.get(identifier);
    }

    /**
     * v1.8.5 — Look up the rod ID for a cached rod section so we can
     * query the ContentLanguageManager for the localized name + lore.
     */
    private String findRodId(ConfigurationSection rodSec) {
        if (rodSec == null) return null;
        String name = rodSec.getName();
        if (name != null && rodIdCache.containsKey(name.toLowerCase())) return name;
        for (Map.Entry<String, ConfigurationSection> e : rodIdCache.entrySet()) {
            if (e.getValue() == rodSec) return e.getKey();
        }
        return null;
    }

    private FixResult fixRod(ItemStack itemInHand, ItemMeta meta) {
        ConfigurationSection rodSec = findRodByItem(itemInHand);

        if (rodSec == null)
            return FixResult.FAILED;

        // v1.8.5 — Localized display-name + lore via ContentLanguageManager.
        String rodId = findRodId(rodSec);
        String defaultRodName = plugin.getMessageManager().getPlainMessage("item_default_rod_name");
        String inlineName = rodSec.getString("display-name", defaultRodName);
        String localizedName = (rodId != null)
                ? plugin.getContentLanguageManager().getRodName(rodId, inlineName)
                : inlineName;
        meta.displayName(miniMessage.deserialize(localizedName));
        ItemUtils.applyCustomModelData(meta, rodSec);

        List<String> inlineLore = rodSec.getStringList("lore");
        List<String> loreLines = (rodId != null)
                ? plugin.getContentLanguageManager().getRodLore(rodId, inlineLore)
                : inlineLore;
        if (!loreLines.isEmpty()) {
            List<Component> loreComponents = new ArrayList<>(loreLines.size());
            for (String line : loreLines) {
                loreComponents.add(miniMessage.deserialize(line));
            }
            meta.lore(loreComponents);
        } else {
            meta.lore(null);
        }

        itemInHand.setItemMeta(meta);
        return FixResult.ROD;
    }

    @SuppressWarnings("deprecation")
    private FixResult fixBait(ItemStack itemInHand, ItemMeta meta) {
        String baitId = meta.getPersistentDataContainer().get(baitKey, PersistentDataType.STRING);
        if (baitId == null)
            return FixResult.FAILED;

        BaitManager.Bait bait = plugin.getBaitManager().getBaits().get(baitId);
        if (bait == null)
            return FixResult.FAILED;

        ItemMeta freshMeta = bait.item().getItemMeta();
        if (freshMeta == null)
            return FixResult.FAILED;

        meta.displayName(freshMeta.displayName());
        meta.lore(freshMeta.lore());

        int cmd = ItemUtils.getCMD(bait.item());
        if (cmd > 0) {
            meta.setCustomModelData(cmd);
        }

        itemInHand.setItemMeta(meta);
        return FixResult.BAIT;
    }

    public ConfigurationSection getRodConfig(String rodId) {
        return rodIdCache.get(rodId.toLowerCase());
    }

    public Map<String, ConfigurationSection> getRodConfigs() {
        return rodIdCache;
    }

    public ItemStack createRodItem(ConfigurationSection rodSec) {
        String matStr = rodSec.getString("material", "FISHING_ROD").toUpperCase();
        Material mat = Material.matchMaterial(matStr);
        if (mat == null)
            return null;

        ItemStack rod = new ItemStack(mat);
        ItemMeta meta = rod.getItemMeta();
        if (meta == null)
            return rod;

        // v1.8.5 — Localized display-name + lore via ContentLanguageManager.
        String rodId = findRodId(rodSec);
        String defaultRodName = plugin.getMessageManager().getPlainMessage("item_default_rod_name");
        String inlineName = rodSec.getString("display-name", defaultRodName);
        String localizedName = (rodId != null)
                ? plugin.getContentLanguageManager().getRodName(rodId, inlineName)
                : inlineName;
        meta.displayName(miniMessage.deserialize(localizedName));

        ItemUtils.applyCustomModelData(meta, rodSec);

        List<String> inlineLore = rodSec.getStringList("lore");
        List<String> loreLines = (rodId != null)
                ? plugin.getContentLanguageManager().getRodLore(rodId, inlineLore)
                : inlineLore;
        if (!loreLines.isEmpty()) {
            List<Component> loreComponents = new ArrayList<>(loreLines.size());
            for (String line : loreLines) {
                loreComponents.add(miniMessage.deserialize(line));
            }
            meta.lore(loreComponents);
        }

        double luck = rodSec.getDouble("luck-multiplier", 1.0);
        meta.getPersistentDataContainer().set(rodKey, PersistentDataType.DOUBLE, luck);

        rod.setItemMeta(meta);
        return rod;
    }
}
