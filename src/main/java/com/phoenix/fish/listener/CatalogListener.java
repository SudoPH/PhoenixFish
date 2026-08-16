package com.phoenix.fish.listener;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class CatalogListener implements Listener {

    private final PhoenixFish plugin;

    public CatalogListener(PhoenixFish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory() == null)
            return;

        String pageWord = plugin.getMessageManager().getPlainMessage("word_page");
        boolean isMainMenu = event.getView().getTopInventory().equals(plugin.getCatalogManager().getMainMenu());
        boolean isPageMenu = PlainTextComponentSerializer.plainText().serialize(event.getView().title())
                .contains(pageWord);

        if (!isMainMenu && !isPageMenu)
            return;

        event.setCancelled(true);

        if (!event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta())
            return;

        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(plugin.getCatalogManager().getActionKey(),
                PersistentDataType.STRING);

        if (action == null)
            return;

        Player player = (Player) event.getWhoClicked();
        String value = meta.getPersistentDataContainer().get(plugin.getCatalogManager().getValueKey(),
                PersistentDataType.STRING);

        if (action.equals("main")) {
            plugin.getCatalogManager().openMainMenu(player);
        } else if (action.equals("category")) {
            plugin.getCatalogManager().openPage(player, value, 0);
        } else if (action.startsWith("page_")) {
            String category = action.substring(5);
            int page = Integer.parseInt(value);
            plugin.getCatalogManager().openPage(player, category, page);
        }
    }
}