<div align="center">

<img src="https://raw.githubusercontent.com/SudoPH/Icons/main/fish.png" alt="PhoenixFish" width="110" />

# PhoenixFish

**Build Beyond Vanilla Fishing.**

A highly customizable, performance-focused fishing plugin for **Paper** and **Purpur** servers.

<br>

[![Latest release](https://img.shields.io/github/v/release/SudoPH/PhoenixFish?style=for-the-badge&color=38BDF8&label=Latest)](https://github.com/SudoPH/PhoenixFish/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/SudoPH/PhoenixFish/total?style=for-the-badge&color=22C55E&label=Downloads)](https://github.com/SudoPH/PhoenixFish/releases)
[![License](https://img.shields.io/badge/License-GPLv3-F97316?style=for-the-badge)](LICENSE)

![Minecraft](https://img.shields.io/badge/Minecraft-1.21%20--%2026.1.3-62B47A?style=flat-square)
![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?style=flat-square)
![Platform](https://img.shields.io/badge/Paper%20%7C%20Purpur-supported-0EA5E9?style=flat-square)
![Status](https://img.shields.io/badge/Status-In%20Development-EAB308?style=flat-square)

<br>

[**Download**](https://github.com/SudoPH/PhoenixFish/releases/latest) ·
[**Features**](#-features) ·
[**Installation**](#-installation) ·
[**Commands**](#-commands--permissions) ·
[**Report a bug**](https://github.com/SudoPH/PhoenixFish/issues)

<br>

[![PhoenixFish Demo](https://img.youtube.com/vi/JEfE1KNi5do/maxresdefault.jpg)](https://www.youtube.com/watch?v=JEfE1KNi5do)

</div>

<br>

## ✨ Overview

PhoenixFish completely overhauls the vanilla fishing experience with an **interactive fishing minigame**, custom fish, rods, bait, rarities and mutations, XP progression, a skill tree, seasonal and environmental fishing, fish weights, personal and server records, a built-in economy with a dynamic market, daily contracts, fishing seasons, tournaments, achievements and extensive configuration options.

> [!TIP]
> Not everyone wants custom fishing all the time. Every player can **switch between PhoenixFish and vanilla fishing** with a single command (`/pf toggle`) and still catch vanilla treasure and enchanted books.

> [!NOTE]
> This README describes the current state of the project. Some features may be newer than the latest release, so check the [Releases](https://github.com/SudoPH/PhoenixFish/releases) page for what each version includes.

<br>

## 🎯 Highlights

| | | |
|:--|:--|:--|
| 🎣 **Action Bar minigame**<br><sub>Track the fish, manage line tension</sub> | 🐟 **Custom fish, rods & bait**<br><sub>Unlimited, fully configurable</sub> | 🧬 **Mutations**<br><sub>Shiny, Albino and Golden variants</sub> |
| 🌦️ **Environmental fishing**<br><sub>Weather, biome, season, time, depth</sub> | 📈 **Levels & skill tree**<br><sub>7 skills with respec</sub> | ⚖️ **Weights & records**<br><sub>Personal bests and a server record</sub> |
| 💰 **Built-in economy**<br><sub>Weight-based prices, dynamic market</sub> | 📜 **Contracts & seasons**<br><sub>Daily goals and competitive seasons</sub> | 🏆 **Tournaments**<br><sub>Timed events with rewards</sub> |
| 📖 **Fishing catalog**<br><sub>Discover, search and sell</sub> | 🏅 **Achievements**<br><sub>8 tiered achievements</sub> | 🔀 **Vanilla toggle**<br><sub>Per-player, saved across restarts</sub> |

<br>

## ⚡ Quick Start

1. **Download** `PhoenixFish.jar` from the [latest release](https://github.com/SudoPH/PhoenixFish/releases/latest).
2. **Drop it** into your server's `plugins/` folder and start the server.
3. **Get a rod** with `/pf giverod <rod>` and **bait** with `/pf givebait <bait>`, put the bait in your off-hand and **right-click water** to cast.

Full details are in [Installation](#-installation) and [Getting Started](#-getting-started).

<br>

## 🚀 Features

### 🎣 Fishing

<details open>
<summary><b>Interactive Fishing Minigame</b></summary>

<br>

PhoenixFish replaces the vanilla fishing experience with an interactive Action Bar minigame. Players need to:

- Track fish movement
- Control the fishing bar
- Manage line tension
- Keep the bar aligned with the fish
- Successfully secure their catch

> [!WARNING]
> If the line tension reaches 100%, the line breaks and the fish escapes.

**Difficulty presets.** Switch the difficulty of the minigame with one line instead of editing several values:

```yaml
features:
  minigame-presets:
    enabled: true
    difficulty: "medium"   # easy | medium | hard | hardcore | insane
```

**Visual effects.** Every rarity tier (1 to 5) can have its own particle burst, sound, screen shake and title announcement, so a Legendary catch feels like one:

```yaml
features:
  minigame-effects:
    enabled: true
    rarity_5:
      particle: "TOTEM_OF_UNDYING"
      particle_count: 30
      sound: "UI_TOAST_CHALLENGE_COMPLETE"
      shake: true
      title: true
      title_text: "<#9D00FF><bold>LEGENDARY CATCH!</bold></#9D00FF>"
```

</details>

<details>
<summary><b>🔀 Fishing Mode Toggle</b></summary>

<br>

Each player can choose how they fish. The choice is saved per player and survives relogs and server restarts.

```text
/phoenixfish toggle
```

| Mode | What the player gets |
|:--|:--|
| **Custom** (default) | The full PhoenixFish experience |
| **Vanilla** | Normal Minecraft fishing. PhoenixFish does not touch fishing at all |

In **vanilla mode**:

- Real vanilla loot, treasure and **enchanted books** drop as normal
- No minigame, no bait consumption
- No records, XP or tournament points
- No level requirement on PhoenixFish rods

Switching to vanilla mode while a minigame is running cancels it without awarding a fish.

> [!NOTE]
> Enchanted books are part of vanilla treasure loot. They drop at the normal vanilla rate, only appear in open water, and are more likely with the *Luck of the Sea* enchantment.

| Command | Description |
|:--|:--|
| `/phoenixfish toggle` | Switch your own fishing mode. |
| `/phoenixfish toggle status [player]` | Shows the current mode and where it comes from (`choice` / `default`). |
| `/phoenixfish toggle <player>` | **Admin:** toggle another player's mode. |
| `/phoenixfish toggle reset <player>` | **Admin:** reset a player to the default mode. |

```yaml
fishing-mode:
  enabled: true              # master switch for the toggle feature
  default-mode: custom       # custom | vanilla
  require-permission: false  # require phoenixfish.toggle
  toggle-cooldown: 5         # seconds between toggles
  broadcast-change: false    # announce mode changes server-wide
```

The toggle sound can be changed with `sounds.mode_toggle` (default: `UI_BUTTON_CLICK`).

</details>

<details>
<summary><b>🐟 Custom Fish & Rods</b></summary>

<br>

Create unlimited custom fish and fishing rods with configurable:

| | | |
|:--|:--|:--|
| Names | Materials | Min / max weight |
| Rarities | Catch chance (`weight`, decimals like `0.05`) | Fight strength |
| XP rewards | Lore | Custom Model Data |
| Luck multipliers | Biome, season, time and depth rules | |

</details>

<details>
<summary><b>🧬 Fish Mutations</b></summary>

<br>

Fish can mutate when they are caught. There are three variants: **Shiny ✦**, **Albino ✦** and **Golden ✦**. Each has its own color, lore prefix and price multiplier (up to 10×), and is stored safely in the item's PersistentDataContainer.

Catching mutated fish also counts toward the **Mutation Hunter** achievement.

</details>

<details>
<summary><b>🪱 Custom Bait</b></summary>

<br>

Create custom bait items that can target specific fish rarities. Bait can be configured to provide different effects and can interact with the **Lucky Bait** skill to reduce bait consumption.

Admins can give bait with `/phoenixfish givebait <bait-name>`.

</details>

<details>
<summary><b>⭐ Rarity System</b></summary>

<br>

PhoenixFish includes **5 configurable rarity tiers**, from **Common** to **Legendary**. Each rarity can have its own name, color, lore, sounds and visual effects. Legendary catches can trigger custom **Title** and **Subtitle** effects.

</details>

<details>
<summary><b>🌍 Environmental Fishing</b></summary>

<br>

Fishing can be affected by the environment, and fish can be limited to specific conditions.

**🌧️ Weather** provides configurable fishing bonuses:

| Weather | Effect |
|:--|:--|
| Rain | +20% catch luck |
| Thunderstorm | +50% catch luck and increased rare fish chances |

```yaml
rain-luck-bonus: 0.2
storm-luck-bonus: 0.5
```

**🌲 Biomes.** Fish can be restricted to specific Minecraft biomes through `fish.yml`:

```yaml
biomes:
  - SNOWY_PLAINS
  - ICE_SPIKES
```

- ❄️ Ice Fish → `SNOWY_PLAINS`, `ICE_SPIKES`
- 🌋 Lava Fish → Nether biomes such as `BASALT_DELTAS`

**🍂 Seasons, time of day and depth.** Fish can also be limited by the season, the time of day and the height at which the player is fishing. Each of these can be switched on or off separately (see [Modular Features](#-modular-features)).

```yaml
# Seasons when this fish can spawn: SPRING, SUMMER, AUTUMN, WINTER.
# Leave empty or remove to allow every season.
seasons: [WINTER]

# Time of day: DAY, NIGHT or ALL
time: NIGHT

# Optional override for servers with a fixed time: "auto", "always-day" or "always-night"
time-override: "always-night"

# Height range [min_y, max_y] the player must be in (inclusive)
y-range: [0, 30]
```

The season can follow real-world dates or the Minecraft world time, whichever you prefer. Fish without any restrictions can be caught anywhere, at any time.

</details>

### 📈 Progression

<details>
<summary><b>XP & Fishing Levels</b></summary>

<br>

Players gain XP by catching fish and can level up their fishing skill.

```text
Required XP = Current Level × 100
```

Each fishing level grants **+2% catch luck** and **1 Skill Point**.

| Level 1 | Level 2 | Level 3 | ... |
|:--:|:--:|:--:|:--:|
| 100 XP | 200 XP | 300 XP | ... |

</details>

<details>
<summary><b>🌳 Skill Tree</b></summary>

<br>

Spend your Skill Points with `/phoenixfish skills`.

| Skill | Effect |
|:--|:--|
| **Fast Catcher** | Increases minigame progress speed by 0.5% per level. |
| **Master Hunter** | Increases rare/epic fish chance by 2% per level. |
| **Double Catch** | Gives a 10% chance per level to catch 2 fish at once. |
| **Lucky Bait** | Gives a 20% chance per level to avoid consuming bait. |
| **Big Catch** | Gives +5% fish weight per level, so heavier and pricier fish. |
| **Lucky Cast** | Gives a +10% chance per level to upgrade a catch to the next rarity. |
| **Stamina** | Gives +10% minigame tension resistance per level, so the line snaps later. |

Each skill can be upgraded up to **Level 5** by default. The maximum can be changed (1 to 10) in `config.yml`:

```yaml
xp-system:
  skill-max-level: 5
```

**Respec:** `/phoenixfish respec` refunds all of your skill points for a configurable cost, so players can experiment without regret.

</details>

<details>
<summary><b>⚖️ Weight System</b></summary>

<br>

Every fish can have an individual randomized weight. Configure the limits in `fish.yml`:

```yaml
min-weight: 0.5
max-weight: 5.0
```

When a fish is caught, PhoenixFish generates a random decimal weight between these limits (for example `Carp → 3.42 kg`), adds it to the fish's lore and stores it on the item.

Weight directly affects the economic value:

```text
Sale Price = Base Price × Fish Weight
```

| 0.5 kg Carp | 5.0 kg Carp |
|:--:|:--:|
| $5 | $50 |

Larger fish are significantly more valuable.

</details>

<details>
<summary><b>🏆 Record System</b></summary>

<br>

Records are stored in `records.yml`.

**🥇 Personal Best (PB).** A player sets a PB when catching a species for the first time, or a heavier fish of a species they already caught. They receive an Action Bar notification and a level-up sound:

```text
Congratulations! New Personal Best: 4.12 kg!
```

**🌎 Server Record.** A single global record: the heaviest fish ever caught on the server, regardless of species. The record holder is stored together with the weight. A new record is announced to everyone:

```text
🔔 [Player] has set a new Server Record!
Fish: Legendary Shark
Weight: 125.8 kg!
```

The record-breaking player also hears the `UI_TOAST_CHALLENGE_COMPLETE` sound. Per-species records are still tracked, but they update silently and are not broadcast. Records written by older versions are migrated automatically on first load.

Record data persists across restarts and is written asynchronously to minimize performance impact.

> [!NOTE]
> Fish caught in **vanilla mode** never count toward records.

</details>

<details>
<summary><b>📖 Fishing Catalog</b></summary>

<br>

Open the interactive catalog with `/phoenixfish catalog`. It lets players:

- Browse available fish, custom bait and custom rods
- Track discovered fish, with undiscovered fish shown as `???`
- Search by fish name or ID with `/phoenixfish catalog search <query>`
- Navigate through multiple pages
- Sell fish directly through the catalog

Fish are revealed automatically after being caught for the first time.

</details>

<details>
<summary><b>🏅 Achievements & Discovery</b></summary>

<br>

Open the achievements menu with `/phoenixfish achievements`.

**8 built-in achievements** with tiered rewards: First Catch, Apprentice Fisher, Master Fisher, Rare Hunter, Epic Hunter, Legendary Master, Species Collector and Mutation Hunter.

**Discovery rewards.** The first catch of a species gives XP, money and optional console commands, which makes completing the catalog worthwhile.

**Discovery progress.** An Action Bar progress display and milestone titles track how much of the catalog a player has completed.

</details>

### 💰 Economy & Competition

<details>
<summary><b>💰 Economy & Selling</b></summary>

<br>

PhoenixFish has its own **built-in economy**. Money earned from selling fish is stored as an internal balance in the plugin's database, together with the rest of the player's data.

> [!IMPORTANT]
> PhoenixFish does **not** support Vault or other economy plugins. The balance can be shown in other plugins through [PlaceholderAPI](#-placeholderapi) (`%phoenixfish_balance%`).

| Command | Description |
|:--|:--|
| `/phoenixfish sell hand` | Sells the fish currently held in your hand. |
| `/phoenixfish sell all` | Sells all supported fish in your inventory. |
| `/phoenixfish sell menu` | Opens a GUI for selecting fish to sell. |

A **Sell** button is also available in the Fishing Catalog. Prices are calculated from the base price, the individual weight and the mutation. If a player's inventory is full, extra items are dropped at their feet instead of being lost.

**Dynamic fish market.** With the dynamic market switched on, prices follow supply and demand:

- Every sale lowers the price of that fish.
- The market recovers every hour.
- A price never drops below **30%** or rises above **150%** of its base price.

</details>

<details>
<summary><b>📜 Daily Contracts</b></summary>

<br>

Daily contracts give players a reason to log in every day.

- Quests are created from templates, with randomized amounts, money and XP
- Progress is saved per player
- A click-to-claim menu: `/phoenixfish contracts`
- **10 built-in templates** from Common to Legendary tiers (`contract_templates.yml`)

</details>

<details>
<summary><b>🗓️ Fishing Seasons</b></summary>

<br>

Competitive seasons that last several weeks, with a server-wide leaderboard and rewards based on rank, plus participation bonuses. `/phoenixfish season` shows the current season and the leaderboard.

</details>

<details>
<summary><b>🏆 Tournament System</b></summary>

<br>

Administrators can host timed fishing competitions.

| Command | Description |
|:--|:--|
| `/phoenixfish tournament start <minutes>` | Starts a tournament (1 to 1440 minutes). |
| `/phoenixfish tournament stop` | Stops the running tournament. |
| `/phoenixfish tournament addreward <place>` | Sets the item in your hand as the reward for that placement. |

Every catch earns points based on the fish's rarity:

| Rarity | Common | Uncommon | Rare | Epic | Legendary |
|:--|:--:|:--:|:--:|:--:|:--:|
| **Points** | 1 | 2 | 5 | 12 | 25 |

Rewards are distributed to the **top 3 players** when the tournament ends. Players in vanilla mode do not earn tournament points.

</details>

### 🔌 Integration & Configuration

<details>
<summary><b>🔌 PlaceholderAPI</b></summary>

<br>

Use these placeholders in scoreboards, holograms, Skript and any other plugin that supports [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/). PlaceholderAPI is optional: without it PhoenixFish works as usual, just without placeholders.

| Placeholder | Returns |
|:--|:--|
| `%phoenixfish_level%` | Fishing level |
| `%phoenixfish_xp%` | Current XP |
| `%phoenixfish_xp_needed%` | XP needed for the next level |
| `%phoenixfish_balance%` | Balance of the built-in economy |
| `%phoenixfish_total_catches%` | Total catches |
| `%phoenixfish_discovered%` | Discovered species |
| `%phoenixfish_discovered_total%` | Total species in the catalog |
| `%phoenixfish_biggest_weight%` | Heaviest fish caught |
| `%phoenixfish_mutation_count%` | Mutated fish caught |
| `%phoenixfish_skill_<skill>%` | Level of a skill |
| `%phoenixfish_skill_points%` | Unspent skill points |
| `%phoenixfish_season%` | Current season |

</details>

<details>
<summary><b>⚙️ Modular Features</b></summary>

<br>

Every major feature has its own switch in the `features` section of `config.yml`. A feature that is switched off does not run at all: it has no listeners, no scheduled tasks, no cached data and it is hidden from the menus, so it costs no CPU or memory.

```yaml
# Example values. See your generated config.yml for the defaults.
features:
  minigame-effects:
    enabled: true
  minigame-presets:
    enabled: false
    difficulty: "medium"
  seasonal-fish:
    enabled: false
  day-night-fish:
    enabled: false
  depth-fish:
    enabled: false
  fish-mutations:
    enabled: false
  dynamic-pricing:
    enabled: false
  contracts:
    enabled: false
  seasons:
    enabled: false
  discovery-rewards:
    enabled: false
  achievements:
    enabled: false
  discovery-progress:
    enabled: false
  skill-big-catch:
    enabled: false
    weight-bonus-per-level: 0.05
  skill-lucky-cast:
    enabled: false
    rarity-upgrade-chance-per-level: 0.10
  skill-stamina:
    enabled: false
    tension-resist-per-level: 0.10
  placeholder-api:
    enabled: true
```

Features can also be switched **live**, without a restart:

```text
/pf settings features fish-mutations true
/pf settings list
```

</details>

<details>
<summary><b>🛠️ Extensive Configuration</b></summary>

<br>

Almost every aspect of PhoenixFish can be configured, including fish movement, progress speed, rod and fishing area width, line tension, difficulty presets, rarities, weights, biome/season/time/depth restrictions, weather bonuses, XP rewards, levels, the skill tree, multi-catch, the economy and prices, tournament settings, the fishing mode toggle, custom messages, titles, rarity names, fish, rods, bait and language settings.

</details>

<details>
<summary><b>⚡ Performance</b></summary>

<br>

PhoenixFish is designed with server performance in mind. It uses:

- Asynchronous database operations
- HikariCP connection pooling
- Thread-safe caching
- Asynchronous record persistence
- Efficient player data management
- Features that are switched off are not loaded at all

Database and record operations are designed to minimize blocking on the main server thread.

</details>

<br>

## 📦 Installation

### Requirements

| Minecraft version | Java version required by the server |
|:--|:--|
| 1.21.x | Java 21 |
| 26.1 and newer | Java 25 |

PhoenixFish itself is built for Java 21 and runs on newer versions. A **Paper** or **Purpur** server on **Minecraft 1.21 – 26.1.3** is required.

### Steps

1. **Download** the latest `PhoenixFish.jar` from the [Releases](https://github.com/SudoPH/PhoenixFish/releases) page.
2. **Install** it by placing the jar into your server's `plugins` directory (for example `/home/username/server/plugins/` on Linux or `C:\Users\Username\Desktop\Server\plugins\` on Windows).
3. *(Optional)* Install [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) to use PhoenixFish placeholders in scoreboards, holograms or other plugins.
4. **Start the server.** All default files are created automatically.
5. **Configure.** Edit `plugins/PhoenixFish/config.yml` to choose your language and the features you want, then run `/pf reload`.

> [!CAUTION]
> **Do not use the server's `/reload`.** It can cause issues with plugin state, database connections and other plugin systems. To apply configuration changes without a restart, use PhoenixFish's own `/phoenixfish reload`.

> [!NOTE]
> **First start:** Paper downloads PhoenixFish's required libraries (such as HikariCP) once, so the server needs internet access on the first start.

### Updating

1. Stop the server.
2. **Back up** the `plugins/PhoenixFish` folder, and your MySQL database if you use one.
3. Replace the jar and start the server.

On the first start PhoenixFish upgrades the database automatically (adding any new columns) and keeps all player progress. Your existing `config.yml`, `fish.yml`, `baits.yml` and `rods.yml` are **not overwritten**, and new default files are added next to them. A customized `messages_en.yml` from older versions is still loaded.

<br>

## 🎮 Getting Started

| Step | What to do |
|:--:|:--|
| 🎣 | **Get a rod:** `/phoenixfish giverod <rod-name>` |
| 🪱 | **Get bait:** `/phoenixfish givebait <bait-name>`. Bait names are the keys under `baits:` in `baits.yml` and are suggested by tab completion. |
| 🤲 | **Equip bait:** place a custom bait item in your **off-hand**. Bait can increase the chance of catching specific fish rarities. |
| 🌊 | **Cast the line:** right-click on water while holding a fishing rod. |
| 🕹️ | **Play the minigame:** when a fish bites, an Action Bar interface appears. |

**Minigame controls**

- **Right-click / hold:** moves the bar upward.
- **Release:** lets the bar move downward.
- **Goal:** keep the bar aligned with the fish to fill the progress bar.
- **Tension:** if the fish escapes the bar, line tension increases.
- **Line break:** if tension reaches 100%, the line breaks and the fish escapes.

**Want vanilla fishing instead?** Use `/phoenixfish toggle` at any time, for example to catch enchanted books. Use it again to switch back.

**Catch fish and progress.** Earn XP, level up, gain Skill Points and catch luck, discover new fish, set Personal Bests, compete for the Server Record, sell fish for money, complete daily contracts, unlock achievements and join tournaments and seasons.

<br>

## 📋 Commands & Permissions

Main command: `/phoenixfish` (aliases: `/pf`, `/pfish`). All commands support tab completion, including nested ones such as `/pf settings features <TAB>`.

<details>
<summary><b>Player commands</b></summary>

<br>

| Command | Description | Permission |
|:--|:--|:--|
| `/phoenixfish` | Shows the help menu. | `phoenixfish.use` |
| `/phoenixfish skills` | Opens the Skill Tree. | `phoenixfish.use` |
| `/phoenixfish respec` | Resets your skills and refunds the points. | `phoenixfish.use` |
| `/phoenixfish catalog` | Opens the Fishing Catalog. | `phoenixfish.use` |
| `/phoenixfish catalog search <query>` | Searches the catalog by fish name or ID. | `phoenixfish.use` |
| `/phoenixfish contracts` | Opens the daily contracts menu. | `phoenixfish.use` |
| `/phoenixfish achievements` | Opens the achievements menu. | `phoenixfish.use` |
| `/phoenixfish season` | Shows the current season and its leaderboard. | `phoenixfish.use` |
| `/phoenixfish toggle` | Switches between PhoenixFish and vanilla fishing. | `phoenixfish.toggle` |
| `/phoenixfish toggle status [player]` | Shows your (or another player's) fishing mode. | `phoenixfish.toggle` |
| `/phoenixfish sell <all\|hand\|menu>` | Sells caught fish. | `phoenixfish.use` |
| `/phoenixfish fix` | Updates old rods and bait in your inventory. | `phoenixfish.use` |

</details>

<details>
<summary><b>Admin commands</b></summary>

<br>

| Command | Description | Permission |
|:--|:--|:--|
| `/phoenixfish reload` | Reloads the configuration and language files without a restart. | `phoenixfish.admin` |
| `/phoenixfish settings language <code>` | Switches the language live (`en`, `tr`, `zh_cn`). | `phoenixfish.settings` |
| `/phoenixfish settings features <name> <true\|false>` | Switches a feature on or off live. | `phoenixfish.settings` |
| `/phoenixfish settings list` | Shows the current language and all feature switches. | `phoenixfish.settings` |
| `/phoenixfish toggle <player>` | Toggles another player's fishing mode. | `phoenixfish.admin` |
| `/phoenixfish toggle reset <player>` | Resets a player's fishing mode to the default. | `phoenixfish.admin` |
| `/phoenixfish giverod <rod>` | Gives a custom fishing rod. | `phoenixfish.admin` |
| `/phoenixfish givebait <bait>` | Gives custom bait. | `phoenixfish.admin` |
| `/phoenixfish addlevel <amount>` | Modifies a player's fishing level (-1000 to 1000). | `phoenixfish.admin` |
| `/phoenixfish addcraft <amount>` | Modifies a player's crafting level. | `phoenixfish.admin` |
| `/phoenixfish fixall` | Fixes broken fishing items for all online and offline players. | `phoenixfish.admin` |
| `/phoenixfish tournament <start\|stop>` | Starts or stops a fishing tournament. | `phoenixfish.tournament.admin` |
| `/phoenixfish tournament addreward <place>` | Sets the held item as a tournament reward. | `phoenixfish.tournament.admin` |
| `/phoenixfish debug <self\|player\|rod>` | Shows debug information about fishing data. | `phoenixfish.admin` |

Offline players' supported items are automatically repaired the next time they join when applicable.

</details>

<details>
<summary><b>What does <code>/phoenixfish reload</code> reload?</b></summary>

<br>

| Reloaded | Not reloaded |
|:--|:--|
| `config.yml`, language files, `fish.yml`, `baits.yml`, `rods.yml`, `custom_recipes.yml` and the Catalog menu | The database connection, the player cache, active tournaments and active minigames. Restart the server to change these. |

</details>

<details>
<summary><b>Permission defaults</b></summary>

<br>

| Permission | Default | Description |
|:--|:--|:--|
| `phoenixfish.use` | All players | Basic commands |
| `phoenixfish.toggle` | All players | Switch your own fishing mode |
| `phoenixfish.admin` | OP only | Admin commands, bypasses the toggle cooldown, manages other players' modes |
| `phoenixfish.settings` | OP only | Change the language and feature switches live |
| `phoenixfish.tournament.admin` | OP only | Manage tournaments |

</details>

<br>

## 🗄️ Database Support

PhoenixFish supports both **SQLite** (default, no setup required) and **MySQL 8+**.

<details>
<summary><b>MySQL setup</b></summary>

<br>

1. Open `config.yml`.
2. Enable the XP system.
3. Change the database type to `mysql`.
4. Configure your MySQL connection details.
5. Restart the server.

```yaml
xp-system:
  enabled: true
  database:
    type: mysql
    host: localhost
    port: 3306
    name: phoenix_db
    username: root
    password: "your-password"
```

> `database` settings are nested **under** `xp-system`. Refer to your generated `config.yml` for the complete configuration.

**MySQL SSL.** Two options control the SSL connection:

| `use-ssl` | `verify-server-certificate` | Behavior |
|:--:|:--:|:--|
| `true` | `true` | SSL required, certificate verified (recommended for production) |
| `true` | `false` | SSL required, certificate not verified (self-signed certificates) |
| `false` | n/a | No SSL (only for a database on the same machine) |

If the database cannot be read, PhoenixFish does not overwrite existing player data with an empty profile.

</details>

<br>

## 🗂️ Configuration Files

All files are generated in `plugins/PhoenixFish/`.

| File / folder | Purpose |
|:--|:--|
| `config.yml` | Main configuration: XP, economy, skills, weather, fishing mode toggle, feature switches and general settings |
| `fish.yml` | Custom fish, weights, rarities, prices, mutations and spawn conditions |
| `baits.yml` | Custom bait definitions |
| `rods.yml` | Custom fishing rod definitions |
| `custom_recipes.yml` | Custom crafting recipes |
| `contract_templates.yml` | Daily contract templates |
| `lang/<code>/` | Language packs (see [Languages](#-languages)) |
| `records.yml` | Persistent fish records |

<br>

## 🌍 Languages

PhoenixFish translates **everything players read**: chat messages and menus, and also the names and descriptions of fish, bait, rods, contracts, achievements and skills.

| Code | Language |
|:--:|:--|
| `en` | 🇬🇧 English |
| `tr` | 🇹🇷 Turkish |

Each language is a folder in `plugins/PhoenixFish/lang/` with 7 files:

```text
plugins/PhoenixFish/lang/
├── en/
│   ├── messages.yml       # Chat and menu messages
│   ├── fish.yml           # Fish names and lore
│   ├── baits.yml          # Bait names and lore
│   ├── rods.yml           # Rod names and lore
│   ├── contracts.yml      # Contract templates
│   ├── achievements.yml   # Achievement titles and lore
│   └── skills.yml         # Skill names and descriptions
├── tr/
└── en/
```

**Missing keys fall back to English**, so a partial translation never shows broken text.

**Switch the language:** set `language` in `config.yml` and run `/pf reload`, or switch it live with `/pf settings language en`.

<details>
<summary><b>Add your own language</b></summary>

<br>

1. Copy `lang/en/` to `lang/<your_code>/`.
2. Translate every value and keep the keys identical.
3. Set `language: "<your_code>"` in `config.yml`.
4. Run `/pf reload`. Any key you did not translate falls back to English.

</details>

<br>

## 📰 What's New

| Version | Highlights |
|:--|:--|
| [**v1.2.3**](https://github.com/SudoPH/PhoenixFish/releases/tag/1.2.3) | 🔴 **Urgent security fix.** Fixed a Fishing Catalog exploit that let players take items out of the menu. Updating is strongly recommended. |
| [**v1.2.2**](https://github.com/SudoPH/PhoenixFish/releases/tag/1.2.2) | Per-player fishing mode toggle, plus fixes for selling prices, rarity balance, MySQL support and a bug that could overwrite player data after a failed database read. |
| [**v1.2.0**](https://github.com/SudoPH/PhoenixFish/releases/tag/v1.2.0) | Skill tree, weather and biome fishing, levels, fish weights, records, selling and tournaments. |
| [**v1.1.0**](https://github.com/SudoPH/PhoenixFish/releases/tag/v1.1.0) | Fishing Catalog and the multi-language message system. |
| [**v1.0.1**](https://github.com/SudoPH/PhoenixFish/releases/tag/v1.0.1) | Stability, database and performance fixes. |
| [**v1.0.0**](https://github.com/SudoPH/PhoenixFish/releases/tag/v1.0.0) | Initial release. |

See all versions on the [Releases](https://github.com/SudoPH/PhoenixFish/releases) page.

<br>

## 🔨 Add-ons

### PhoenixCraft

**PhoenixCraft** is a planned add-on that provides custom crafting recipes for PhoenixFish fishing rods and bait. It has not been released yet and is **not required** for the core fishing features. Until it is released, rods and bait can be given with `/phoenixfish giverod` and `/phoenixfish givebait`.

<br>

## 🧭 Development Status

PhoenixFish is in **active development** and continuously receives new features, balancing improvements, optimizations, integrations and bug fixes. For the latest changes, see the [GitHub Releases](https://github.com/SudoPH/PhoenixFish/releases).

<br>

## 🤝 Contributing

Contributions, bug reports and feature suggestions are welcome.

1. Check the existing [Issues](https://github.com/SudoPH/PhoenixFish/issues).
2. Open a new issue if necessary.
3. Provide as much relevant information as possible.
4. When reporting bugs, include your Minecraft version, server software and version, PhoenixFish version, configuration details and relevant logs.

Pull requests are also welcome.

<br>

## 📄 License

PhoenixFish is open-source and free to use under the **GNU General Public License v3.0**. See the [`LICENSE`](https://github.com/SudoPH/PhoenixFish/blob/main/LICENSE) file for the complete license text.

<br>

<div align="center">

**Developed with passion. Built for Minecraft.**

[GitHub](https://github.com/SudoPH) · [Releases](https://github.com/SudoPH/PhoenixFish/releases) · [Issues](https://github.com/SudoPH/PhoenixFish/issues)

</div>
