package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.model.CustomFish;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CatalogManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final NamespacedKey actionKey;
    private final NamespacedKey valueKey;

    private Inventory mainMenu;
    private final Map<String, List<ItemStack>> baseItems = new HashMap<>();

    public CatalogManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.actionKey = new NamespacedKey(plugin, "catalog_action");
        this.valueKey = new NamespacedKey(plugin, "catalog_value");
    }

    public void init() {
        createMainMenu();
        baseItems.put("fish", plugin.getFishManager().getFishList().stream().map(CustomFish::itemStack).toList());
        baseItems.put("bait",
                plugin.getBaitManager().getBaits().values().stream().map(BaitManager.Bait::item).toList());
        baseItems.put("rod", getAllRodItems());
    }

    private void createMainMenu() {
        this.mainMenu = Bukkit.createInventory(null, 27,
                miniMessage.deserialize(plugin.getMessageManager().getPlainMessage("catalog_main_menu_title")));

        mainMenu.setItem(11, createButton(Material.COD,
                plugin.getMessageManager().getPlainMessage("catalog_button_fish"), "category", "fish"));
        mainMenu.setItem(13, createButton(Material.FISHING_ROD,
                plugin.getMessageManager().getPlainMessage("catalog_button_rod"), "category", "rod"));
        mainMenu.setItem(15, createButton(Material.STICK,
                plugin.getMessageManager().getPlainMessage("catalog_button_bait"), "category", "bait"));
    }

    public void openMainMenu(Player player) {
        player.openInventory(mainMenu);
    }

    public void openPage(Player player, String category, int page) {
        List<ItemStack> items = baseItems.get(category);
        if (items == null)
            return;

        int totalPages = (int) Math.ceil((double) items.size() / 45);
        if (totalPages == 0)
            totalPages = 1;

        // Dinamik başlık oluşturma
        String titleTemplate = plugin.getMessageManager().getPlainMessage("catalog_page_title");
        String title = titleTemplate
                .replace("%color%", getCategoryColor(category))
                .replace("%category%", getCategoryName(category))
                .replace("%page%", String.valueOf(page + 1))
                .replace("%total_pages%", String.valueOf(totalPages));

        Inventory pageInv = Bukkit.createInventory(null, 54, miniMessage.deserialize(title));

        // DEĞİŞTİRİLDİ: isXpSystemEnabled yerine isDiscoveryEnabled
        FishingData data = plugin.isDiscoveryEnabled() ? plugin.getCacheManager().getData(player.getUniqueId()) : null;

        int startIndex = page * 45;
        int endIndex = Math.min(startIndex + 45, items.size());

        for (int i = startIndex; i < endIndex; i++) {
            ItemStack item = items.get(i);

            // Keşif Sistemi Mantığı
            if (category.equals("fish") && data != null) {
                String fishId = getFishIdFromItem(item);
                if (fishId != null && !data.hasDiscovered(fishId)) {
                    pageInv.setItem(i - startIndex, createUndiscoveredItem());
                    continue;
                }
            }

            pageInv.setItem(i - startIndex, item.clone());
        }

        if (page > 0) {
            pageInv.setItem(45,
                    createButton(Material.ARROW, plugin.getMessageManager().getPlainMessage("catalog_button_prev_page"),
                            "page_" + category, String.valueOf(page - 1)));
        }
        pageInv.setItem(49, createButton(Material.BARRIER,
                plugin.getMessageManager().getPlainMessage("catalog_button_back"), "main", "none"));
        if (page < totalPages - 1) {
            pageInv.setItem(53,
                    createButton(Material.ARROW, plugin.getMessageManager().getPlainMessage("catalog_button_next_page"),
                            "page_" + category, String.valueOf(page + 1)));
        }

        player.openInventory(pageInv);
    }

    private String getFishIdFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return null;
        return item.getItemMeta().getPersistentDataContainer().get(plugin.getFishManager().getFishKey(),
                PersistentDataType.STRING);
    }

    private ItemStack createUndiscoveredItem() {
        ItemStack unknown = new ItemStack(Material.PAPER);
        ItemMeta meta = unknown.getItemMeta();
        if (meta != null) {
            meta.displayName(
                    miniMessage.deserialize(plugin.getMessageManager().getPlainMessage("catalog_undiscovered_name")));
            meta.lore(List.of(
                    miniMessage.deserialize(plugin.getMessageManager().getPlainMessage("catalog_undiscovered_lore_1")),
                    miniMessage
                            .deserialize(plugin.getMessageManager().getPlainMessage("catalog_undiscovered_lore_2"))));
            unknown.setItemMeta(meta);
        }
        return unknown;
    }

    private String getCategoryColor(String category) {
        return switch (category) {
            case "fish" -> "aqua";
            case "bait" -> "gold";
            case "rod" -> "yellow";
            default -> "white";
        };
    }

    private String getCategoryName(String category) {
        String name = plugin.getMessageManager().getPlainMessage("catalog_title_" + category);
        if (name.equals("catalog_title_" + category)) {
            return plugin.getMessageManager().getPlainMessage("catalog_title_default");
        }
        return name;
    }

    private List<ItemStack> getAllRodItems() {
        List<ItemStack> rods = new ArrayList<>();
        for (String rodId : plugin.getFishingListener().getItemFixer().getRodConfigs().keySet()) {
            ConfigurationSection rodSec = plugin.getFishingListener().getItemFixer().getRodConfig(rodId);
            if (rodSec != null) {
                ItemStack rod = plugin.getFishingListener().getItemFixer().createRodItem(rodSec);
                if (rod != null)
                    rods.add(rod);
            }
        }
        return rods;
    }

    private ItemStack createButton(Material mat, String name, String action, String value) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize(name));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            meta.getPersistentDataContainer().set(valueKey, PersistentDataType.STRING, value);
            item.setItemMeta(meta);
        }
        return item;
    }

    public Inventory getMainMenu() {
        return mainMenu;
    }

    public NamespacedKey getActionKey() {
        return actionKey;
    }

    public NamespacedKey getValueKey() {
        return valueKey;
    }
}