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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CatalogManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final NamespacedKey actionKey;
    private final NamespacedKey valueKey;

    private Inventory mainMenu;
    private ItemStack undiscoveredItem;
    private final Map<String, List<ItemStack>> baseItems = new ConcurrentHashMap<>();

    public CatalogManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.actionKey = new NamespacedKey(plugin, "catalog_action");
        this.valueKey = new NamespacedKey(plugin, "catalog_value");
    }

    public void init() {
        createMainMenu();
        loadBaseItems();
        createUndiscoveredItem();
    }

    private void loadBaseItems() {
        if (plugin.getFishManager() != null) {
            baseItems.put("fish", plugin.getFishManager().getFishList().stream()
                    .map(CustomFish::itemStack)
                    .filter(item -> item != null)
                    .toList());
        } else {
            baseItems.put("fish", List.of());
        }

        if (plugin.getBaitManager() != null) {
            baseItems.put("bait", plugin.getBaitManager().getBaits().values().stream()
                    .map(BaitManager.Bait::item)
                    .filter(item -> item != null)
                    .toList());
        } else {
            baseItems.put("bait", List.of());
        }

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
        mainMenu.setItem(22, createButton(Material.GOLD_INGOT,
                plugin.getMessageManager().getPlainMessage("catalog_button_sell"), "sell", "all"));
    }

    public void openMainMenu(Player player) {
        if (mainMenu == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("catalog_not_ready", true));
            return;
        }
        player.openInventory(mainMenu);
    }

    public void openPage(Player player, String category, int page) {
        List<ItemStack> items = baseItems.get(category);
        if (items == null || items.isEmpty()) {
            player.sendMessage(plugin.getMessageManager().getMessage("catalog_category_empty", true));
            return;
        }

        int totalPages = (int) Math.ceil((double) items.size() / 45);
        if (totalPages == 0)
            totalPages = 1;

        String titleTemplate = plugin.getMessageManager().getPlainMessage("catalog_page_title");
        String title = titleTemplate
                .replace("%color%", getCategoryColor(category))
                .replace("%category%", getCategoryName(category))
                .replace("%page%", String.valueOf(page + 1))
                .replace("%total_pages%", String.valueOf(totalPages));

        Inventory pageInv = Bukkit.createInventory(null, 54, miniMessage.deserialize(title));

        FishingData data = null;
        if (plugin.isDiscoveryEnabled() && plugin.getCacheManager() != null) {
            data = plugin.getCacheManager().getData(player.getUniqueId());
        }

        // Döngü dışında fishKey'i alarak performansı artırıyoruz
        NamespacedKey fishKey = (category.equals("fish") && plugin.getFishManager() != null)
                ? plugin.getFishManager().getFishKey()
                : null;

        int startIndex = page * 45;
        int endIndex = Math.min(startIndex + 45, items.size());

        for (int i = startIndex; i < endIndex; i++) {
            ItemStack item = items.get(i);
            if (item == null)
                continue;

            if (category.equals("fish") && data != null && fishKey != null && item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    String fishId = meta.getPersistentDataContainer().get(fishKey, PersistentDataType.STRING);
                    if (fishId != null && !data.hasDiscovered(fishId)) {
                        pageInv.setItem(i - startIndex, undiscoveredItem);
                        continue;
                    }
                }
            }

            pageInv.setItem(i - startIndex, item.clone());
        }

        if (page > 0) {
            pageInv.setItem(45, createButton(Material.ARROW,
                    plugin.getMessageManager().getPlainMessage("catalog_button_prev_page"),
                    "page_" + category, String.valueOf(page - 1)));
        }
        pageInv.setItem(49, createButton(Material.BARRIER,
                plugin.getMessageManager().getPlainMessage("catalog_button_back"), "main", "none"));
        if (page < totalPages - 1) {
            pageInv.setItem(53, createButton(Material.ARROW,
                    plugin.getMessageManager().getPlainMessage("catalog_button_next_page"),
                    "page_" + category, String.valueOf(page + 1)));
        }

        player.openInventory(pageInv);
    }

    private void createUndiscoveredItem() {
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
        this.undiscoveredItem = unknown;
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
        if (plugin.getFishingListener() == null)
            return List.of();

        ItemFixer itemFixer = plugin.getFishingListener().getItemFixer();
        if (itemFixer == null)
            return List.of();

        Map<String, ConfigurationSection> rodConfigs = itemFixer.getRodConfigs();
        if (rodConfigs == null || rodConfigs.isEmpty())
            return List.of();

        List<ItemStack> rods = new ArrayList<>();
        for (ConfigurationSection rodSec : rodConfigs.values()) {
            if (rodSec != null) {
                ItemStack rod = itemFixer.createRodItem(rodSec);
                if (rod != null) {
                    rods.add(rod);
                }
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