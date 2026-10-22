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

        Inventory clicked = event.getClickedInventory();

        if (clicked != null && !clicked.equals(topInv) && event.getClick().isShiftClick()) {
            event.setCancelled(true);
            manuallyRouteToSellSlots(event, topInv);
            return;
        }

        if (clicked == null || !clicked.equals(topInv)) {
            return;
        }

        event.setCancelled(true);

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || !clickedItem.hasItemMeta())
            return;

        ItemMeta meta = clickedItem.getItemMeta();
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

    private void manuallyRouteToSellSlots(InventoryClickEvent event, Inventory topInv) {
        ItemStack carried = event.getCurrentItem();
        if (carried == null || carried.getType().isAir())
            return;

        ItemStack clone = carried.clone();

        for (int i = 0; i < 45 && clone.getAmount() > 0; i++) {
            ItemStack existing = topInv.getItem(i);
            if (existing == null || existing.getType().isAir()) {
                topInv.setItem(i, clone.clone());
                clone.setAmount(0);
                break;
            }
            if (existing.isSimilar(clone) && existing.getAmount() < existing.getMaxStackSize()) {
                int space = existing.getMaxStackSize() - existing.getAmount();
                int transfer = Math.min(space, clone.getAmount());
                ItemStack merged = existing.clone();
                merged.setAmount(existing.getAmount() + transfer);
                topInv.setItem(i, merged);
                clone.setAmount(clone.getAmount() - transfer);
            }
        }

        if (clone.getAmount() <= 0) {
            event.setCurrentItem(null);
        } else {
            // Could not fit in 0-44: leave the original stack in player inv (event already cancelled).
            // No item lands in border slots.
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
        int size = topInv.getSize();
        for (int i = 0; i < size; i++) {
            ItemStack item = topInv.getItem(i);
            if (item == null || item.getType().isAir())
                continue;
            if (isGuiButton(item))
                continue;
            hasItems = true;
            break;
        }

        if (hasItems) {
            returnItems(player, topInv);
        }
    }

    private void returnItems(Player player, Inventory inv) {
        if (inv == null)
            return;

        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir())
                continue;
            if (isGuiButton(item))
                continue;

            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            inv.setItem(i, null);
        }
    }

    private boolean isGuiButton(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(guiActionKey, PersistentDataType.STRING);
    }
}