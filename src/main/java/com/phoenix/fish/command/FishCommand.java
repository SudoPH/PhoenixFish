package com.phoenix.fish.command;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.manager.ItemFixer;
import com.phoenix.fish.manager.ItemUtils;
import com.phoenix.fish.task.ContainerFixTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
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
            default -> sendHelp(player);
        }
        return true;
    }

    private void handleFix(Player player) {
        ItemFixer fixer = plugin.getFishingListener().getItemFixer();
        int[] result = fixer.fixInventory(player.getInventory());

        Map<String, String> fixPh = new HashMap<>();
        fixPh.put("%rods%", String.valueOf(result[0]));
        fixPh.put("%baits%", String.valueOf(result[1]));
        player.sendMessage(plugin.getMessageManager().getMessage("fix_inventory", true, fixPh));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_USE, 0.5f, 1.2f);
    }

    private void handleFixAll(Player player) {
        if (!player.hasPermission("phoenixfish.admin")) {
            player.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
            return;
        }

        ItemFixer fixer = plugin.getFishingListener().getItemFixer();

        ContainerFixTask task = new ContainerFixTask(plugin, fixer, player);
        Map<String, String> startPh = new HashMap<>();
        startPh.put("%chunks%", String.valueOf(task.getTotalChunks()));
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
                    Map<String, String> onlinePh = new HashMap<>();
                    onlinePh.put("%players%", String.valueOf(playerCount));
                    onlinePh.put("%rods%", String.valueOf(totalRods));
                    onlinePh.put("%baits%", String.valueOf(totalBaits));
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

        ItemStack rod = createRodItem(rodSec);
        if (rod == null) {
            Map<String, String> matPh = new HashMap<>();
            matPh.put("%material%", rodSec.getString("material", "FISHING_ROD"));
            player.sendMessage(plugin.getMessageManager().getMessage("invalid_material", true, matPh));
            return;
        }

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(rod);
        if (!overflow.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rod);
        }

        player.sendMessage(plugin.getMessageManager().getMessage("rod_given", true));
    }

    private ItemStack createRodItem(ConfigurationSection rodSec) {
        String matStr = rodSec.getString("material", "FISHING_ROD").toUpperCase();
        Material mat = Material.matchMaterial(matStr);
        if (mat == null)
            return null;

        ItemStack rod = new ItemStack(mat);
        ItemMeta meta = rod.getItemMeta();
        if (meta == null)
            return rod;

        meta.displayName(miniMessage.deserialize(rodSec.getString("display-name", "<white>Fishing Rod</white>")));

        ItemUtils.applyCustomModelData(meta, rodSec);

        if (rodSec.contains("lore")) {
            List<String> loreLines = rodSec.getStringList("lore");
            List<net.kyori.adventure.text.Component> loreComponents = new ArrayList<>();
            for (String line : loreLines) {
                loreComponents.add(miniMessage.deserialize(line));
            }
            meta.lore(loreComponents);
        }

        double luck = rodSec.getDouble("luck-multiplier", 1.0);
        meta.getPersistentDataContainer().set(plugin.getFishingListener().getRodKey(), PersistentDataType.DOUBLE, luck);

        rod.setItemMeta(meta);
        return rod;
    }

    private void sendHelp(Player player) {
        player.sendMessage(plugin.getMessageManager().getMessage("usage_giverod", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_fix", true));
        if (player.hasPermission("phoenixfish.admin")) {
            player.sendMessage(plugin.getMessageManager().getMessage("usage_fixall", true));
        }
    }
}