package com.phoenix.fish.listener;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.manager.AchievementManager;
import com.phoenix.fish.manager.ContractManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;

/**
 * v1.7.0 — Achievement + Contract GUI listener.
 *
 * <p>Both GUIs are protected against item theft: every click/drag that
 * would move items in or out of the top inventory is cancelled.</p>
 *
 * <p>For the Contract GUI only, after cancelling a click on a contract
 * slot, the listener dispatches a "claim" action to {@link ContractManager}.
 * This lets players claim completed-contract rewards by clicking the
 * slot, without exposing the inventory to item-shift exploits (the click
 * is cancelled BEFORE the claim dispatch — no item ever moves).</p>
 */
public class AchievementContractListener implements Listener {

    private final PhoenixFish plugin;

    public AchievementContractListener(PhoenixFish plugin) {
        this.plugin = plugin;
    }

    private boolean isProtectedGui(Inventory topInv) {
        if (topInv == null) return false;
        Object holder = topInv.getHolder();
        return holder instanceof AchievementManager.AchievementGUIHolder
                || holder instanceof ContractManager.ContractGUIHolder;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory topInv = event.getView().getTopInventory();
        if (!isProtectedGui(topInv)) return;

        // Hotbar key swaps (1..9) — always block.
        if (event.getHotbarButton() != -1) {
            event.setCancelled(true);
            return;
        }

        Inventory clicked = event.getClickedInventory();

        // Shift-click from bottom → would push into top — block.
        if (clicked != null && !clicked.equals(topInv) && event.getClick().isShiftClick()) {
            event.setCancelled(true);
            return;
        }

        // Click inside the top inventory — block, then maybe dispatch a claim.
        if (clicked != null && clicked.equals(topInv)) {
            event.setCancelled(true);

            // Only left-clicks on a Contract GUI dispatch a claim action.
            // Right-clicks, middle-click, shift-clicks, etc. are silent no-ops.
            Object holder = topInv.getHolder();
            if (holder instanceof ContractManager.ContractGUIHolder
                    && event.getAction() != InventoryAction.NOTHING
                    && event.getWhoClicked() instanceof Player player) {
                int slot = event.getRawSlot();
                // Always dispatch — ContractManager returns false if the slot
                // isn't a contract slot or the contract isn't claimable, in
                // which case we just no-op.
                boolean claimed = plugin.getContractManager().handleSlotClick(player, slot);
                if (claimed) {
                    // Re-open the GUI so the clicked slot updates to "claimed" state.
                    plugin.getContractManager().openGUI(player);
                }
            }
            return;
        }

        // Bottom-inventory clicks while a protected GUI is open — only block
        // shift-clicks (already handled above). Plain left/right clicks in the
        // player's own inventory are allowed (they don't touch the top inv).
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        Inventory topInv = event.getView().getTopInventory();
        if (!isProtectedGui(topInv)) return;
        int topSize = topInv.getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ── Extra safety nets (hopper/dropper/pickup) ────────────────────────
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMoveItem(InventoryMoveItemEvent event) {
        if (isProtectedGui(event.getDestination()) || isProtectedGui(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(InventoryPickupItemEvent event) {
        if (isProtectedGui(event.getInventory())) {
            event.setCancelled(true);
        }
    }
}
