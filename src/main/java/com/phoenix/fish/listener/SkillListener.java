package com.phoenix.fish.listener;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.manager.SkillManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class SkillListener implements Listener {

    private final PhoenixFish plugin;

    public SkillListener(PhoenixFish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {

        if (!(event.getView().getTopInventory().getHolder() instanceof SkillManager.SkillGUIHolder))
            return;

        event.setCancelled(true);

        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta())
            return;

        ItemMeta meta = clicked.getItemMeta();
        NamespacedKey actionKey = plugin.getSkillManager().getActionKey();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);

        if (action == null || !action.equals("upgrade"))
            return;

        NamespacedKey valueKey = plugin.getSkillManager().getValueKey();
        String value = meta.getPersistentDataContainer().get(valueKey, PersistentDataType.STRING);

        if (value == null)
            return;

        Player player = (Player) event.getWhoClicked();
        plugin.getSkillManager().handleUpgrade(player, value);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {

        if (event.getView().getTopInventory().getHolder() instanceof SkillManager.SkillGUIHolder) {
            event.setCancelled(true);
        }
    }
}