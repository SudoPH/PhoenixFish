package com.phoenix.fish.util;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.event.FishCaughtEvent;
import com.phoenix.fish.model.CustomFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class FishRewardHelper {

    private FishRewardHelper() {
        throw new UnsupportedOperationException("Utility class — no instances");
    }

    public enum CatchSource {
        DIRECT,
        MINIGAME
    }

    public static void awardCatch(PhoenixFish plugin, Player player, CustomFish fish, CatchSource source) {
        if (plugin == null || player == null || fish == null) {
            return;
        }
        if (source == null) {
            source = CatchSource.DIRECT;
        }

        int amountToGive = calculateMultiCatchAmount(plugin, player);

        double weight = rollWeight(fish);
        String formattedWeight = String.format(Locale.US, "%.2f", weight);

        FishCaughtEvent caughtEvent = new FishCaughtEvent(player, fish, weight);

        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(caughtEvent);
        } else {
            CompletableFuture<Void> fired = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    Bukkit.getPluginManager().callEvent(caughtEvent);
                    fired.complete(null);
                } catch (Throwable t) {
                    fired.completeExceptionally(t);
                }
            });
            try {
                fired.join();
            } catch (Throwable t) {
                plugin.getLogger().warning("FishCaughtEvent dispatch failed on main thread: "
                        + t.getMessage());
            }
        }
        if (caughtEvent.isCancelled()) {
            player.sendActionBar(plugin.getMessageManager().getMessage("catch_cancelled", false));
            return;
        }

        ItemStack fishItem = fish.itemStack();
        if (fishItem == null) {
            plugin.getLogger().warning("Fish itemStack is null for fish: " + fish.id());
            return;
        }
        ItemStack caughtItem = fishItem.clone();
        caughtItem.setAmount(amountToGive);

        applyWeightToItem(plugin, caughtItem, weight, formattedWeight);

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(caughtItem);
        if (!overflow.isEmpty()) {
            Location location = player.getLocation();
            if (location != null) {
                for (ItemStack drop : overflow.values()) {
                    player.getWorld().dropItemNaturally(location, drop);
                }
            }
        }

        sendCatchMessage(plugin, player, fish, amountToGive, source);

        plugin.playCatchEffects(player, fish);

        plugin.getRecordManager().checkRecords(player, fish, weight, formattedWeight);

        if (plugin.getTournamentManager().isActive()) { 
            int points = calculateTournamentPoints(fish);
            plugin.getTournamentManager().addScore(player, points * amountToGive);
        }

        if (plugin.isDiscoveryEnabled()) {
            FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
            if (data != null && data.discoverFish(fish.id())) {
                SoundUtils.playFromConfig(plugin, player, "sounds.discover",
                        Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            }
        }

        if (plugin.isXpSystemEnabled() && fish.xpReward() > 0) {
            plugin.getCacheManager().addXpAndCheckLevel(player, fish.xpReward() * amountToGive);
        }
    }

    private static int calculateMultiCatchAmount(PhoenixFish plugin, Player player) {
        if (!plugin.isXpSystemEnabled()) {
            return 1;
        }

        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null || data.getDoubleCatch() == 0) {
            return 1;
        }

        double chance = data.getDoubleCatch() * 0.10;
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            return 2;
        }

        return 1;
    }

    private static double rollWeight(CustomFish fish) {
        return ThreadLocalRandom.current().nextDouble(fish.minWeight(), fish.maxWeight());
    }

    // M-21 FIX: rarity-weighted tournament points.
    private static int calculateTournamentPoints(CustomFish fish) {
        return switch (fish.rarity()) {
            case 1 -> 1;   // common
            case 2 -> 2;   // uncommon
            case 3 -> 5;   // rare
            case 4 -> 12;  // epic
            case 5 -> 25;  // legendary
            default -> 1;
        };
    }

    private static void applyWeightToItem(PhoenixFish plugin, ItemStack item,
            double weight, String formattedWeight) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        List<Component> existing = meta.lore();
        List<Component> lore;
        if (existing == null || existing.isEmpty()) {
            lore = new ArrayList<>();
        } else {
            lore = new ArrayList<>(existing);
        }
        Map<String, String> weightPh = Map.of("%weight%", formattedWeight);
        Component weightLore = plugin.getMessageManager().getMessage("fish_lore_weight", false, weightPh);
        lore.add(weightLore);
        meta.lore(lore);

        item.setItemMeta(meta);

        plugin.getEconomyManager().setWeight(item, weight);
    }

    private static void sendCatchMessage(PhoenixFish plugin, Player player, CustomFish fish,
            int amountToGive, CatchSource source) {
        if (source == CatchSource.MINIGAME) {
            if (amountToGive > 1) {
                Map<String, String> ph = Map.of("%amount%", String.valueOf(amountToGive));
                player.sendActionBar(plugin.getMessageManager().getMessage("minigame_win_multiple", false, ph));
            } else {
                player.sendActionBar(plugin.getMessageManager().getMessage("minigame_win_single", false));
            }
        } else {
            String fishName = PlainTextComponentSerializer.plainText().serialize(fish.name());
            Map<String, String> ph = Map.of(
                    "%amount%", String.valueOf(amountToGive),
                    "%fish_name%", fishName);
            String key = amountToGive > 1 ? "fish_caught_multiple" : "fish_caught_single";
            player.sendActionBar(plugin.getMessageManager().getMessage(key, false, ph));
        }
    }
}
