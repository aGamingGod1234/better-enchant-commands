# Better Enchant Commands

Better Enchant Commands is a Fabric mod that makes Minecraft's admin enchant commands less restrictive and easier to use.

It replaces the vanilla commands with versions that let you:

- use `/enchant` with levels from `1` to `255`
- use `/give` with a simple enchantment string in the same command

## What This Mod Changes

### `/enchant`

Vanilla `/enchant` has stricter level handling. This mod keeps the command familiar, but allows enchantment levels from `1` to `255`.

Syntax:

```text
/enchant <targets> <enchantment> [level]
```

Examples:

```text
/enchant @p sharpness
/enchant @p sharpness 10
/enchant @a minecraft:unbreaking 255
```

### `/give`

This mod also extends `/give` so you can attach enchantments directly in the same command.

Syntax:

```text
/give <targets> <item> [count] [enchantments]
```

Enchantment format:

```text
enchantments:<id>:<level>,<id>:<level>,...
```

You can use either:

- short IDs like `sharpness:10`
- full namespaced IDs like `minecraft:sharpness:10`

Examples:

```text
/give @p minecraft:diamond_sword enchantments:sharpness:10,unbreaking:5
/give @p minecraft:bow 1 enchantments:power:20,infinity:1
/give @a minecraft:netherite_pickaxe 3 enchantments:mending:1,efficiency:255
```

## Quick Notes

- Enchantment levels must be between `1` and `255`
- `/give` item count must be between `1` and `6400`
- Unknown enchantments and invalid levels return clear error messages
- If a target player's inventory is full, the item is dropped instead
- Command permission level matches admin/operator level `2`

## Installation

### Server

This mod is server-side only.

- install [Fabric Loader](https://fabricmc.net/use/server/)
- install [Fabric API](https://modrinth.com/mod/fabric-api) on the server
- place the mod jar in the server `mods` folder
- start the server

Players do not need this mod installed on the client.

### Singleplayer

This mod also works in singleplayer because the integrated world runs a local server.

- install Fabric Loader
- install Fabric API
- place the mod jar in your local `mods` folder

## Requirements

- Minecraft `1.21.11`
- Fabric Loader `0.18.4` or newer
- Fabric API
- Java `21`

## Why Use This Mod

- keeps the vanilla command style instead of adding a separate custom command
- makes high-level testing and custom gameplay setups easier
- lets admins create enchanted items faster with one `/give` command
- works without requiring client-side installation

## For Developers

Build on Windows:

```powershell
.\gradlew.bat clean build
```

Build on Linux/macOS:

```bash
./gradlew clean build
```

Optional in-game stress verification:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dbetterenchantcommands.stressTest=true'
.\gradlew.bat runServer --args="nogui"
Remove-Item Env:JAVA_TOOL_OPTIONS
```

The stress verifier runs automated command checks and shuts down the server when complete.
