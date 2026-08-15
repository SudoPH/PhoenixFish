package com.phoenix.fish.listener;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.manager.BaitManager;
import com.phoenix.fish.manager.ItemFixer;
import com.phoenix.fish.minigame.MinigameTask;
import com.phoenix.fish.model.CustomFish;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class FishingListener implements Listener {

    private final PhoenixFish plugin;
    private final ConcurrentHashMap<UUID, MinigameTask> activeMinigames;
    private final NamespacedKey rodKey = new NamespacedKey("phoenixfish", "luck_multiplier");

    private final ItemFixer itemFixer;
    private final Enchantment luckEnchantment;

    public FishingListener(PhoenixFish plugin) {
        this.plugin = plugin;
        this.activeMinigames = new ConcurrentHashMap<>();
        this.itemFixer = new ItemFixer(plugin);

        this.luckEnchantment = RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.ENCHANTMENT)
                .get(NamespacedKey.minecraft("luck_of_the_sea"));
    }

    public ItemFixer getItemFixer() {
        return itemFixer;
    }

    public Map<UUID, MinigameTask> getActiveMinigames() {
        return Collections.unmodifiableMap(activeMinigames);
    }

    public void removeMinigame(UUID uuid) {
        activeMinigames.remove(uuid);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        itemFixer.fixInventory(player.getInventory());
        itemFixer.fixInventory(player.getEnderChest());
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        InventoryType type = event.getInventory().getType();
        if (type == InventoryType.CHEST || type == InventoryType.BARREL || type == InventoryType.SHULKER_BOX
                || type == InventoryType.ENDER_CHEST) {
            itemFixer.fixInventory(event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();

        if (activeMinigames.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (event.getState() == PlayerFishEvent.State.BITE) {
            player.sendActionBar(plugin.getMessageManager().getMessage("fish_bite", false));
        }

        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            handleFishCatch(event, player);
        }
    }

    private void handleFishCatch(PlayerFishEvent event, Player player) {
        boolean minigameEnabled = plugin.getConfig().getBoolean("settings.minigame-enabled", true);
        ItemStack rod = player.getInventory().getItemInMainHand();

        double luckMultiplier = calculateLuckMultiplier(player, rod);

        ItemStack offHandItem = player.getInventory().getItemInOffHand();
        BaitManager.Bait bait = plugin.getBaitManager().getBaitFromItem(offHandItem);

        CustomFish fish = plugin.getFishManager().rollRandomFish(luckMultiplier, bait);
        if (fish == null)
            return;

        event.setCancelled(true);

        consumeBait(player, offHandItem, bait);

        if (minigameEnabled) {
            startMinigame(player, fish);
        } else {
            giveFishDirectly(player, fish);
        }
    }

    private double calculateLuckMultiplier(Player player, ItemStack rod) {
        double luckMultiplier = 1.0;

        if (rod != null && rod.getType() == Material.FISHING_ROD) {
            ItemMeta meta = rod.getItemMeta();
            if (meta != null && meta.getPersistentDataContainer().has(rodKey, PersistentDataType.DOUBLE)) {
                luckMultiplier = meta.getPersistentDataContainer().get(rodKey, PersistentDataType.DOUBLE);
            }
            if (luckEnchantment != null) {
                luckMultiplier += (rod.getEnchantmentLevel(luckEnchantment) * 0.5);
            }
        }

        if (plugin.isXpSystemEnabled()) {
            FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
            if (data != null) {
                luckMultiplier += data.getCurrentLevel()
                        * plugin.getConfig().getDouble("xp-system.level-luck-bonus", 0.05);
            }
        }
        return luckMultiplier;
    }

    private void consumeBait(Player player, ItemStack offHandItem, BaitManager.Bait bait) {
        if (bait == null || offHandItem == null)
            return;

        if (offHandItem.getAmount() > 1) {
            offHandItem.setAmount(offHandItem.getAmount() - 1);
            player.getInventory().setItemInOffHand(offHandItem);
        } else {
            player.getInventory().setItemInOffHand(null);
        }
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.5f);
    }

    private void startMinigame(Player player, CustomFish fish) {
        MinigameTask task = new MinigameTask(plugin, player, fish);
        activeMinigames.put(player.getUniqueId(), task);
        task.start();
    }

    private void giveFishDirectly(Player player, CustomFish fish) {
        int amountToGive = calculateMultiCatchAmount(player);
        ItemStack caughtItem = fish.itemStack().clone();
        caughtItem.setAmount(amountToGive);

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(caughtItem);
        if (!overflow.isEmpty()) {
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }

        String fishName = PlainTextComponentSerializer.plainText().serialize(fish.name());
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%amount%", String.valueOf(amountToGive));
        placeholders.put("%fish_name%", fishName);

        String messageKey = amountToGive > 1 ? "fish_caught_multiple" : "fish_caught_single";
        player.sendActionBar(plugin.getMessageManager().getMessage(messageKey, false, placeholders));

        plugin.playCatchEffects(player, fish);

        if (plugin.isXpSystemEnabled() && fish.xpReward() > 0) {
            plugin.getCacheManager().addXpAndCheckLevel(player, fish.xpReward() * amountToGive);
        }
    }

    private int calculateMultiCatchAmount(Player player) {
        if (!plugin.isXpSystemEnabled() || !plugin.getConfig().getBoolean("xp-system.multi-catch.enabled", false)) {
            return 1;
        }

        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null)
            return 1;

        int level = data.getCurrentLevel();
        int level3x = plugin.getConfig().getInt("xp-system.multi-catch.level-3x", 999);
        int level2x = plugin.getConfig().getInt("xp-system.multi-catch.level-2x", 999);
        double chance3x = plugin.getConfig().getDouble("xp-system.multi-catch.chance-3x", 0.0);
        double chance2x = plugin.getConfig().getDouble("xp-system.multi-catch.chance-2x", 0.0);

        if (level >= level3x && ThreadLocalRandom.current().nextDouble() < chance3x)
            return 3;
        if (level >= level2x && ThreadLocalRandom.current().nextDouble() < chance2x)
            return 2;

        return 1;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (activeMinigames.containsKey(player.getUniqueId())) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                MinigameTask task = activeMinigames.get(player.getUniqueId());
                if (task.getSession() != null) {
                    task.getSession().setLastPullTick(System.currentTimeMillis());
                }
            }
        }
    }

    public NamespacedKey getRodKey() {
        return rodKey;
    }
}