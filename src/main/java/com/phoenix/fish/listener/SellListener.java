package com.phoenix.fish.listener;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.manager.EconomyManager;

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

import java.util.Map;

public class SellListener implements Listener {

    private final PhoenixFish plugin;
    private final NamespacedKey guiActionKey;

    public SellListener(PhoenixFish plugin) {
        this.plugin = plugin;
        this.guiActionKey = new NamespacedKey(plugin, "gui_action");
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory topInv = event.getView().getTopInventory();
        if (!(topInv.getHolder() instanceof EconomyManager.SellGUIHolder))
            return;

        if (event.getHotbarButton() != -1) {
            event.setCancelled(true);
            return;
        }

        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(topInv)) {
            return;
        }

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta())
            return;

        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(guiActionKey, PersistentDataType.STRING);

        if (action == null)
            return;

        Player player = (Player) event.getWhoClicked();

        if (action.equals("sell_confirm")) {
            plugin.getEconomyManager().confirmSell(player, topInv);
            player.closeInventory();
        } else if (action.equals("sell_cancel")) {
            returnItems(player, topInv);
            player.closeInventory();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory topInv = event.getView().getTopInventory();
        if (!(topInv.getHolder() instanceof EconomyManager.SellGUIHolder))
            return;

        for (int slot : event.getRawSlots()) {
            if (slot < topInv.getSize()) {
                event.setCancelled(true);
                break;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory topInv = event.getView().getTopInventory();
        if (!(topInv.getHolder() instanceof EconomyManager.SellGUIHolder))
            return;

        Player player = (Player) event.getPlayer();

        boolean hasItems = false;
        for (int i = 0; i < 45; i++) {
            ItemStack item = topInv.getItem(i);
            if (item != null && !item.getType().isAir()) {
                hasItems = true;
                break;
            }
        }

        if (hasItems) {
            returnItems(player, topInv);
        }
    }

    private void returnItems(Player player, Inventory inv) {
        if (inv == null)
            return;

        for (int i = 0; i < 45; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir())
                continue;

            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            inv.setItem(i, null);
        }
    }
}