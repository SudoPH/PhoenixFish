package com.phoenix.fish.command;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.manager.FishingModeManager;
import com.phoenix.fish.manager.ItemFixer;
import com.phoenix.fish.manager.ItemUtils;
import com.phoenix.fish.manager.MessageManager;
import com.phoenix.fish.manager.TournamentManager;
import com.phoenix.fish.task.ContainerFixTask;
import net.kyori.adventure.text.Component;
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

    public FishCommand(PhoenixFish plugin) {
        this.plugin = plugin;
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
            case "toggle" -> handleToggle(player, args);
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
            player.sendMessage(plugin.getMessageManager().getMessage("usage_addlevel", true));
            return;
        }

        // H3 FIX: cacheManager is null when the XP system is disabled -> NPE.
        if (!plugin.isXpSystemEnabled() || plugin.getCacheManager() == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("addlevel_xp_disabled", true));
            return;
        }

        try {
            int levelsToAdd = Integer.parseInt(args[1]);
            FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
            if (data == null) {
                player.sendMessage(plugin.getMessageManager().getMessage("addlevel_data_error", true));
                return;
            }

            if (isCrafting) {
                int newLevel = Math.max(1, data.getCraftingLevel() + levelsToAdd);
                data.setCraftingLevel(newLevel);
                player.sendMessage(plugin.getMessageManager().getMessage("addlevel_success_crafting", true,
                        Map.of("%amount%", String.valueOf(levelsToAdd),
                                "%level%", String.valueOf(newLevel))));
            } else {
                int newLevel = Math.max(1, data.getCurrentLevel() + levelsToAdd);
                data.setCurrentLevel(newLevel);
                player.sendMessage(plugin.getMessageManager().getMessage("addlevel_success_fishing", true,
                        Map.of("%amount%", String.valueOf(levelsToAdd),
                                "%level%", String.valueOf(newLevel))));
            }
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getMessageManager().getMessage("addlevel_invalid_number", true));
        }
    }

    // ==================================================================
    // FISHING MODE TOGGLE
    //   /phoenixfish toggle                  -> switch your own mode
    //   /phoenixfish toggle status           -> show your current mode
    //   /phoenixfish toggle status <player>  -> (Admin) view a player
    //   /phoenixfish toggle <player>         -> (Admin) switch a player
    //   /phoenixfish toggle reset <player>   -> (Admin) reset to default
    // All text comes from messages_tr.yml / messages_en.yml keys.
    // ==================================================================
    private void handleToggle(Player player, String[] args) {
        FishingModeManager modeManager = plugin.getFishingModeManager();

        // /phoenixfish toggle -> self toggle
        if (args.length == 1) {
            handleToggleResult(player, player, modeManager.toggle(player), true);
            return;
        }

        String sub = args[1].toLowerCase();
        switch (sub) {
            case "status" -> {
                if (args.length >= 3) {
                    if (!player.hasPermission("phoenixfish.admin")) {
                        player.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
                        return;
                    }
                    Player target = Bukkit.getPlayerExact(args[2]);
                    if (target == null) {
                        player.sendMessage(plugin.getMessageManager().getMessage("fishing_mode_player_not_found",
                                true, Map.of("%player%", args[2])));
                        return;
                    }
                    sendToggleStatus(player, target, false);
                } else {
                    sendToggleStatus(player, player, true);
                }
            }
            case "reset" -> {
                if (args.length < 3) {
                    player.sendMessage(plugin.getMessageManager().getMessage("usage_toggle_admin_reset", true));
                    return;
                }
                if (!player.hasPermission("phoenixfish.admin")) {
                    player.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    player.sendMessage(plugin.getMessageManager().getMessage("fishing_mode_player_not_found",
                            true, Map.of("%player%", args[2])));
                    return;
                }

                modeManager.resetPlayer(target.getUniqueId());

                // Resulting mode is now the server default
                boolean vanilla = modeManager.isVanillaMode(target);
                String modeName = plugin.getMessageManager()
                        .getPlainMessage(vanilla ? "mode_vanilla" : "mode_custom");

                player.sendMessage(plugin.getMessageManager().getMessage("fishing_mode_reset", true,
                        Map.of("%player%", target.getName(), "%mode%", modeName)));
                sendModeInfo(target, vanilla);
                playSoundFromConfig(target, "sounds.mode_toggle", Sound.UI_BUTTON_CLICK, 0.6f, 1.5f);
            }
            default -> {
                // /phoenixfish toggle <player> -> (Admin) switch another player
                if (!player.hasPermission("phoenixfish.admin")) {
                    player.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    player.sendMessage(plugin.getMessageManager().getMessage("fishing_mode_player_not_found",
                            true, Map.of("%player%", args[1])));
                    return;
                }
                handleToggleResult(player, target, modeManager.toggle(target), false);
            }
        }
    }

    private void handleToggleResult(CommandSender actor, Player target,
            FishingModeManager.ToggleResult result, boolean self) {
        MessageManager messages = plugin.getMessageManager();

        switch (result) {
            case SUCCESS -> notifyModeChange(actor, target, self);
            case NO_PERMISSION ->
                    actor.sendMessage(messages.getMessage("no_permission", true));
            case FEATURE_DISABLED ->
                    actor.sendMessage(messages.getMessage("fishing_mode_disabled", true));
            case COOLDOWN -> {
                long seconds = plugin.getFishingModeManager().getRemainingCooldown(target);
                actor.sendMessage(messages.getMessage("fishing_mode_cooldown", true,
                        Map.of("%seconds%", String.valueOf(seconds))));
            }
        }
    }

    private void sendToggleStatus(Player viewer, Player target, boolean self) {
        MessageManager messages = plugin.getMessageManager();
        FishingModeManager modeManager = plugin.getFishingModeManager();

        boolean vanilla = modeManager.isVanillaMode(target);
        String modeName = messages.getPlainMessage(vanilla ? "mode_vanilla" : "mode_custom");

        boolean hasChoice = modeManager.hasPlayerChoice(target);
        String source = messages.getPlainMessage(
                hasChoice ? "fishing_mode_source_choice" : "fishing_mode_source_default");

        if (self) {
            viewer.sendMessage(messages.getMessage("fishing_mode_status", true,
                    Map.of("%mode%", modeName, "%source%", source)));
        } else {
            viewer.sendMessage(messages.getMessage("fishing_mode_status_other", true,
                    Map.of("%player%", target.getName(), "%mode%", modeName, "%source%", source)));
        }
    }

    private void notifyModeChange(CommandSender actor, Player target, boolean self) {
        MessageManager messages = plugin.getMessageManager();

        boolean vanilla = plugin.getFishingModeManager().isVanillaMode(target);
        String modeName = messages.getPlainMessage(vanilla ? "mode_vanilla" : "mode_custom");

        // The affected player always gets the multi-line info list
        // (self-toggle: this list is also the confirmation message)
        sendModeInfo(target, vanilla);

        // Admin forced the change -> also confirm to the admin
        if (!self) {
            actor.sendMessage(messages.getMessage("fishing_mode_changed_other", true,
                    Map.of("%player%", target.getName(), "%mode%", modeName)));
        }

        // Optional server-wide broadcast (config: fishing-mode.broadcast-change)
        if (plugin.getConfig().getBoolean("fishing-mode.broadcast-change", false)) {
            Bukkit.broadcast(messages.getMessage("fishing_mode_broadcast", true,
                    Map.of("%player%", target.getName(), "%mode%", modeName)));
        }

        playSoundFromConfig(target, "sounds.mode_toggle", Sound.UI_BUTTON_CLICK, 0.6f, 1.5f);
    }

    /** Sends the multi-line "mode enabled" info list to a player (no prefix - it is built into the list). */
    private void sendModeInfo(Player target, boolean vanilla) {
        String infoKey = vanilla ? "fishing_mode_vanilla_enabled" : "fishing_mode_custom_enabled";
        for (Component line : plugin.getMessageManager().getMessageList(infoKey, null)) {
            target.sendMessage(line);
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage(plugin.getMessageManager().getMessage("usage_giverod", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_fix", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_catalog", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_skills", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_sell", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_toggle", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_toggle_status", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_tournament", true));
        if (player.hasPermission("phoenixfish.admin")) {
            player.sendMessage(plugin.getMessageManager().getMessage("usage_fixall", true));
            player.sendMessage(plugin.getMessageManager().getMessage("usage_toggle_admin", true));
            player.sendMessage(plugin.getMessageManager().getMessage("usage_toggle_admin_reset", true));
        }
        if (player.isOp()) {
            player.sendMessage(plugin.getMessageManager().getMessage("usage_addlevel_help", true));
            player.sendMessage(plugin.getMessageManager().getMessage("usage_addcraft_help", true));
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
                // Console-only diagnostic (intentionally not a language key)
                plugin.getLogger().warning("Invalid sound in config: " + soundName
                        + " (path: " + path + "). Using the default sound.");
            }
        }

        player.playSound(player, soundToPlay, volume, pitch);
    }
}