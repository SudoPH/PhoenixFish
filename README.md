# 🎣 PhoenixFish

**PhoenixFish** is a highly customizable, performance-focused fishing plugin for **Paper and Purpur servers**.

[![PhoenixFish Demo](https://img.youtube.com/vi/JEfE1KNi5do/maxresdefault.jpg)](https://www.youtube.com/watch?v=JEfE1KNi5do)

It completely overhauls the vanilla fishing experience with an interactive fishing minigame, custom fish, rods, bait, rarities, XP progression, skill progression, environmental fishing, fish weights, personal and server records, economy, selling, tournaments, and extensive configuration options.

> **Status:** In Development · **License:** GNU GPL v3.0 · **Minecraft:** 1.21+ · **Java:** 21 · **Server Software:** Paper / Purpur

---

## Table of Contents

* [Features](#features)
* [Fishing Minigame](#fishing-minigame)
* [Custom Fish & Rods](#custom-fish--rods)
* [Custom Bait](#custom-bait)
* [Rarity System](#rarity-system)
* [XP & Fishing Levels](#xp--fishing-levels)
* [Skill Tree](#skill-tree)
* [Weight System](#weight-system)
* [Record System](#record-system)
* [Environmental Fishing](#environmental-fishing)
* [Fishing Catalog](#fishing-catalog)
* [Economy & Selling](#economy--selling)
* [Tournament System](#tournament-system)
* [Performance](#performance)
* [Installation](#installation)
* [Getting Started](#getting-started)
* [Commands & Permissions](#commands--permissions)
* [Database Support](#database-support)
* [Configuration](#configuration)
* [Languages](#languages)
* [Add-ons](#add-ons)
* [Development Status](#development-status)
* [Contributing](#contributing)
* [License](#license)

---

## Features

### 🎣 Interactive Fishing Minigame

PhoenixFish replaces the vanilla fishing experience with an interactive Action Bar minigame.

Players need to:

* Track fish movement
* Control the fishing bar
* Manage line tension
* Keep the bar aligned with the fish
* Successfully secure their catch

If the line tension reaches 100%, the line breaks and the fish escapes.

---

### 🐟 Custom Fish & Rods

Create unlimited custom fish and fishing rods with configurable:

* Names
* Materials
* Minimum and maximum weight
* Rarities
* Fight strength
* XP rewards
* Lore
* Custom Model Data
* Luck multipliers
* Biome restrictions

Fish can be configured to appear only in specific biomes while unrestricted fish remain available everywhere.

---

### 🪱 Custom Bait

Create custom bait items that can target specific fish rarities.

Bait can be configured to provide different effects and can interact with the **Lucky Bait** skill to reduce bait consumption.

---

### ⭐ Rarity System

PhoenixFish includes **5 configurable rarity tiers**, ranging from **Common** to **Legendary**.

Each rarity can have its own:

* Name
* Color
* Lore
* Sounds
* Visual effects

Legendary catches can trigger custom **Title** and **Subtitle** effects.

---

### 📈 XP & Fishing Levels

Players gain XP by catching fish and can level up their fishing skill.

The XP progression system uses:

```text
Required XP = Current Level × 100
```

Each fishing level grants:

* **+2% catch luck**
* **1 Skill Point**

Example:

```text
Level 1 → 100 XP
Level 2 → 200 XP
Level 3 → 300 XP
...
```

---

### 🌳 Skill Tree

Players can spend their earned Skill Points through:

```text
/phoenixfish skills
```

Available skills include:

| Skill             | Effect                                                |
| ----------------- | ----------------------------------------------------- |
| **Fast Catcher**  | Increases minigame progress speed by 0.5% per level.  |
| **Master Hunter** | Increases rare/epic fish chance by 2% per level.      |
| **Double Catch**  | Gives a 10% chance per level to catch 2 fish at once. |
| **Lucky Bait**    | Gives a 20% chance per level to avoid consuming bait. |

Each skill can be upgraded up to **Level 5**.

---

### ⚖️ Weight System

Every fish can have an individual randomized weight.

Configure the minimum and maximum weight in `fish.yml`:

```yaml
min-weight: 0.5
max-weight: 5.0
```

When a fish is caught, PhoenixFish generates a random decimal value between these limits.

Example:

```text
Carp → 3.42 kg
```

The generated weight is automatically added to the fish's lore:

```text
Weight: 3.42 kg
```

Fish weight also directly affects its economic value.

```text
Sale Price = Base Price × Fish Weight
```

For example:

```text
0.5 kg → $5
5.0 kg → $50
```

This makes larger fish significantly more valuable.

---

### 🏆 Record System

The Weight System introduces a competitive record system stored in `records.yml`.

#### Personal Best

A player sets a **Personal Best (PB)** when:

* Catching a fish species for the first time.
* Catching a heavier fish of a species they have already caught.

The player receives an Action Bar notification:

```text
Congratulations! New Personal Best: 4.12 kg!
```

A level-up sound is also played.

#### Server Record

A **Server Record** is created when a player catches the heaviest fish of that species ever recorded on the server.

A new server record is announced to everyone:

```text
🔔 [Player] has set a new Server Record!
Fish: Legendary Shark
Weight: 125.8 kg!
```

The record-breaking player receives the special:

```text
UI_TOAST_CHALLENGE_COMPLETE
```

achievement sound.

Record data persists across server restarts and is written asynchronously to minimize performance impact.

---

### 🌍 Environmental Fishing

Fishing can be affected by the environment.

#### 🌧️ Weather

Weather provides configurable fishing bonuses:

| Weather      | Effect                                          |
| ------------ | ----------------------------------------------- |
| Rain         | +20% catch luck                                 |
| Thunderstorm | +50% catch luck and increased rare fish chances |

Configuration:

```yaml
rain-luck-bonus: 0.2
storm-luck-bonus: 0.5
```

#### 🌲 Biomes

Fish can be restricted to specific Minecraft biomes through `fish.yml`.

Example:

```yaml
biomes:
  - SNOWY_PLAINS
  - ICE_SPIKES
```

Example use cases:

* ❄️ Ice Fish → `SNOWY_PLAINS`, `ICE_SPIKES`
* 🌋 Lava Fish → Nether biomes such as `BASALT_DELTAS`

Fish without biome restrictions can still be caught anywhere.

---

### 📖 Fishing Catalog

PhoenixFish includes an interactive **Fishing Catalog**.

Open it with:

```text
/phoenixfish catalog
```

The Catalog allows players to:

* Browse available fish
* Browse custom bait
* Browse custom rods
* Track discovered fish
* View undiscovered fish as `???`
* Navigate through multiple pages
* Sell fish directly through the Catalog

Fish are automatically revealed after being caught for the first time.

---

### 💰 Economy & Selling

PhoenixFish supports economy integration through **Vault**.

Selling commands:

```text
/phoenixfish sell hand
/phoenixfish sell all
/phoenixfish sell menu
```

| Command                  | Description                                         |
| ------------------------ | --------------------------------------------------- |
| `/phoenixfish sell hand` | Sells the fish currently held in the player's hand. |
| `/phoenixfish sell all`  | Sells all supported fish in the player's inventory. |
| `/phoenixfish sell menu` | Opens a GUI for selecting fish to sell.             |

A **Sell** button is also available in the Fishing Catalog.

Fish prices are calculated using their base price and individual weight.

> Vault is required for economy-related features.

---

### 🏆 Tournament System

Administrators can host timed fishing competitions.

#### Start

```text
/phoenixfish tournament start <minutes>
```

#### Stop

```text
/phoenixfish tournament stop
```

#### Add Reward

The item currently held in the player's hand can be assigned as a reward for a specific placement.

```text
/phoenixfish tournament addreward <place>
```

Example:

```text
/phoenixfish tournament addreward 1
```

This sets the item currently held in the player's hand as the **1st place reward**.

Tournament rankings can be based on:

* Total catch count
* Fish rarity

Rewards are distributed to the **top 3 players** when the tournament ends.

---

### ⚙️ Extensive Configuration

Almost every aspect of PhoenixFish can be configured, including:

* Fish movement
* Progress speed
* Rod width
* Fishing area width
* Line tension
* Fish rarities
* Fish weights
* Biome restrictions
* Weather bonuses
* XP rewards
* Fishing levels
* Skill Tree
* Multi-catch
* Economy
* Fish prices
* Tournament settings
* Custom messages
* Titles and subtitles
* Rarity names
* Fish
* Rods
* Bait
* Language settings

---

## Performance

PhoenixFish is designed with server performance in mind.

It uses:

* Asynchronous database operations
* HikariCP connection pooling
* Thread-safe caching
* Asynchronous record persistence
* Efficient player data management

Database and record operations are designed to minimize blocking on the main server thread.

---

## Installation

PhoenixFish requires a server running **Paper or a compatible fork such as Purpur** with **Java 21**.

### 1. Download

Download the latest `PhoenixFish.jar` from the [Releases](https://github.com/SudoPH/PhoenixFish/releases) page.

### 2. Install

Place `PhoenixFish.jar` into your server's `plugins` directory.

**Linux:**

```text
/home/username/server/plugins/
```

**Windows:**

```text
C:\Users\Username\Desktop\Server\plugins\
```

### 3. Install Vault

If you want to use the economy and selling features, install **Vault** and a compatible economy provider.

### 4. Install PhoenixCraft (Optional)

If you want custom crafting recipes for fishing rods and bait, place `PhoenixCraft.jar` into the `plugins` directory as well.

PhoenixCraft is **not required** for the core fishing features.

### 5. Restart the Server

Restart your server after installing the plugin.

> ⚠️ **Do not use `/reload`.** Reloading can cause issues with plugin state, database connections, and other plugin systems.

### 6. Configure

After the first startup, configuration files will be generated in:

```text
plugins/PhoenixFish/
```

Edit these files to customize fish, rods, bait, messages, fishing mechanics, economy, skills, weather, and other plugin features.

Restart the server after making configuration changes.

---

## Getting Started

### Get a Fishing Rod

Use:

```text
/phoenixfish giverod <rod-name>
```

to receive a custom fishing rod.

Alternatively, rods can be crafted if **PhoenixCraft** is installed and the corresponding recipes are configured.

### Equip Bait

Place a custom bait item in your **off-hand**.

Bait can increase the chance of catching specific fish rarities.

### Cast the Line

Right-click on water while holding a fishing rod.

### Fishing Minigame

When a fish bites, an Action Bar interface appears:

* **Right-click / Hold:** Moves the bar upward.
* **Release:** Allows the bar to move downward.
* **Goal:** Keep the bar aligned with the fish to fill the progress bar.
* **Tension:** If the fish escapes the bar, line tension increases.
* **Line Break:** If tension reaches 100%, the line breaks and the fish escapes.

### Catch Fish & Progress

Catch fish to:

* Earn XP
* Increase your fishing level
* Gain Skill Points
* Improve catch luck
* Discover new fish
* Set Personal Best records
* Compete for Server Records
* Earn money by selling fish
* Participate in tournaments

---

## Commands & Permissions

| Command                                     | Description                                                             | Required Permission |
| ------------------------------------------- | ----------------------------------------------------------------------- | ------------------- |
| `/phoenixfish`                              | Shows the help menu.                                                    | `phoenixfish.use`   |
| `/phoenixfish skills`                       | Opens the Skill Tree.                                                   | `phoenixfish.use`   |
| `/phoenixfish catalog`                      | Opens the Fishing Catalog.                                              | `phoenixfish.use`   |
| `/phoenixfish sell hand`                    | Sells the fish in your hand.                                            | `phoenixfish.use`   |
| `/phoenixfish sell all`                     | Sells all supported fish in your inventory.                             | `phoenixfish.use`   |
| `/phoenixfish sell menu`                    | Opens the fish selling menu.                                            | `phoenixfish.use`   |
| `/phoenixfish fix`                          | Scans and fixes broken custom items in your inventory.                  | `phoenixfish.use`   |
| `/phoenixfish giverod <rod>`                | Gives a custom fishing rod to the player.                               | `phoenixfish.admin` |
| `/phoenixfish addlevel <amount>`            | Modifies a player's fishing level.                                      | `phoenixfish.admin` |
| `/phoenixfish fixall`                       | Fixes broken fishing items across supported inventories and containers. | `phoenixfish.admin` |
| `/phoenixfish tournament start <minutes>`   | Starts a fishing tournament.                                            | `phoenixfish.admin` |
| `/phoenixfish tournament stop`              | Stops the active tournament.                                            | `phoenixfish.admin` |
| `/phoenixfish tournament addreward <place>` | Sets the held item as a tournament reward.                              | `phoenixfish.admin` |

> Offline players' supported items are automatically repaired the next time they join when applicable.

### Permission Defaults

| Permission          | Default     |
| ------------------- | ----------- |
| `phoenixfish.use`   | All players |
| `phoenixfish.admin` | OP only     |

### Aliases

```text
/pfish
/pf
```

---

## Database Support

PhoenixFish supports both **SQLite** and **MySQL**.

### SQLite

SQLite is the default database and requires no additional setup.

### MySQL

To use MySQL:

1. Open `config.yml`.
2. Enable the XP system.
3. Change the database type to `mysql`.
4. Configure your MySQL connection details.
5. Restart the server.

Example:

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

---

## Configuration

PhoenixFish uses several configuration files:

| File                 | Purpose                                                                       |
| -------------------- | ----------------------------------------------------------------------------- |
| `config.yml`         | Main plugin configuration, XP, economy, skills, weather, and general settings |
| `fish.yml`           | Custom fish, weights, rarities, prices, and biome restrictions                |
| `rods.yml`           | Custom fishing rod definitions                                                |
| `custom_recipes.yml` | Custom crafting recipes                                                       |
| `messages_en.yml`    | English messages                                                              |
| `messages_tr.yml`    | Turkish messages                                                              |
| `records.yml`        | Persistent fish records                                                       |

Almost every aspect of the plugin can be customized without modifying the source code.

---

## Languages

PhoenixFish currently includes:

* 🇬🇧 English
* 🇹🇷 Turkish

Language files include configurable:

* Chat messages
* Action Bar messages
* Titles
* Subtitles
* GUI messages
* Catalog messages
* Economy messages
* Tournament messages
* Record notifications
* Console messages

The active language can be selected through `config.yml`.

---

## Add-ons

### PhoenixCraft

**PhoenixCraft** is an optional add-on that provides custom crafting recipes for PhoenixFish fishing rods and bait.

PhoenixCraft is **not required** for the core PhoenixFish fishing features.

---

## Development Status

PhoenixFish is currently **in active development**.

The project is continuously receiving new features, balancing improvements, optimizations, integrations, and bug fixes.

For the latest changes, see the [GitHub Releases](https://github.com/SudoPH/PhoenixFish/releases).

---

## Contributing

Contributions, bug reports, and feature suggestions are welcome.

If you find a bug or have an idea for PhoenixFish:

1. Check the existing [Issues](https://github.com/SudoPH/PhoenixFish/issues).
2. Open a new issue if necessary.
3. Provide as much relevant information as possible.
4. Include your Minecraft version, server software/version, PhoenixFish version, configuration details, and relevant logs when reporting bugs.

Pull requests are also welcome.

---

## License

PhoenixFish is open-source and free to use under the **GNU General Public License v3.0**.

See the [`LICENSE`](https://github.com/SudoPH/PhoenixFish/blob/main/LICENSE) file for the complete license text.
