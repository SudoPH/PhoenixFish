package com.phoenix.fish.command;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.manager.ItemFixer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class FishTabCompleter implements TabCompleter {

    private final PhoenixFish plugin;

    public FishTabCompleter(PhoenixFish plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterByPrefix(getAvailableSubcommands(player), args[0]);
        }

        if (args.length == 2) {
            return filterByPrefix(getSecondArgSuggestions(player, args[0]), args[1]);
        }

        if (args.length == 3) {
            return getThirdArgSuggestions(player, args);
        }

        return Collections.emptyList();
    }

    private List<String> getAvailableSubcommands(Player player) {
        List<String> subs = new ArrayList<>(List.of(
                "catalog", "fix", "sell", "skills", "toggle"
        ));

        boolean isAdmin = player.hasPermission("phoenixfish.admin");
        boolean isTournamentAdmin = player.hasPermission("phoenixfish.tournament.admin");

        if (isTournamentAdmin || isAdmin) {
            subs.add("tournament");
        }
        if (isAdmin) {
            subs.add("addcraft");
            subs.add("addlevel");
            subs.add("fixall");
            subs.add("givebait");
            subs.add("giverod");
            subs.add("reload");
        }

        Collections.sort(subs);
        return subs;
    }

    private List<String> getSecondArgSuggestions(Player player, String sub) {
        switch (sub.toLowerCase()) {
            case "sell":
                return List.of("all", "hand", "menu");

            case "tournament":
                if (player.hasPermission("phoenixfish.tournament.admin")
                        || player.hasPermission("phoenixfish.admin")) {
                    return List.of("addreward", "start", "stop");
                }
                return Collections.emptyList();

            case "toggle":
                List<String> toggleSubs = new ArrayList<>();
                toggleSubs.add("status");
                if (player.hasPermission("phoenixfish.admin")) {
                    toggleSubs.add("reset");
                    for (Player p : plugin.getServer().getOnlinePlayers()) {
                        toggleSubs.add(p.getName());
                    }
                }
                Collections.sort(toggleSubs);
                return toggleSubs;

            case "giverod":
                return getRodIds();

            case "givebait":
                return getBaitIds();

            case "addlevel":
            case "addcraft":
                return List.of("1", "5", "10", "50");

            case "reload":
            case "fix":
            case "fixall":
            case "catalog":
            case "skills":
            default:
                return Collections.emptyList();
        }
    }

    private List<String> getThirdArgSuggestions(Player player, String[] args) {
        String sub = args[0].toLowerCase();
        String arg2 = args[1].toLowerCase();
        String prefix = args.length > 2 ? args[2] : "";

        switch (sub) {
            case "tournament":
                switch (arg2) {
                    case "start":
                        return filterByPrefix(List.of("5", "10", "15", "30", "60"), prefix);
                    case "addreward":
                        return filterByPrefix(List.of("1", "2", "3"), prefix);
                    default:
                        return Collections.emptyList();
                }

            case "toggle":
                if ((arg2.equals("status") || arg2.equals("reset"))
                        && player.hasPermission("phoenixfish.admin")) {
                    return filterByPrefix(getOnlinePlayerNames(), prefix);
                }
                return Collections.emptyList();

            default:
                return Collections.emptyList();
        }
    }

    private List<String> getRodIds() {
        if (plugin.getFishingListener() == null) {
            return Collections.emptyList();
        }
        ItemFixer fixer = plugin.getFishingListener().getItemFixer();
        if (fixer == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(fixer.getRodConfigs().keySet());
    }

    private List<String> getBaitIds() {
        if (plugin.getBaitManager() == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(plugin.getBaitManager().getBaits().keySet());
    }

    private List<String> getOnlinePlayerNames() {
        return plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
    }

    private List<String> filterByPrefix(List<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return new ArrayList<>(options);
        }
        String lower = prefix.toLowerCase();
        return options.stream()
                .filter(s -> s != null && s.toLowerCase().startsWith(lower))
                .collect(Collectors.toList());
    }
}