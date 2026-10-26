package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.event.FishSellEvent;
import com.phoenix.fish.model.CustomFish;
import com.phoenix.fish.util.SoundUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.Map;

public class EconomyManager {

    private final PhoenixFish plugin;
    private final NamespacedKey weightKey;
    private final NamespacedKey guiActionKey;
    private final MiniMessage miniMessage;

    public static class SellGUIHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public EconomyManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.weightKey = new NamespacedKey(plugin, "fish_weight");
        this.guiActionKey = new NamespacedKey(plugin, "gui_action");
        this.miniMessage = MiniMessage.miniMessage();
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("economy.enabled", false);
    }

    public double getBalance(Player player) {
        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        return data != null ? data.getBalance() : 0.0;
    }

    public double getWeight(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return 0.0;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return 0.0;
        return meta.getPersistentDataContainer().getOrDefault(weightKey, PersistentDataType.DOUBLE, 0.0);
    }

    public void setWeight(ItemStack item, double weight) {
        if (item == null || !item.hasItemMeta())
            return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return;
        meta.getPersistentDataContainer().set(weightKey, PersistentDataType.DOUBLE, weight);
        item.setItemMeta(meta);
    }

    public double calculatePrice(ItemStack item, CustomFish fish) {
        if (fish == null)
            return 0.0;
        double basePrice = Math.max(0.0, fish.price());
        double weight = getWeight(item);
        if (weight <= 0)
            weight = 1.0;
        return basePrice * weight * item.getAmount();
    }

    private String getFishId(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return null;
        return meta.getPersistentDataContainer().get(plugin.getFishManager().getFishKey(), PersistentDataType.STRING);
    }

    public void sellAll(Player player) {
        if (!isEnabled()) {
            player.sendMessage(plugin.getMessageManager().getMessage("economy_disabled", true));
            return;
        }

        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null)
            return;

        double total = 0.0;
        int count = 0;

        // v1.4.0 (C-3 fix) — Two-pass approach.
        // ESKİ DAVRANIŞ (BUG): Eşyalar satıştan ÖNCE silinirdi. Eğer FishSellEvent
        // iptal edilirse, oyuncu hem eşyalarını hem de parayı kaybederdi.
        // YENİ DAVRANIŞ: Önce tüm fiyatı hesapla → event fire et → iptal edilmezse sil.
        ItemStack[] contents = player.getInventory().getContents();
        // Pass 1: hesapla (envanteri değiştirme)
        java.util.List<Integer> slotsToClear = new java.util.ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir())
                continue;
            String fishId = getFishId(item);
            if (fishId != null) {
                CustomFish fish = plugin.getFishManager().getFishById(fishId);
                if (fish != null) {
                    total += calculatePrice(item, fish);
                    count += item.getAmount();
                    slotsToClear.add(i);   // slot'u silme listesine ekle (silme!
                }
            }
        }

        // Pass 2: Eğer event cancel edilmediyse, slot'ları sil.
        // processSale önce event fire ediyor. onCancel olursa return ediyor → slot'lar silinmez.
        // Bu amacla processSale'in "silindimi" bilgisini geri iletmesi gerekir →
        // burada slotsToClear sadece event onaylırsa silinmeli. Refactor: processSale'a
        // "onSuccess" callback vermek yerine — iptal kontrolünü burada yap.
        boolean shouldSell = processSaleCheckCancel(player, data, total, count);
        if (shouldSell) {
            // Event onaylandı → slot'ları sil
            for (int slot : slotsToClear) {
                player.getInventory().setItem(slot, null);
            }
            processSaleComplete(player, data, total, count);
        }
    }

    /**
     * v1.4.0 — Sadece FishSellEvent'i fire edip iptal durumunu döner.
     * İptal edilmezse true döner (satış devam edebilir).
     */
    private boolean processSaleCheckCancel(Player player, FishingData data, double total, int count) {
        FishSellEvent event = new FishSellEvent(player, total, count);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            player.sendMessage(plugin.getMessageManager().getMessage("sell_cancelled", true));
            return false;
        }
        return true;
    }

    /**
     * v1.4.0 — Satışı tamamla (para ekle + mesaj). Sadece processSaleCheckCancel true döndükten sonra çağrılır.
     */
    private void processSaleComplete(Player player, FishingData data, double total, int count) {
        if (count == 0) {
            player.sendMessage(plugin.getMessageManager().getMessage("sell_no_fish", true));
            return;
        }
        data.addBalance(total);
        Map<String, String> ph = Map.of(
                "%amount%", String.valueOf(count),
                "%price%", String.format("%.2f", total));
        player.sendMessage(plugin.getMessageManager().getMessage("sell_all_success", true, ph));
    }

    public void openSellGUI(Player player) {
        if (!isEnabled()) {
            player.sendMessage(plugin.getMessageManager().getMessage("economy_disabled", true));
            return;
        }

        Inventory inv = Bukkit.createInventory(new SellGUIHolder(), 54,
                miniMessage.deserialize(plugin.getMessageManager().getPlainMessage("sell_gui_title")));

        ItemStack confirmButton = createButton(Material.GOLD_BLOCK,
                plugin.getMessageManager().getPlainMessage("sell_gui_confirm"), "sell_confirm");
        inv.setItem(49, confirmButton);

        ItemStack cancelButton = createButton(Material.BARRIER,
                plugin.getMessageManager().getPlainMessage("sell_gui_cancel"), "sell_cancel");
        inv.setItem(45, cancelButton);

        player.openInventory(inv);
    }

    public void confirmSell(Player player, Inventory inv) {
        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null)
            return;

        double total = 0.0;
        int count = 0;

        // v1.4.0 (C-3 fix) — Two-pass approach. Eski davranışta balıklar satıştan ÖNCE silinirdi
        // → event cancel olursa oyuncu eşyalarını kaybederdi. Şimdi: önce hesapla, slot'ları
        // geçici listeye koy → event fire et → cancel edilmediyse sil.
        int size = inv.getSize();
        java.util.List<Integer> fishSlots = new java.util.ArrayList<>();
        java.util.List<Integer> nonFishSlots = new java.util.ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir())
                continue;
            if (isGuiButton(item))
                continue;

            String fishId = getFishId(item);
            if (fishId != null) {
                CustomFish fish = plugin.getFishManager().getFishById(fishId);
                if (fish != null) {
                    total += calculatePrice(item, fish);
                    count += item.getAmount();
                    fishSlots.add(i);
                    continue;
                }
            }
            nonFishSlots.add(i);  // balık olmayan eşyalar — oyuncu envanterine geri verilecek
        }

        // Event kontrolü: iptal edilmezse balıkları sil
        if (!processSaleCheckCancel(player, data, total, count)) {
            // Event iptal edildi — envanteri KAPATMA, eşyalar envanterde kalsın
            return;
        }

        // Event onaylandı → balıkları sil, balık olmayanları oyuncuya geri ver
        for (int slot : fishSlots) {
            inv.setItem(slot, null);
        }
        for (int slot : nonFishSlots) {
            ItemStack item = inv.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                for (ItemStack rest : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
                inv.setItem(slot, null);
            }
        }

        player.closeInventory();
        processSaleComplete(player, data, total, count);
    }

    public void sellHand(Player player) {
        if (!isEnabled()) {
            player.sendMessage(plugin.getMessageManager().getMessage("economy_disabled", true));
            return;
        }

        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null)
            return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            player.sendMessage(plugin.getMessageManager().getMessage("sell_no_fish", true));
            return;
        }

        String fishId = getFishId(item);
        if (fishId == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("sell_no_fish", true));
            return;
        }

        CustomFish fish = plugin.getFishManager().getFishById(fishId);
        if (fish == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("sell_no_fish", true));
            return;
        }

        double price = calculatePrice(item, fish);
        int amount = item.getAmount();

        // v1.4.0 (C-3 fix) — Two-pass: önce event fire et, iptal edilmezse sil.
        if (!processSaleCheckCancel(player, data, price, amount)) {
            return;  // event cancelled → item envanterde kalır
        }
        player.getInventory().setItemInMainHand(null);
        processSaleComplete(player, data, price, amount);
    }

    private void processSale(Player player, FishingData data, double total, int count) {
        if (total <= 0) {
            player.sendMessage(plugin.getMessageManager().getMessage("sell_no_fish", true));
            return;
        }

        FishSellEvent event = new FishSellEvent(player, total, count);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            player.sendMessage(plugin.getMessageManager().getMessage("sell_cancelled", true));
            return;
        }

        data.addBalance(total);

        Map<String, String> ph = Map.of(
                "%amount%", String.valueOf(count),
                "%price%", String.format(Locale.US, "%.2f", total));
        player.sendMessage(plugin.getMessageManager().getMessage("sell_all_success", true, ph));

        SoundUtils.playFromConfig(plugin, player, "sounds.sell_success",
                Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);

        if (plugin.getApi() != null) {
            try {
                plugin.getApi().triggerFishSell(player, total, count);
            } catch (Exception e) {
                plugin.getLogger().warning("Error triggering fish sell event via API: " + e.getMessage());
            }
        }
    }

    private ItemStack createButton(Material mat, String name, String action) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize(name));
            meta.getPersistentDataContainer().set(guiActionKey, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isGuiButton(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(guiActionKey, PersistentDataType.STRING);
    }
}