package com.phoenix.fish.command;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.manager.ItemFixer;
import com.phoenix.fish.manager.ItemUtils;
import com.phoenix.fish.manager.TournamentManager;
import com.phoenix.fish.task.ContainerFixTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FishCommand implements CommandExecutor {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;

    public FishCommand(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessageManager().getMessage("player_only", true));
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "fix" -> handleFix(player);
            case "fixall" -> handleFixAll(player);
            case "giverod" -> {
                if (args.length < 2) {
                    player.sendMessage(plugin.getMessageManager().getMessage("usage_giverod", true));
                    return true;
                }
                handleGiveRod(player, args[1]);
            }
            case "catalog" -> plugin.getCatalogManager().openMainMenu(player);
            case "skills" -> plugin.getSkillManager().openMenu(player);
            case "tournament" -> handleTournament(player, args);
            case "sell" -> handleSell(player, args);
            case "addlevel" -> handleAddLevel(player, args, false);
            case "addcraft" -> handleAddLevel(player, args, true);
            default -> sendHelp(player);
        }
        return true;
    }

    private void handleSell(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getMessageManager().getMessage("usage_sell", true));
            return;
        }

        if (args[1].equalsIgnoreCase("hand")) {
            plugin.getEconomyManager().sellHand(player);
        } else if (args[1].equalsIgnoreCase("menu") || args[1].equalsIgnoreCase("gui")) {
            plugin.getEconomyManager().openSellGUI(player);
        } else {
            player.sendMessage(plugin.getMessageManager().getMessage("usage_sell", true));
        }
    }

    private void handleTournament(Player player, String[] args) {
        if (!player.hasPermission("phoenixfish.tournament.admin")) {
            player.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(plugin.getMessageManager().getMessage("usage_tournament", true));
            return;
        }

        TournamentManager manager = plugin.getTournamentManager();

        switch (args[1].toLowerCase()) {
            case "start" -> {
                if (!manager.isEnabled()) {
                    player.sendMessage(plugin.getMessageManager().getMessage("tournament_disabled", true));
                    return;
                }
                if (manager.isActive()) {
                    player.sendMessage(plugin.getMessageManager().getMessage("tournament_already_active", true));
                    return;
                }
                int minutes = 30;
                if (args.length >= 3) {
                    try {
                        minutes = Integer.parseInt(args[2]);
                        if (minutes <= 0) {
                            player.sendMessage(plugin.getMessageManager().getMessage("usage_tournament", true));
                            return;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                manager.start(minutes);

                Map<String, String> ph = Map.of("%minutes%", String.valueOf(minutes));
                Bukkit.broadcast(plugin.getMessageManager().getMessage("tournament_start", true, ph));
            }
            case "stop" -> {
                if (!manager.isActive()) {
                    player.sendMessage(plugin.getMessageManager().getMessage("tournament_no_active", true));
                    return;
                }
                manager.stop(false);
                Bukkit.broadcast(plugin.getMessageManager().getMessage("tournament_stopped", true));
            }
            case "addreward" -> handleAddReward(player, args);
            default -> player.sendMessage(plugin.getMessageManager().getMessage("usage_tournament", true));
        }
    }

    private void handleAddReward(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getMessageManager().getMessage("usage_addreward", true));
            return;
        }

        int rank;
        try {
            rank = Integer.parseInt(args[2]);
            if (rank <= 0) {
                player.sendMessage(plugin.getMessageManager().getMessage("usage_addreward", true));
                return;
            }
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getMessageManager().getMessage("usage_addreward", true));
            return;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand == null || itemInHand.getType().isAir()) {
            player.sendMessage(plugin.getMessageManager().getMessage("addreward_no_item", true));
            return;
        }

        String base64 = ItemUtils.itemToBase64(itemInHand);
        if (base64 == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("addreward_error", true));
            return;
        }

        plugin.getConfig().set("tournament.rewards." + rank + ".item", base64);
        Bukkit.getScheduler().runTask(plugin, plugin::saveConfig);

        Map<String, String> ph = Map.of("%rank%", String.valueOf(rank));
        player.sendMessage(plugin.getMessageManager().getMessage("addreward_success", true, ph));
    }

    private void handleFix(Player player) {
        ItemFixer fixer = plugin.getFishingListener().getItemFixer();
        int[] result = fixer.fixInventory(player.getInventory());

        Map<String, String> fixPh = Map.of(
                "%rods%", String.valueOf(result[0]),
                "%baits%", String.valueOf(result[1]));
        player.sendMessage(plugin.getMessageManager().getMessage("fix_inventory", true, fixPh));

        playSoundFromConfig(player, "sounds.fix", Sound.BLOCK_ANVIL_USE, 0.5f, 1.2f);
    }

    private void handleFixAll(Player player) {
        if (!player.hasPermission("phoenixfish.admin")) {
            player.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
            return;
        }

        ItemFixer fixer = plugin.getFishingListener().getItemFixer();

        ContainerFixTask task = new ContainerFixTask(plugin, fixer, player);
        Map<String, String> startPh = Map.of("%chunks%", String.valueOf(task.getTotalChunks()));
        player.sendMessage(plugin.getMessageManager().getMessage("fixall_start", true, startPh));
        player.sendMessage(plugin.getMessageManager().getMessage("fixall_offline_note", true));

        new BukkitRunnable() {
            int totalRods = 0, totalBaits = 0, playerCount = 0;
            final List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
            int index = 0;

            @Override
            public void run() {
                int processed = 0;
                while (processed < 5 && index < players.size()) {
                    Player online = players.get(index);
                    int[] mainResult = fixer.fixInventory(online.getInventory());
                    int[] enderResult = fixer.fixInventory(online.getEnderChest());
                    totalRods += mainResult[0] + enderResult[0];
                    totalBaits += mainResult[1] + enderResult[1];
                    playerCount++;
                    index++;
                    processed++;
                }

                if (index >= players.size()) {
                    cancel();
                    Map<String, String> onlinePh = Map.of(
                            "%players%", String.valueOf(playerCount),
                            "%rods%", String.valueOf(totalRods),
                            "%baits%", String.valueOf(totalBaits));
                    player.sendMessage(plugin.getMessageManager().getMessage("fixall_done_online", true, onlinePh));

                    task.runTaskTimer(plugin, 0L, 1L);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void handleGiveRod(Player player, String rodId) {
        if (!player.hasPermission("phoenixfish.admin")) {
            player.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
            return;
        }

        rodId = rodId.toLowerCase();
        ConfigurationSection rodSec = plugin.getFishingListener().getItemFixer().getRodConfig(rodId);

        if (rodSec == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("rod_not_found", true));
            return;
        }

        ItemStack rod = plugin.getFishingListener().getItemFixer().createRodItem(rodSec);
        if (rod == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("invalid_material", true));
            return;
        }

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(rod);
        if (!overflow.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rod);
        }

        player.sendMessage(plugin.getMessageManager().getMessage("rod_given", true));
    }

    private void handleAddLevel(Player player, String[] args, boolean isCrafting) {
        if (!player.isOp()) {
            player.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
            return;
        }

        if (args.length < 2) {
            player.sendMessage("§cKullanım: /phoenixfish " + args[0] + " <miktar>");
            return;
        }

        try {
            int levelsToAdd = Integer.parseInt(args[1]);
            FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
            if (data == null) {
                player.sendMessage("§cVerilerin yüklenemedi, tekrar deneyin.");
                return;
            }

            if (isCrafting) {
                int newLevel = Math.max(1, data.getCraftingLevel() + levelsToAdd);
                data.setCraftingLevel(newLevel);
                player.sendMessage("§aBaşarıyla " + levelsToAdd + " Zanaat(Crafting) seviyesi eklendi! Yeni seviye: §e"
                        + newLevel);
            } else {
                int newLevel = Math.max(1, data.getCurrentLevel() + levelsToAdd);
                data.setCurrentLevel(newLevel);
                player.sendMessage(
                        "§aBaşarıyla " + levelsToAdd + " Balıkçılık seviyesi eklendi! Yeni seviye: §e" + newLevel);
            }
        } catch (NumberFormatException e) {
            player.sendMessage("§cLütfen geçerli bir sayı girin!");
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage(plugin.getMessageManager().getMessage("usage_giverod", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_fix", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_catalog", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_skills", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_sell", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_tournament", true));
        if (player.hasPermission("phoenixfish.admin")) {
            player.sendMessage(plugin.getMessageManager().getMessage("usage_fixall", true));
        }
        if (player.isOp()) {
            player.sendMessage("§7/phoenixfish addlevel <miktar> §8- §7Balıkçılık seviyesi ekle");
            player.sendMessage("§7/phoenixfish addcraft <miktar> §8- §7Zanaat seviyesi ekle");
        }
    }

    @SuppressWarnings({ "removal" })
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