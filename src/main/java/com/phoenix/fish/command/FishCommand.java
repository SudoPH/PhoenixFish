package com.phoenix.fish.command;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.manager.FishingModeManager;
import com.phoenix.fish.manager.ItemFixer;
import com.phoenix.fish.manager.ItemUtils;
import com.phoenix.fish.manager.MessageManager;
import com.phoenix.fish.manager.TournamentManager;
import com.phoenix.fish.task.ContainerFixTask;
import com.phoenix.fish.util.SoundUtils;
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

    private static final int MAX_LEVEL_DELTA = 1000;
    private static final int MAX_LEVEL_CAP = 1_000_000;

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
            case "givebait" -> {
                if (args.length < 2) {
                    player.sendMessage(plugin.getMessageManager().getMessage("usage_givebait", true));
                    return true;
                }
                handleGiveBait(player, args[1]);
            }
            case "catalog" -> {
                // v1.5.0 (F-3) — /pf catalog search <query> → balık ara
                // /pf catalog (alone) → ana menü
                if (args.length >= 2 && "search".equalsIgnoreCase(args[1])) {
                    handleCatalogSearch(player, args);
                } else {
                    plugin.getCatalogManager().openMainMenu(player);
                }
            }
            case "skills" -> plugin.getSkillManager().openMenu(player);
            case "tournament" -> handleTournament(player, args);
            case "sell" -> handleSell(player, args);
            case "toggle" -> handleToggle(player, args);
            case "addlevel" -> handleAddLevel(player, args, false);
            case "addcraft" -> handleAddLevel(player, args, true);
            case "reload" -> handleReload(player);
            // v1.5.0 (F-7) — Admin debug tools
            case "debug" -> handleDebug(player, args);
            // v1.5.0 (F-4) — Skill re-spec (per-player)
            case "respec" -> handleRespec(player, args);
            default -> sendHelp(player);
        }
        return true;
    }

    /**
     * v1.5.0 (F-7) — Admin debug komutu.
     * Kullanım:
     *   /pf debug player <name>  — oyuncunun XP, level, skill puanı, balance, discovery sayısını göster
     *   /pf debug rod            — eldeki oltanın resolved config'ini göster (material, cmd, luck, PDC)
     *   /pf debug self           — kendi FishingData özetini göster
     */
    private void handleDebug(Player sender, String[] args) {
        if (!sender.hasPermission("phoenixfish.admin")) {
            sender.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("<yellow>Usage: <white>/pf debug player <name> | /pf debug rod | /pf debug self");
            return;
        }

        String sub = args[1].toLowerCase();

        if ("self".equals(sub)) {
            // Kendi FishingData özetini göster
            com.phoenix.fish.data.FishingData data = plugin.getCacheManager().getData(sender.getUniqueId());
            if (data == null) {
                sender.sendMessage("<red>Your FishingData is not loaded yet.");
                return;
            }
            sendDebugSummary(sender, sender.getName(), data);
            return;
        }

        if ("player".equals(sub)) {
            if (args.length < 3) {
                sender.sendMessage("<yellow>Usage: <white>/pf debug player <name>");
                return;
            }
            Player target = org.bukkit.Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage("<red>Player not found: <yellow>" + args[2]);
                return;
            }
            com.phoenix.fish.data.FishingData data = plugin.getCacheManager().getData(target.getUniqueId());
            if (data == null) {
                sender.sendMessage("<red>FishingData not loaded for <yellow>" + target.getName());
                return;
            }
            sendDebugSummary(sender, target.getName(), data);
            return;
        }

        if ("rod".equals(sub)) {
            // Elindeki oltanın resolved config'ini göster
            org.bukkit.inventory.ItemStack rod = sender.getInventory().getItemInMainHand();
            if (rod == null || rod.getType().isAir()) {
                sender.sendMessage("<red>Your main hand is empty.");
                return;
            }
            sender.sendMessage("<gold>=== Rod Debug ===");
            sender.sendMessage("<gray>Material: <white>" + rod.getType());
            int cmd = com.phoenix.fish.manager.ItemUtils.getCMD(rod);
            sender.sendMessage("<gray>CustomModelData: <white>" + cmd);
            if (rod.hasItemMeta() && rod.getItemMeta().hasDisplayName()) {
                sender.sendMessage("<gray>Display name: <white>"
                        + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                .serialize(rod.getItemMeta().displayName()));
            }
            // Luck PDC oku
            org.bukkit.NamespacedKey luckKey = new org.bukkit.NamespacedKey(plugin, "luck_multiplier");
            if (rod.hasItemMeta()) {
                Double luck = rod.getItemMeta().getPersistentDataContainer()
                        .get(luckKey, org.bukkit.persistence.PersistentDataType.DOUBLE);
                sender.sendMessage("<gray>Luck PDC: <white>"
                        + (luck != null ? String.format("%.2f", luck) : "<red>(none)"));
            }
            // ItemFixer'dan rod config resolve et
            if (plugin.getFishingListener() != null && plugin.getFishingListener().getItemFixer() != null) {
                org.bukkit.configuration.ConfigurationSection rodSec =
                        plugin.getFishingListener().getItemFixer().findRodConfig(rod.getType(), cmd);
                if (rodSec != null) {
                    sender.sendMessage("<gray>Matched rod in rods.yml: <green>YES");
                    sender.sendMessage("<gray>  luck-multiplier: <white>" + rodSec.getDouble("luck-multiplier", 1.0));
                    sender.sendMessage("<gray>  fishing-level-requirement: <white>"
                            + rodSec.getInt("fishing-level-requirement", 1));
                    sender.sendMessage("<gray>  has minigame block: <white>"
                            + (rodSec.contains("minigame") ? "<green>YES" : "<red>NO"));
                } else {
                    sender.sendMessage("<gray>Matched rod in rods.yml: <red>NO (not a custom rod)");
                }
            }
            return;
        }

        sender.sendMessage("<yellow>Usage: <white>/pf debug player <name> | /pf debug rod | /pf debug self");
    }

    private void sendDebugSummary(Player sender, String targetName, com.phoenix.fish.data.FishingData data) {
        sender.sendMessage("<gold>=== Debug: <white>" + targetName + "</white> ===");
        sender.sendMessage("<gray>Fishing level: <white>" + data.getCurrentLevel()
                + " <dark_gray>(XP: <white>" + data.getCurrentXp() + "<dark_gray>)");
        sender.sendMessage("<gray>Crafting level: <white>" + data.getCraftingLevel()
                + " <dark_gray>(XP: <white>" + data.getCraftingXp() + "<dark_gray>)");
        sender.sendMessage("<gray>Balance: <green>$" + String.format("%.2f", data.getBalance()));
        sender.sendMessage("<gray>Skill points: <yellow>" + data.getSkillPoints());
        sender.sendMessage("<gray>Fast Catcher: <white>" + data.getFastCatcher() + "/"
                + data.getMaxSkillLevel());
        sender.sendMessage("<gray>Master Hunter: <white>" + data.getMasterHunter() + "/"
                + data.getMaxSkillLevel());
        sender.sendMessage("<gray>Double Catch: <white>" + data.getDoubleCatch() + "/"
                + data.getMaxSkillLevel());
        sender.sendMessage("<gray>Lucky Bait: <white>" + data.getLuckyBait() + "/"
                + data.getMaxSkillLevel());
        sender.sendMessage("<gray>Discovered fish: <white>" + data.getDiscoveredFish().size());
    }

    /**
     * v1.5.0 (F-4) — Skill re-spec komutu.
     * Kullanım:
     *   /pf respec             — kendi skill'lerini sıfırla, puanları geri al (ücretsiz, default)
     *   /pf respec <player>    — başkasının skill'lerini sıfırla (admin yetkisi gerek)
     *
     * Opsiyonel: config'de xp-system.respec-cost belirlenebilir (PhoenixFish bakiyesinden düşülür).
     */
    private void handleRespec(Player sender, String[] args) {
        Player target = sender;
        if (args.length >= 2) {
            // Admin tarafından başkası için respec
            if (!sender.hasPermission("phoenixfish.admin")) {
                sender.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
                return;
            }
            target = org.bukkit.Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("<red>Player not found: <yellow>" + args[1]);
                return;
            }
        }

        com.phoenix.fish.data.FishingData data = plugin.getCacheManager().getData(target.getUniqueId());
        if (data == null) {
            sender.sendMessage("<red>FishingData not loaded for <yellow>" + target.getName());
            return;
        }

        // Opsiyonel ücret (PhoenixFish bakiyesinden)
        double respecCost = plugin.getConfig().getDouble("xp-system.respec-cost", 0.0);
        if (respecCost > 0 && !sender.hasPermission("phoenixfish.admin")) {
            // Sadece non-admin için ücret kontrol et
            double balance = data.getBalance();
            if (balance < respecCost) {
                sender.sendMessage("<red>Re-spec costs <yellow>$" + String.format("%.2f", respecCost)
                        + "<red> but you only have <yellow>$" + String.format("%.2f", balance));
                return;
            }
            data.withdrawBalance(respecCost);
        }

        int refunded = data.respecAllSkills();
        sender.sendMessage("<green>✔ Re-spec complete for <white>" + target.getName()
                + "<green>. <white>" + refunded + "<green> skill points refunded.");
        if (respecCost > 0) {
            sender.sendMessage("<gray>Cost: <red>-$" + String.format("%.2f", respecCost));
        }
    }

    /**
     * v1.5.0 (F-3) — Basit katalog arama.
     * Kullanım: /pf catalog search <query>
     * Eşleşen balıkları chat'te listeler — case-insensitive, partial match.
     */
    private void handleCatalogSearch(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("<yellow>Usage: <white>/pf catalog search <query>");
            return;
        }
        // args[2...] birleşerek query
        StringBuilder query = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (i > 2) query.append(" ");
            query.append(args[i]);
        }
        String q = query.toString().toLowerCase().trim();
        if (q.length() < 2) {
            player.sendMessage("<red>Query too short (min 2 chars).");
            return;
        }

        // Tüm balıkları tara, eşleşenleri topla
        java.util.List<com.phoenix.fish.model.CustomFish> matches = new java.util.ArrayList<>();
        for (com.phoenix.fish.model.CustomFish fish : plugin.getFishManager().getAllFish()) {
            String fishId = fish.id().toLowerCase();
            String fishName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(fish.name()).toLowerCase();
            if (fishId.contains(q) || fishName.contains(q)) {
                matches.add(fish);
            }
        }

        if (matches.isEmpty()) {
            player.sendMessage("<gray>No fish found for '<white>" + q + "<gray>'.");
            return;
        }

        // Listeyi chat'te göster
        player.sendMessage("<gold>=== Fish search: '<white>" + q + "<gold>' (" + matches.size() + " results) ===");
        for (com.phoenix.fish.model.CustomFish fish : matches) {
            String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(fish.name());
            player.sendMessage("<aqua>" + fish.id() + " <dark_gray>» <white>" + name
                    + " <dark_gray>(rarity " + fish.rarity() + ")");
        }
    }

    private void handleSell(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getMessageManager().getMessage("usage_sell", true));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "hand" -> plugin.getEconomyManager().sellHand(player);
            case "all" -> plugin.getEconomyManager().sellAll(player);
            case "menu", "gui" -> plugin.getEconomyManager().openSellGUI(player);
            default -> player.sendMessage(plugin.getMessageManager().getMessage("usage_sell", true));
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
                    } catch (NumberFormatException e) {
                        player.sendMessage(plugin.getMessageManager().getMessage("tournament_invalid_duration", true));
                        return;
                    }
                    if (minutes <= 0 || minutes > 1440) {
                        player.sendMessage(plugin.getMessageManager().getMessage("tournament_invalid_duration", true));
                        return;
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
            if (rank <= 0 || rank > 100) {
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
        if (!player.hasPermission("phoenixfish.use")) {
            player.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
            return;
        }

        ItemFixer fixer = plugin.getFishingListener().getItemFixer();
        int[] result = fixer.fixInventory(player.getInventory());

        Map<String, String> fixPh = Map.of(
                "%rods%", String.valueOf(result[0]),
                "%baits%", String.valueOf(result[1]));
        player.sendMessage(plugin.getMessageManager().getMessage("fix_inventory", true, fixPh));

        SoundUtils.playFromConfig(plugin, player, "sounds.fix",
                Sound.BLOCK_ANVIL_USE, 0.5f, 1.2f);
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
                    try {
                        if (!online.isOnline()) {
                            index++;
                            processed++;
                            continue;
                        }
                        int[] mainResult = fixer.fixInventory(online.getInventory());
                        int[] enderResult = fixer.fixInventory(online.getEnderChest());
                        totalRods += mainResult[0] + enderResult[0];
                        totalBaits += mainResult[1] + enderResult[1];
                        playerCount++;
                    } catch (Exception ex) {
                        plugin.getLogger().warning("fixall: failed to fix inventory of "
                                + online.getName() + ": " + ex.getMessage());
                    }
                    index++;
                    processed++;
                }

                if (index >= players.size()) {
                    try {
                        cancel();
                        Map<String, String> onlinePh = Map.of(
                                "%players%", String.valueOf(playerCount),
                                "%rods%", String.valueOf(totalRods),
                                "%baits%", String.valueOf(totalBaits));
                        player.sendMessage(plugin.getMessageManager()
                                .getMessage("fixall_done_online", true, onlinePh));

                        task.runTaskTimer(plugin, 0L, 1L);
                    } catch (Exception ex) {
                        plugin.getLogger().severe("fixall: failed to start ContainerFixTask: "
                                + ex.getMessage());
                        player.sendMessage(plugin.getMessageManager()
                                .getMessage("fixall_container_error", true));
                    }
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

        ItemStack copy = rod.clone();
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(copy);
        if (!overflow.isEmpty()) {
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover.clone());
            }
        }

        player.sendMessage(plugin.getMessageManager().getMessage("rod_given", true));
    }

    private void handleGiveBait(Player player, String baitId) {
        if (!player.hasPermission("phoenixfish.admin")) {
            player.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
            return;
        }

        baitId = baitId.toLowerCase();
        ItemStack bait = plugin.getBaitManager().getBaitItem(baitId);
        if (bait == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("bait_not_found", true));
            return;
        }

        ItemStack copy = bait.clone();
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(copy);
        if (!overflow.isEmpty()) {
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover.clone());
            }
        }

        Map<String, String> ph = Map.of("%bait_id%", baitId);
        player.sendMessage(plugin.getMessageManager().getMessage("bait_given", true, ph));
    }

    private void handleAddLevel(Player player, String[] args, boolean isCrafting) {
        if (!player.hasPermission("phoenixfish.admin")) {
            player.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(plugin.getMessageManager().getMessage("usage_addlevel", true));
            return;
        }

        if (!plugin.isXpSystemEnabled() || plugin.getCacheManager() == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("addlevel_xp_disabled", true));
            return;
        }

        int levelsToAdd;
        try {
            levelsToAdd = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getMessageManager().getMessage("addlevel_invalid_number", true));
            return;
        }

        if (levelsToAdd == 0 || Math.abs(levelsToAdd) > MAX_LEVEL_DELTA) {
            player.sendMessage(plugin.getMessageManager().getMessage("addlevel_out_of_range", true));
            return;
        }

        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("addlevel_data_error", true));
            return;
        }

        if (isCrafting) {
            long projected = (long) data.getCraftingLevel() + levelsToAdd;
            if (projected < 1) projected = 1;
            if (projected > MAX_LEVEL_CAP) projected = MAX_LEVEL_CAP;
            int newLevel = (int) projected;
            data.setCraftingLevel(newLevel);
            // v1.4.0 — /pf addcraft hiçbir zaman crafting skill puanı vermez
            // (fishing skill puanı ayrı sistem). Bu bilinçli bir karar.
            player.sendMessage(plugin.getMessageManager().getMessage("addlevel_success_crafting", true,
                    Map.of("%amount%", String.valueOf(levelsToAdd),
                            "%level%", String.valueOf(newLevel))));
        } else {
            long projected = (long) data.getCurrentLevel() + levelsToAdd;
            if (projected < 1) projected = 1;
            if (projected > MAX_LEVEL_CAP) projected = MAX_LEVEL_CAP;
            int newLevel = (int) projected;
            data.setCurrentLevel(newLevel);
            // v1.4.0 — /pf addlevel normal levelUp yolunu bypass ettiği için
            // skillPoints artmıyordu. Şimdi sadece POZİTİF level eklemelerinde
            // skill puanı da veriyoruz. Negatif (level düşürme) puan düşürmez.
            if (levelsToAdd > 0) {
                int currentSkillPoints = data.getSkillPoints();
                data.setSkillPoints(currentSkillPoints + levelsToAdd);
            }
            player.sendMessage(plugin.getMessageManager().getMessage("addlevel_success_fishing", true,
                    Map.of("%amount%", String.valueOf(levelsToAdd),
                            "%level%", String.valueOf(newLevel))));
        }
    }

    private void handleToggle(Player player, String[] args) {
        FishingModeManager modeManager = plugin.getFishingModeManager();

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

                boolean vanilla = modeManager.isVanillaMode(target);
                String modeName = plugin.getMessageManager()
                        .getPlainMessage(vanilla ? "mode_vanilla" : "mode_custom");

                player.sendMessage(plugin.getMessageManager().getMessage("fishing_mode_reset", true,
                        Map.of("%player%", target.getName(), "%mode%", modeName)));
                sendModeInfo(target, vanilla);

                SoundUtils.playFromConfig(plugin, target, "sounds.mode_toggle",
                        Sound.UI_BUTTON_CLICK, 0.6f, 1.5f);
            }
            default -> {
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

        sendModeInfo(target, vanilla);

        if (!self) {
            actor.sendMessage(messages.getMessage("fishing_mode_changed_other", true,
                    Map.of("%player%", target.getName(), "%mode%", modeName)));
        }

        if (plugin.getConfig().getBoolean("fishing-mode.broadcast-change", false)) {
            Bukkit.broadcast(messages.getMessage("fishing_mode_broadcast", true,
                    Map.of("%player%", target.getName(), "%mode%", modeName)));
        }

        SoundUtils.playFromConfig(plugin, target, "sounds.mode_toggle",
                Sound.UI_BUTTON_CLICK, 0.6f, 1.5f);
    }

    private void sendModeInfo(Player target, boolean vanilla) {
        String infoKey = vanilla ? "fishing_mode_vanilla_enabled" : "fishing_mode_custom_enabled";
        for (Component line : plugin.getMessageManager().getMessageList(infoKey, null)) {
            target.sendMessage(line);
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage(plugin.getMessageManager().getMessage("usage_giverod", true));
        player.sendMessage(plugin.getMessageManager().getMessage("usage_givebait", true));
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
            player.sendMessage(plugin.getMessageManager().getMessage("usage_addlevel_help", true));
            player.sendMessage(plugin.getMessageManager().getMessage("usage_addcraft_help", true));
            player.sendMessage(plugin.getMessageManager().getMessage("usage_reload", true));
        }
    }

    private void handleReload(Player player) {
        if (!player.hasPermission("phoenixfish.admin")) {
            player.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
            return;
        }

        player.sendMessage(plugin.getMessageManager().getMessage("reload_started", true));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.reloadAll();
                player.sendMessage(plugin.getMessageManager().getMessage("plugin_reloaded", true));
            } catch (Exception ex) {
                plugin.getLogger().severe("Reload failed: " + ex.getMessage());
                player.sendMessage(plugin.getMessageManager().getMessage("reload_failed", true));
            }
        });
    }
}