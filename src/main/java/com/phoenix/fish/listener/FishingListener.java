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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class FishingListener implements Listener {

    private final PhoenixFish plugin;
    private final ConcurrentHashMap<UUID, MinigameTask> activeMinigames;
    private final NamespacedKey rodKey;
    private final ItemFixer itemFixer;
    private final Enchantment luckEnchantment;

    public FishingListener(PhoenixFish plugin) {
        this.plugin = plugin;
        this.activeMinigames = new ConcurrentHashMap<>();
        this.itemFixer = new ItemFixer(plugin);
        this.rodKey = new NamespacedKey(plugin, "luck_multiplier");

        NamespacedKey luckKey = NamespacedKey.minecraft("luck_of_the_sea");
        this.luckEnchantment = RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.ENCHANTMENT)
                .get(luckKey);
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

    public void shutdown() {
        for (MinigameTask task : activeMinigames.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        activeMinigames.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        itemFixer.fixInventory(player.getInventory());
        itemFixer.fixInventory(player.getEnderChest());

        if (plugin.getTournamentManager().isActive() && plugin.getTournamentManager().getBossBar() != null) {
            plugin.getTournamentManager().getBossBar().addPlayer(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        MinigameTask task = activeMinigames.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!plugin.getConfig().getBoolean("settings.fix-items-on-chest-open", true)) {
            return;
        }

        InventoryType type = event.getInventory().getType();
        if (type == InventoryType.CHEST || type == InventoryType.BARREL ||
                type == InventoryType.SHULKER_BOX || type == InventoryType.ENDER_CHEST) {
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

        Location location = player.getLocation();
        if (location == null)
            return;

        Biome playerBiome = location.getBlock().getBiome();

        CustomFish fish = plugin.getFishManager().rollRandomFish(luckMultiplier, bait, playerBiome);
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
                Double stored = meta.getPersistentDataContainer().get(rodKey, PersistentDataType.DOUBLE);
                if (stored != null) {
                    luckMultiplier = stored;
                }
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

                if (data.getMasterHunter() > 0) {
                    luckMultiplier += (data.getMasterHunter() * 0.02);
                }
            }
        }

        boolean isRaining = player.getWorld().hasStorm();
        boolean isThundering = player.getWorld().isThundering();

        if (isThundering) {
            luckMultiplier += plugin.getConfig().getDouble("weather.storm-luck-bonus", 0.5);
        } else if (isRaining) {
            luckMultiplier += plugin.getConfig().getDouble("weather.rain-luck-bonus", 0.2);
        }

        return luckMultiplier;
    }

    private void consumeBait(Player player, ItemStack offHandItem, BaitManager.Bait bait) {
        if (bait == null || offHandItem == null)
            return;

        if (plugin.isXpSystemEnabled()) {
            FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
            if (data != null && data.getLuckyBait() > 0) {
                double chance = data.getLuckyBait() * 0.20;
                if (ThreadLocalRandom.current().nextDouble() < chance) {
                    player.sendActionBar(plugin.getMessageManager().getMessage("skills_bait_saved", false));
                    return;
                }
            }
        }

        if (offHandItem.getAmount() > 1) {
            offHandItem.setAmount(offHandItem.getAmount() - 1);
            player.getInventory().setItemInOffHand(offHandItem);
        } else {
            player.getInventory().setItemInOffHand(null);
        }

        playSoundFromConfig(player, "sounds.bait_consume", Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.5f);
    }

    private void startMinigame(Player player, CustomFish fish) {
        MinigameTask task = new MinigameTask(plugin, player, fish);
        activeMinigames.put(player.getUniqueId(), task);
        task.start();
    }

    private void giveFishDirectly(Player player, CustomFish fish) {
        int amountToGive = calculateMultiCatchAmount(player);

        ItemStack caughtItem = fish.itemStack();
        if (caughtItem == null) {
            plugin.getLogger().warning("Fish itemStack is null for fish: " + fish.id());
            return;
        }

        caughtItem = caughtItem.clone();
        caughtItem.setAmount(amountToGive);

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(caughtItem);
        if (!overflow.isEmpty()) {
            Location location = player.getLocation();
            if (location != null) {
                for (ItemStack drop : overflow.values()) {
                    player.getWorld().dropItemNaturally(location, drop);
                }
            }
        }

        String fishName = PlainTextComponentSerializer.plainText().serialize(fish.name());
        Map<String, String> placeholders = Map.of(
                "%amount%", String.valueOf(amountToGive),
                "%fish_name%", fishName);

        String messageKey = amountToGive > 1 ? "fish_caught_multiple" : "fish_caught_single";
        player.sendActionBar(plugin.getMessageManager().getMessage(messageKey, false, placeholders));

        plugin.playCatchEffects(player, fish);

        double minW = fish.minWeight();
        double maxW = fish.maxWeight();
        double weight = ThreadLocalRandom.current().nextDouble(minW, maxW);
        String formattedWeight = String.format("%.2f", weight);

        plugin.getRecordManager().checkRecords(player, fish, weight, formattedWeight);

        if (plugin.getTournamentManager().isActive()) {
            int points = (fish.rarity() >= 3) ? 5 : 1;
            plugin.getTournamentManager().addScore(player, points * amountToGive);
        }

        if (plugin.isDiscoveryEnabled()) {
            FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
            if (data != null && data.discoverFish(fish.id())) {
                playSoundFromConfig(player, "sounds.discover", Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            }
        }

        if (plugin.isXpSystemEnabled() && fish.xpReward() > 0) {
            plugin.getCacheManager().addXpAndCheckLevel(player, fish.xpReward() * amountToGive);
        }
    }

    private int calculateMultiCatchAmount(Player player) {
        if (!plugin.isXpSystemEnabled())
            return 1;

        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null || data.getDoubleCatch() == 0)
            return 1;

        double chance = data.getDoubleCatch() * 0.10;
        if (ThreadLocalRandom.current().nextDouble() < chance)
            return 2;

        return 1;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        MinigameTask task = activeMinigames.get(player.getUniqueId());
        if (task != null) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
                if (task.getSession() != null) {
                    task.getSession().setLastPullTick(System.currentTimeMillis());
                }
            }
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item != null && item.getType() == Material.FISHING_ROD) {
                ConfigurationSection rodSec = itemFixer.findRodByItem(item);
                if (rodSec != null) {
                    int requiredLevel = rodSec.getInt("fishing-level-requirement", 1);
                    FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
                    if (data == null || data.getCurrentLevel() < requiredLevel) {
                        event.setCancelled(true);
                        player.sendMessage("§cBu oltayı kullanmak için " + requiredLevel
                                + " balıkçılık seviyesi gerekir! (Mevcut: " +
                                (data != null ? data.getCurrentLevel() : 0) + ")");
                        player.updateInventory();
                    }
                }
            }
        }
    }

    public NamespacedKey getRodKey() {
        return rodKey;
    }

    @SuppressWarnings("removal")
    private void playSoundFromConfig(Player player, String path, Sound defaultSound, float volume, float pitch) {
        String soundName = plugin.getConfig().getString(path);
        Sound soundToPlay = defaultSound;

        if (soundName != null && !soundName.isEmpty()) {
            try {
                soundToPlay = Sound.valueOf(soundName.toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid sound in config: " + soundName + " at path: " + path);
            }
        }

        player.playSound(player, soundToPlay, volume, pitch);
    }
}