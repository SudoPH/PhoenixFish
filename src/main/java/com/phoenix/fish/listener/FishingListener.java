package com.phoenix.fish.listener;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.CacheManager;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.manager.BaitManager;
import com.phoenix.fish.manager.ItemFixer;
import com.phoenix.fish.minigame.MinigameTask;
import com.phoenix.fish.model.CustomFish;
import com.phoenix.fish.util.FishRewardHelper;
import com.phoenix.fish.util.SoundUtils;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
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

        if (plugin.getFishingModeManager().isVanillaMode(player)) {
            MinigameTask staleTask = activeMinigames.remove(player.getUniqueId());
            if (staleTask != null) {
                staleTask.cancel();
            }
            return;
        }

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
        if (plugin.getFishingModeManager().isVanillaMode(player)) {
            return;
        }

        boolean minigameEnabled = plugin.getConfig().getBoolean("settings.minigame-enabled", true);
        ItemStack rod = player.getInventory().getItemInMainHand();

        double luckMultiplier = calculateLuckMultiplier(player, rod);

        // v1.9.1 — Use getEffectiveBait() which unifies the rod-bound bait
        // path (v1.9.1) with the legacy off-hand bait path. Falls back to
        // off-hand if no bait is bound to the rod. Returns null safely if
        // no bait is active — no NPE risk.
        ItemStack offHandItem = player.getInventory().getItemInOffHand();
        BaitManager.Bait bait = plugin.getBaitManager().getEffectiveBait(player, rod);

        Location location = player.getLocation();
        if (location == null)
            return;

        Biome playerBiome = location.getBlock().getBiome();

        // v1.6.2 (Fix #2) — Pass `player` to rollRandomFish so the new
        // player-aware overload pre-filters the candidate pool by depth +
        // day-night constraints BEFORE rolling. The old code rolled first,
        // retried up to 3 times if the roll didn't satisfy conditions, and
        // then silently gave the player an ineligible fish if all retries
        // failed. The new path returns a constraint-satisfying fish in one
        // shot (or falls back to the biome-only pool if the constrained pool
        // is empty, so the catch never silently fails).
        CustomFish fish = plugin.getFishManager().rollRandomFish(luckMultiplier, bait, playerBiome, player);
        if (fish == null)
            return;

        // v1.6.0 — Lucky Cast skill: bir üst rarelıkta balık şansı
        // v1.6.2 (Fix #3) — Config default changed from 0.02 to 0.10 per spec.
        // v1.8.6 — Use SkillManager.isSkillEnabled() which checks the new
        // features.skills.types.lucky-cast path with backward-compat fallback.
        if (plugin.getSkillManager() != null && plugin.getSkillManager().isSkillEnabled("lucky")) {
            try {
                com.phoenix.fish.data.FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
                if (data != null && data.getLuckyCast() > 0 && fish.rarity() < 5) {
                    double chance = data.getLuckyCast() * plugin.getConfig().getDouble(
                            "features.skill-lucky-cast.rarity-upgrade-chance-per-level", 0.10);
                    if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < chance) {
                        // Bir üst rarelıkta balık bul
                        int targetRarity = fish.rarity() + 1;
                        for (com.phoenix.fish.model.CustomFish candidate :
                                plugin.getFishManager().getAllFish()) {
                            if (candidate.rarity() == targetRarity
                                    && plugin.getFishManager().meetsConditions(candidate, player)) {
                                fish = candidate;
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) { }
        }

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
                // C-18 FIX: code default was 0.05 but config.yml ships 0.02. A user deleting the key
                // would silently get a 2.5× stronger luck bonus than the documented value.
                luckMultiplier += data.getCurrentLevel()
                        * plugin.getConfig().getDouble("xp-system.level-luck-bonus", 0.02);

                if (data.getMasterHunter() > 0) {
                    // v1.5.0 (F-6) — Hardcoded 0.02 yerine config-driven per-level değer.
                    // xp-system.skill-per-level.master-hunter-luck-bonus (default 0.02)
                    luckMultiplier += (data.getMasterHunter()
                            * plugin.getConfig().getDouble(
                                    "xp-system.skill-per-level.master-hunter-luck-bonus", 0.02));
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

    /**
     * v1.9.1 — Consume the active bait after a catch.
     *
     * <p><b>Fixes applied:</b></p>
     * <ul>
     *   <li><b>Config-driven Lucky Bait chance:</b> The old code hardcoded
     *       {@code 0.20} per level. Now reads from
     *       {@code xp-system.skill-per-level.lucky-bait-chance-per-level}
     *       (default 0.20) so the skill-per-level config actually matters.</li>
     *   <li><b>Inventory update after consumption:</b> The old code called
     *       {@code setItemInOffHand(null)} without refreshing the player's
     *       inventory, causing client-side desync. Now schedules a
     *       {@code player.updateInventory()} one tick later (Paper-recommended
     *       pattern for inventory mutation inside events).</li>
     *   <li><b>Rod-bound bait consumption:</b> If the active bait came from
     *       the rod's PDC (not the off-hand stack), we don't consume an item
     *       stack — the rod-bound bait is persistent. The off-hand stack is
     *       only consumed if the bait came from the off-hand path.</li>
     *   <li><b>Null-safe:</b> Guards against null bait, null off-hand item,
     *       and air off-hand items.</li>
     * </ul>
     */
    private void consumeBait(Player player, ItemStack offHandItem, BaitManager.Bait bait) {
        if (bait == null) return;

        // v1.9.1 — Lucky Bait skill check: chance to NOT consume the bait.
        // Config-driven per-level chance (default 0.20 = 20% per level).
        if (plugin.isXpSystemEnabled()) {
            FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
            if (data != null && data.getLuckyBait() > 0) {
                double chancePerLevel = plugin.getConfig().getDouble(
                        "xp-system.skill-per-level.lucky-bait-chance-per-level", 0.20);
                double chance = data.getLuckyBait() * chancePerLevel;
                if (ThreadLocalRandom.current().nextDouble() < chance) {
                    player.sendActionBar(plugin.getMessageManager().getMessage("skills_bait_saved", false));
                    return;
                }
            }
        }

        // v1.9.1 — Determine the consumption source.
        // If the bait is bound to the rod (rod_bait_id PDC key), we don't
        // consume an item stack — the rod-bound bait is persistent until
        // the player explicitly clears it. Only consume from the off-hand
        // stack if the bait came from the off-hand path.
        ItemStack rodItem = player.getInventory().getItemInMainHand();
        BaitManager.Bait rodBoundBait = (rodItem != null)
                ? plugin.getBaitManager().getBaitFromRod(rodItem) : null;

        if (rodBoundBait != null && rodBoundBait.id().equals(bait.id())) {
            // Bait came from the rod binding — no item stack to consume.
            // The rod-bound bait persists across casts.
            return;
        }

        // Bait came from the off-hand stack — decrement it.
        if (offHandItem == null || offHandItem.getType().isAir()) {
            // v1.9.1 — Defensive: the off-hand is empty but we resolved a
            // bait. This can happen if the player swapped items between the
            // cast start and now. Nothing to consume — bail safely.
            return;
        }

        if (offHandItem.getAmount() > 1) {
            offHandItem.setAmount(offHandItem.getAmount() - 1);
            player.getInventory().setItemInOffHand(offHandItem);
        } else {
            player.getInventory().setItemInOffHand(null);
        }

        // v1.9.1 — Schedule an inventory update one tick later to avoid
        // client/server desync (Paper-recommended pattern for inventory
        // mutation inside event handlers).
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);

        SoundUtils.playFromConfig(plugin, player, "sounds.bait_consume",
                Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.5f);
    }

    private void startMinigame(Player player, CustomFish fish) {
        // v1.3.5 — Ekipman etkili minigame: rod'un ve bait'in config'lerini
        // MinigameTask constructor'ına geç. Eski çağrı şartları korunur.
        ItemStack rodItem = player.getInventory().getItemInMainHand();
        ConfigurationSection rodConfig = null;
        if (rodItem != null && rodItem.getType().name().endsWith("_FISHING_ROD")) {
            try {
                rodConfig = itemFixer.findRodByItem(rodItem);
            } catch (Throwable ignored) {
                // ItemFixer erişilemezse minigame eski davranışla çalışır (no bonus).
            }
        }

        // v1.9.1 — Use getEffectiveBait() for the minigame too, so rod-bound
        // baits apply their minigame bonuses (fight-strength-reduction,
        // fish-slow-bonus, progress-bonus) just like off-hand baits.
        ItemStack rodItemForBait = player.getInventory().getItemInMainHand();
        BaitManager.Bait bait = null;
        try {
            bait = plugin.getBaitManager().getEffectiveBait(player, rodItemForBait);
        } catch (Throwable ignored) {
            // BaitManager erişilemezse minigame eski davranışla çalışır (no bonus).
        }

        MinigameTask task = new MinigameTask(plugin, player, fish, rodConfig, bait);
        activeMinigames.put(player.getUniqueId(), task);
        task.start();
    }

    private void giveFishDirectly(Player player, CustomFish fish) {
        FishRewardHelper.awardCatch(plugin, player, fish, FishRewardHelper.CatchSource.DIRECT);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (plugin.getFishingModeManager().isVanillaMode(player)) {
            MinigameTask staleTask = activeMinigames.remove(player.getUniqueId());
            if (staleTask != null) {
                staleTask.cancel();
            }
            return;
        }

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

            // v1.9.1 — Bait-equip handler: right-click a fishing rod while
            // sneaking + holding a bait in the off-hand → bind the bait to
            // the rod's PDC. Right-click a rod that already has a bait bound
            // while sneaking → clear the bound bait (toggle behavior).
            //
            // This gives players an alternative to the off-hand bait path:
            // they can "equip" a bait onto the rod once, and it persists
            // across casts without consuming the off-hand stack.
            if (item != null && item.getType() == Material.FISHING_ROD
                    && player.isSneaking()) {
                BaitManager bm = plugin.getBaitManager();
                if (bm != null) {
                    BaitManager.Bait existingRodBait = bm.getBaitFromRod(item);
                    if (existingRodBait != null) {
                        // Toggle off — clear the bound bait
                        bm.clearBaitFromRod(item);
                        event.setCancelled(true);
                        player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                .deserialize("<gray>Bait unequipped from your rod."));
                        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                    } else {
                        // Try to equip from off-hand
                        ItemStack offHand = player.getInventory().getItemInOffHand();
                        BaitManager.Bait offHandBait = (offHand != null)
                                ? bm.getBaitFromItem(offHand) : null;
                        if (offHandBait != null) {
                            if (bm.bindBaitToRod(item, offHandBait.id())) {
                                event.setCancelled(true);
                                player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                        .deserialize("<green>✔ Bait equipped: <white>"
                                                + offHandBait.id() + "</white> <gray>(sneak + right-click to remove)"));
                                Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                            }
                        }
                    }
                }
            }

            if (item != null && item.getType() == Material.FISHING_ROD) {
                ConfigurationSection rodSec = itemFixer.findRodByItem(item);
                if (rodSec != null) {
                    int requiredLevel = rodSec.getInt("fishing-level-requirement", 1);
                    // v1.6.1 (bug #4 fix): CacheManager can be null when both xp-system.enabled
                    // and settings.discovery-enabled are false (or DB init failed). Guard to avoid NPE.
                    CacheManager cache = plugin.getCacheManager();
                    FishingData data = (cache != null) ? cache.getData(player.getUniqueId()) : null;
                    if (data == null || data.getCurrentLevel() < requiredLevel) {
                        event.setCancelled(true);
                        Map<String, String> rodPh = Map.of(
                                "%level%", String.valueOf(requiredLevel),
                                "%current%", String.valueOf(data != null ? data.getCurrentLevel() : 0));
                        player.sendMessage(plugin.getMessageManager().getMessage("rod_level_required", true, rodPh));
                        // H-18 FIX: calling updateInventory() inside PlayerInteractEvent is a known
                        // client/server desync source per Paper docs. Schedule it one tick later
                        // so the event completes first, then refresh.
                        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                    }
                }
            }
        }
    }

    public NamespacedKey getRodKey() {
        return rodKey;
    }
}
