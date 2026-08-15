# PhoenixFish

**PhoenixFish** is a highly customizable, performance-focused fishing plugin for **Paper** servers.

[![PhoenixFish Demo](https://img.youtube.com/vi/JEfE1KNi5do/maxresdefault.jpg)](https://www.youtube.com/watch?v=JEfE1KNi5do)

It completely overhauls the vanilla fishing experience with an interactive fishing minigame, custom fish, rods, bait, rarities, XP progression, multi-catch mechanics, and extensive configuration options.

> **Status:** In Development · **License:** GNU GPL v3.0 · **Minecraft:** 1.21+ · **Java:** 21 · **Server Software:** Paper / Purpur

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Getting Started](#getting-started)
- [Commands & Permissions](#commands--permissions)
- [Database Support](#database-support)
- [Configuration](#configuration)
- [Languages](#languages)
- [Add-ons](#add-ons)
- [Development Status](#development-status)
- [Contributing](#contributing)
- [License](#license)

---

## Features

### 🎣 Interactive Fishing Minigame

PhoenixFish replaces the vanilla fishing experience with an interactive Action Bar minigame. Players need to:

- Track fish movement
- Manage the progress bar
- Control line tension
- Successfully secure their catch

### 🐟 Custom Fish & Rods

Create unlimited custom fish and fishing rods with configurable:

- Names
- Materials
- Weights
- Rarities
- Fight strength
- XP rewards
- Lore
- Custom Model Data
- Luck multipliers

### 🪱 Custom Bait

Create custom bait items that can target specific fish rarities. This allows players to make strategic decisions about which fish they want to catch.

### ⭐ Rarity System

PhoenixFish includes **5 configurable rarity tiers**, ranging from **Common** to **Legendary**. Each rarity can have its own colors, lore, sounds, and visual effects. Legendary catches can trigger custom **Title** and **Subtitle** effects.

### 📈 XP & Level Progression

Players gain XP by catching fish and can level up their fishing skill. The progression system supports:

- Fishing XP
- Fishing levels
- Passive luck bonuses
- Configurable 2x multi-catch chance
- Configurable 3x multi-catch chance

### ⚙️ Performance

PhoenixFish is designed with server performance in mind. It uses:

- Asynchronous database operations
- HikariCP connection pooling
- Thread-safe caching
- Folia-compatible task handling

---

## Installation

PhoenixFish requires a server running **Paper or a compatible fork such as Purpur** with **Java 21**.

### 1. Download

Download the latest `PhoenixFish.jar` from the [Releases](https://github.com/SudoPH/PhoenixFish/releases) page.

### 2. Install

Place `PhoenixFish.jar` into your server's `plugins` directory.

**Linux:**
```
/home/username/server/plugins/
```

**Windows:**
```
C:\Users\Username\Desktop\Server\plugins\
```

### 3. Install PhoenixCraft (Optional)

If you want custom crafting recipes for fishing rods and bait, place `PhoenixCraft.jar` into the `plugins` directory as well.

### 4. Restart the Server

Restart your server after installing the plugin.

> ⚠️ Do not use `/reload`. Reloading can cause issues with plugin state and database connections.

### 5. Configure

After the first startup, configuration files will be generated in:

```
plugins/PhoenixFish/
```

Edit these files to customize fish, rods, bait, messages, fishing mechanics, and other plugin features. Restart the server after making configuration changes.

---

## Getting Started

### Get a Fishing Rod

Use:

```
/phoenixfish giverod <rod-name>
```

to receive a custom fishing rod. Alternatively, rods can be crafted if **PhoenixCraft** is installed and the corresponding recipes are configured.

### Equip Bait

Place a custom bait item in your **off-hand**. Bait can increase the spawn chance of specific fish rarities.

### Cast the Line

Right-click on water while holding a fishing rod.

### Fishing Minigame

When a fish bites, an Action Bar interface appears:

- **Right-click / Hold:** Moves the bar upward.
- **Release:** Allows the bar to move downward.
- **Goal:** Keep the bar aligned with the fish to fill the progress bar.
- **Tension:** If the fish escapes the bar, line tension increases.
- **Line Break:** If tension reaches 100%, the line breaks and the fish escapes.

### Catch Fish & Progress

Catch fish to earn XP, increase your fishing level, unlock passive bonuses, and gain access to multi-catch chances.

---

## Commands & Permissions

| Command | Description | Required Permission |
| --- | --- | --- |
| `/phoenixfish` | Shows the help menu. | `phoenixfish.use` |
| `/phoenixfish fix` | Scans and fixes broken custom items in your own inventory. | `phoenixfish.use` |
| `/phoenixfish giverod <rod>` | Gives a custom fishing rod to the player. | `phoenixfish.admin` |
| `/phoenixfish fixall` | Fixes online players' inventories and ender chests; loaded containers are fixed automatically when opened. | `phoenixfish.admin` |

> Offline players' items are automatically fixed the next time they join — no manual action needed.

### Permission Defaults

| Permission | Default |
| --- | --- |
| `phoenixfish.use` | All players |
| `phoenixfish.admin` | OP only |

### Aliases

```
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

> `database` settings are nested **under** `xp-system`. Refer to your generated `config.yml` for the full configuration.

---

## Configuration

Almost every part of PhoenixFish can be configured, including:

- Fish movement
- Progress speed
- Rod width
- Fishing area width
- Line tension
- Fish rarities
- XP rewards
- Multi-catch chances
- Custom messages
- Titles and subtitles
- Rarity names
- Fish
- Rods
- Bait
- Language settings

---

## Languages

PhoenixFish currently includes:

- English
- Turkish

Messages, rarity names, titles, and fishing minigame messages can be customized.

---

## Add-ons

### PhoenixCraft

**PhoenixCraft** is an optional add-on that provides custom crafting recipes for PhoenixFish fishing rods and bait.

---

## Development Status

PhoenixFish is currently **in development**. Additional features, integrations, and improvements are planned for future releases.

---

## Contributing

Contributions, bug reports, and feature suggestions are welcome. If you find a bug or have an idea for PhoenixFish, open an **Issue** on GitHub and provide as much relevant information as possible.

---

## License

PhoenixFish is open-source and free to use under the **GNU General Public License v3.0**. See the [`LICENSE`](https://github.com/SudoPH/PhoenixFish/blob/main/LICENSE) file for the complete license text.
