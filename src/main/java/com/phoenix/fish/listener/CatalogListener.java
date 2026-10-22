package com.phoenix.fish.listener;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.manager.CatalogManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class CatalogListener implements Listener {

    private final PhoenixFish plugin;
    private final NamespacedKey actionKey;
    private final NamespacedKey valueKey;

    public CatalogListener(PhoenixFish plugin) {
        this.plugin = plugin;
        this.actionKey = plugin.getCatalogManager().getActionKey();
        this.valueKey = plugin.getCatalogManager().getValueKey();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory topInv = event.getView().getTopInventory();
        if (topInv == null)
            return;

        boolean isCatalog = topInv.getHolder() instanceof CatalogManager.CatalogGUIHolder;
        if (!isCatalog)
            return;

        event.setCancelled(true);

        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(topInv)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta())
            return;

        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);

        if (action == null)
            return;

        Player player = (Player) event.getWhoClicked();
        String value = meta.getPersistentDataContainer().get(valueKey, PersistentDataType.STRING);

        if (action.equals("sell")) {
            plugin.getEconomyManager().sellAll(player);
            player.closeInventory();
            return;
        }

        if (action.equals("main")) {
            plugin.getCatalogManager().openMainMenu(player);
        } else if (action.equals("category")) {
            if (value != null) {
                plugin.getCatalogManager().openPage(player, value, 0);
            }
        } else if (action.startsWith("page_")) {
            String category = action.substring(5);
            int page = 0;
            if (value != null) {
                try {
                    page = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                }
            }
            plugin.getCatalogManager().openPage(player, category, page);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory topInv = event.getView().getTopInventory();
        if (topInv == null)
            return;

        if (!(topInv.getHolder() instanceof CatalogManager.CatalogGUIHolder))
            return;

        event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        
        Inventory top = event.getInventory();
        if (top == null)
            return;
        if (!(top.getHolder() instanceof CatalogManager.CatalogGUIHolder))
            return;
    }
}
