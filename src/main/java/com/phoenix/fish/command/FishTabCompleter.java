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
        // v1.8.5 — /pf settings is admin-only and works from console too.
        // Don't restrict to Player sender — allow console tab-completion.
        if (args.length == 1) {
            return filterByPrefix(getAvailableSubcommands(sender), args[0]);
        }

        if (args.length == 2) {
            // Special-case /pf settings — its 2nd-arg suggestions depend on
            // args[1] (which subcommand the admin is typing).
            if ("settings".equalsIgnoreCase(args[0])) {
                return filterByPrefix(getSettingsSubcommands(sender), args[1]);
            }
            return filterByPrefix(getSecondArgSuggestions(sender, args[0]), args[1]);
        }

        if (args.length >= 3) {
            // v1.8.5 — /pf settings <sub> <args...> — multi-arg completion.
            if ("settings".equalsIgnoreCase(args[0])) {
                return getSettingsArgSuggestions(sender, args);
            }
            if (args.length == 3) {
                return getThirdArgSuggestions(sender, args);
            }
        }

        return Collections.emptyList();
    }

    // =====================================================================
    //  /pf settings — subcommand + arg completion (v1.8.5)
    // =====================================================================

    private List<String> getSettingsSubcommands(CommandSender sender) {
        // v1.8.5 — Same permission check as SettingsCommandHandler.handle().
        if (!sender.hasPermission("phoenixfish.settings") && !sender.hasPermission("phoenixfish.admin")) {
            return Collections.emptyList();
        }
        List<String> subs = new ArrayList<>(List.of("language", "features", "list"));
        Collections.sort(subs);
        return subs;
    }

    private List<String> getSettingsArgSuggestions(CommandSender sender, String[] args) {
        if (!sender.hasPermission("phoenixfish.settings") && !sender.hasPermission("phoenixfish.admin")) {
            return Collections.emptyList();
        }

        String sub = args[1].toLowerCase();
        String prefix = args.length > 2 ? args[args.length - 1] : "";

        switch (sub) {
            case "language":
                // /pf settings language <lang_code>
                if (args.length == 3) {
                    return filterByPrefix(
                            plugin.getContentLanguageManager().listAvailableLanguages(),
                            prefix);
                }
                return Collections.emptyList();

            case "features":
            case "feature":
                return getFeaturesArgSuggestions(args, prefix);

            case "list":
            default:
                return Collections.emptyList();
        }
    }

    /**
     * v1.8.7 — Tab-completion for /pf settings features <args...>.
     *
     * <p>Handles deep nested paths with dynamic substring matching:</p>
     * <ul>
     *   <li>/pf settings features &lt;TAB&gt; → all feature names + "skills"</li>
     *   <li>/pf settings features skills &lt;TAB&gt; → true, false</li>
     *   <li>/pf settings features skills.types.&lt;TAB&gt; → all skill config IDs (dynamically fetched from SkillManager)</li>
     *   <li>/pf settings features skills.types.fa&lt;TAB&gt; → fast (substring match)</li>
     *   <li>/pf settings features skills.types.cast&lt;TAB&gt; → big-catch, lucky-cast (substring match)</li>
     *   <li>/pf settings features skills.types.&lt;id&gt; &lt;TAB&gt; → true, false</li>
     * </ul>
     *
     * <p><b>Key fixes from v1.8.6:</b></p>
     * <ul>
     *   <li>Fixed unreachable code: the v1.8.6 {@code args.length == 3} block
     *       returned before reaching the {@code skills.types.} expansion.</li>
     *   <li>Substring matching: typing "cast" now matches both "big-catch" and
     *       "lucky-cast", not just IDs that start with "cast".</li>
     *   <li>Dynamic source: skill IDs are fetched from
     *       {@link com.phoenix.fish.manager.SkillManager#getAllSkillConfigIds()}
     *       which derives from the {@code SKILL_TO_CONFIG_ID} map — future
     *       skill additions auto-populate without touching this file.</li>
     * </ul>
     */
    private List<String> getFeaturesArgSuggestions(String[] args, String prefix) {
        // ── args.length == 3: user is typing the feature name (args[2]) ──
        if (args.length == 3) {
            String typed = args[2].toLowerCase();

            // v1.8.7 — Deep nested path: skills.types.<id>
            // If the user has typed "skills.types." (or is partway through a
            // skill ID after the dot), dynamically expand the registered skill
            // config IDs with SUBSTRING matching.
            if (typed.startsWith("skills.types.")) {
                String skillQuery = typed.substring("skills.types.".length());
                // Dynamic: fetched from SkillManager.getAllSkillConfigIds()
                // which derives from the SKILL_TO_CONFIG_ID map.
                List<String> skillConfigIds =
                        com.phoenix.fish.manager.SkillManager.getAllSkillConfigIds();
                List<String> matches = new ArrayList<>();
                for (String id : skillConfigIds) {
                    // Substring matching: "cast" matches both "big-catch" and "lucky-cast"
                    if (id.toLowerCase().contains(skillQuery.toLowerCase())) {
                        matches.add("skills.types." + id);
                    }
                }
                Collections.sort(matches);
                return matches;
            }

            // Standard feature name completion (including "skills" master toggle)
            List<String> options = new ArrayList<>(getFeatureNames());
            if (!options.contains("skills")) {
                options.add("skills");
            }
            Collections.sort(options);
            return filterByPrefix(options, typed);
        }

        // ── args.length == 4: user is typing the boolean value (args[3]) ──
        // /pf settings features <name> <true|false>
        // /pf settings features skills <true|false>
        // /pf settings features skills.types.<id> <true|false>
        if (args.length == 4) {
            return filterByPrefix(List.of("true", "false"), prefix);
        }

        return Collections.emptyList();
    }

    /**
     * v1.8.5 — Return all feature names under {@code features.*} in config.yml.
     * Each feature has an {@code enabled:} boolean. Also includes a few common
     * aliases (e.g. "daily-contracts" → "contracts").
     */
    private List<String> getFeatureNames() {
        List<String> result = new ArrayList<>();
        var featuresSec = plugin.getConfig().getConfigurationSection("features");
        if (featuresSec != null) {
            for (String key : featuresSec.getKeys(false)) {
                var child = featuresSec.getConfigurationSection(key);
                if (child != null && child.contains("enabled")) {
                    result.add(key);
                }
            }
        }
        // Common aliases
        result.add("daily-contracts");
        Collections.sort(result);
        return result;
    }

    // =====================================================================
    //  Original subcommands (unchanged)
    // =====================================================================

    private List<String> getAvailableSubcommands(CommandSender sender) {
        List<String> subs = new ArrayList<>(List.of(
                "catalog", "fix", "sell", "skills", "toggle"
        ));

        boolean isAdmin = sender.hasPermission("phoenixfish.admin");
        boolean isTournamentAdmin = sender.hasPermission("phoenixfish.tournament.admin");

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
            subs.add("debug");
            // v1.8.5 — /pf settings
            subs.add("settings");
        }
        subs.add("respec");
        subs.add("achievements");
        // v1.6.0 — contracts / season (only if sender has any chance of using them)
        subs.add("contracts");
        subs.add("season");

        Collections.sort(subs);
        return subs;
    }

    private List<String> getSecondArgSuggestions(CommandSender sender, String sub) {
        // sender may be console — wrap player-only calls
        boolean isPlayer = sender instanceof Player;
        switch (sub.toLowerCase()) {
            case "sell":
                return List.of("all", "hand", "menu");

            case "tournament":
                if (sender.hasPermission("phoenixfish.tournament.admin")
                        || sender.hasPermission("phoenixfish.admin")) {
                    return List.of("addreward", "start", "stop");
                }
                return Collections.emptyList();

            case "toggle":
                if (!isPlayer) return Collections.emptyList();
                Player player = (Player) sender;
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

            case "catalog":
                return List.of("search");

            case "debug":
                if (sender.hasPermission("phoenixfish.admin")) {
                    return List.of("player", "rod", "self");
                }
                return Collections.emptyList();

            case "respec":
                if (sender.hasPermission("phoenixfish.admin")) {
                    List<String> names = getOnlinePlayerNames();
                    Collections.sort(names);
                    return names;
                }
                return Collections.emptyList();

            case "reload":
            case "fix":
            case "fixall":
            case "skills":
            case "achievements":
            case "contracts":
            case "season":
            default:
                return Collections.emptyList();
        }
    }

    private List<String> getThirdArgSuggestions(CommandSender sender, String[] args) {
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
                if (sender instanceof Player player
                        && (arg2.equals("status") || arg2.equals("reset"))
                        && player.hasPermission("phoenixfish.admin")) {
                    return filterByPrefix(getOnlinePlayerNames(), prefix);
                }
                return Collections.emptyList();

            case "catalog":
                if (arg2.equals("search")) {
                    return filterByPrefix(getFishSearchTerms(), prefix);
                }
                return Collections.emptyList();

            case "debug":
                if (arg2.equals("player") && sender.hasPermission("phoenixfish.admin")) {
                    return filterByPrefix(getOnlinePlayerNames(), prefix);
                }
                return Collections.emptyList();

            default:
                return Collections.emptyList();
        }
    }

    private List<String> getFishSearchTerms() {
        if (plugin.getFishManager() == null) {
            return Collections.emptyList();
        }
        List<String> terms = new ArrayList<>();
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        for (var fish : plugin.getFishManager().getAllFish()) {
            terms.add(fish.id());
            terms.add(plain.serialize(fish.name()));
        }
        return terms;
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
