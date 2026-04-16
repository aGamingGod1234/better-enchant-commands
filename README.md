# Better Enchant Commands

Better Enchant Commands is a server-side Fabric mod for Minecraft (Java 21, Fabric 1.21.x) that replaces Minecraft's admin enchanting commands with a more powerful, safer, and auditable set. It is designed for server operators, datapack authors, and map-makers who need to hand out precisely-configured enchanted gear at scale without wrestling with vanilla's terse component syntax.

## What the mod does

- **Raises the level ceiling.** `/enchant` accepts levels from 1 to 255 (vanilla caps out at the enchantment's natural maximum). Clamp values are still enforced at the parser, so nothing invalid can sneak into item NBT.
- **Lets `/give` apply enchantments inline.** One `enchantments:<id>:<level>,<id>:<level>,...` token at the end of `/give` applies a whole bundle in a single command, with tab-completion that filters to only compatible enchantments by default.
- **Adds a preset system.** `/enchantpreset save|delete|list|apply` persists and replays named bundles of enchantments, so operators can hand out standard loadouts without memorising IDs.
- **Adds cleanup and inspection.** `/unenchant`, `/enchantinfo`, `/enchantlist`, and `/enchants status` make it fast to audit and remove enchantments in place.
- **Adds undo.** `/enchants undo` restores the last bulk modification made by the caller from a bounded per-operator history stack.
- **Adds convenience helpers.** `/repair` hands out mended (and, where compatible, Unbreaking III) copies of any item, and `/enchants allow_all_enchantments` toggles strict-vs-permissive compatibility checking for operators who know what they are doing.
- **Gates and audits everything.** Every command requires permission level 2, and every privileged action is written to a structured `[AUDIT]` SLF4J logger with sanitised operator and target names, so server log pipelines can route and alert on enchantment abuse.

## Design summary

- **Fail-safe validation.** Enchantment strings are parsed once into fully-validated `(Identifier, level)` pairs before any item is touched; parse failures leave items unmodified and return a structured command error. Levels are range-checked both when parsed from command input and when loaded from disk presets.
- **Undo is bounded.** The per-operator undo deque is capped at the configured history size (default 8) to avoid an unbounded memory footprint on long-running sessions.
- **Confirmation gate for bulk operations.** Commands whose target count exceeds `confirmation_threshold` stash a short-lived token and require `/enchants confirm <token>` to proceed, so a typo that would enchant an entire server does not fire-and-forget. Replaced pending tokens are now surfaced back to the operator instead of being silently dropped.
- **Reflective compatibility layer.** `MinecraftCompatibility` resolves permission checks, registry lookups, and component read helpers through method-shape heuristics cached per class, so the same jar can run across minor Minecraft revisions without a hard dependency on obfuscation-remapped method names.
- **Log-injection hardened.** Any string originating from player input (operator names, preset names, target names, label text) is stripped of ASCII control characters before being fed to SLF4J `{}` placeholders, so a crafted name can't forge audit lines.

## Command Reference

### `/enchant`

Vanilla `/enchant` has stricter level handling. This mod keeps the command familiar, but allows enchantment levels from `1` to `255` and suggests useful level values in tab completion.

```text
/enchant <targets> <enchantment> [level]
/enchant list
```

`/enchant list` prints the enchantments on the held item of the player running the command (players only).

Examples:

```text
/enchant @p sharpness
/enchant @p sharpness 10
/enchant @a minecraft:unbreaking 255
/enchant list
```

### `/give`

Extends `/give` so you can attach enchantments directly in the same command.

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

Tab completion for the enchantment string filters candidates to those that are actually compatible with the chosen item (unless `allow_all_enchantments` is on).

Examples:

```text
/give @p minecraft:diamond_sword enchantments:sharpness:10,unbreaking:5
/give @p minecraft:bow 1 enchantments:power:20,infinity:1
/give @a minecraft:netherite_pickaxe 3 enchantments:mending:1,efficiency:255
```

### `/unenchant`

Removes one or all enchantments from the target's held item.

```text
/unenchant <targets>                   # removes every enchantment
/unenchant <targets> <enchantment>     # removes only that enchantment
```

Operations are reversible through `/enchants undo`.

### `/enchantinfo`

Prints metadata for a specific enchantment (vanilla max level, mod-allowed max level, etc.).

```text
/enchantinfo <enchantment>
```

### `/enchantlist`

Lists all registered enchantments, optionally filtered.

```text
/enchantlist
/enchantlist sharp
```

### `/enchantpreset`

Save, apply, list, or delete named bundles of enchantments. Presets live in the mod config file and survive restarts.

```text
/enchantpreset save <name>                 # saves enchantments on held item
/enchantpreset apply <name> <targets>      # applies to targets' held items
/enchantpreset list                        # shows stored presets
/enchantpreset delete <name>               # removes a preset
```

### `/repair`

Convenience wrapper that hands out an item with Mending (and Unbreaking 3 when compatible) pre-applied.

```text
/repair <targets> <item> [count]
```

### `/enchants`

Umbrella command for configuration, history, and confirmation flow.

```text
/enchants status                                     # prints current config and undo depth
/enchants allow_all_enchantments                     # read current value
/enchants allow_all_enchantments <true|false>        # OP-only: toggle strict compatibility
/enchants undo                                       # reverts the most recent enchant/unenchant
/enchants confirm <token>                            # completes a pending bulk operation
```

Only operators at permission level `4` (server administrators) may change `allow_all_enchantments`.

## Compatibility Validation

By default, `allow_all_enchantments` is **false**. The mod verifies that an enchantment can actually be applied to the target item and rejects invalid pairs with:

```text
You cannot enchant <item> with <enchantment>
```

To bypass this check (for example on a testing server), an OP can run:

```text
/enchants allow_all_enchantments true
```

The setting is persisted to `config/better-enchant-commands.json`.

## Confirmation for Bulk Operations

When `/enchant` would affect more than `confirmation_threshold` targets (default `10`), the command stages the action behind a token and responds with:

```text
This would affect N targets. Re-run with /enchants confirm <token> within 30s.
```

This helps prevent accidental `@a` mistakes on a busy server.

## Undo History

`/enchant`, `/unenchant`, and `/enchantpreset apply` each push a snapshot of the affected players' main-hand items. `/enchants undo` pops the most recent snapshot from **your own** history (each operator has an independent stack) and restores the saved state.

The stack is bounded by `undo_history_size` (default `20`) and is cleared on server shutdown. `/give` and `/repair` do not create undo entries.

## Audit Log

Set `audit_log_enabled` to `true` in the config file to emit structured audit lines through the `better-enchant-commands.audit` logger. Route them to a dedicated file by configuring log4j on the server.

## Configuration File

`config/better-enchant-commands.json`:

```json
{
  "allow_all_enchantments": false,
  "audit_log_enabled": false,
  "undo_history_size": 20,
  "confirmation_threshold": 10,
  "presets": {}
}
```

The file is written automatically the first time the server starts. **Manual edits to the config file are only picked up at server startup** — the mod does not hot-reload the file. Use the in-game commands (`/enchants allow_all_enchantments`, `/enchantpreset save|delete`) to change settings live; those writes are atomic and flushed to disk immediately.

## Permissions

Every command honours vanilla operator permission levels **and** LuckPerms / fabric-permissions-api nodes when that mod is installed:

| Node | Fallback level | Commands |
| --- | --- | --- |
| `betterenchantcommands.command.enchant` | 2 | `/enchant` |
| `betterenchantcommands.command.give` | 2 | `/give` |
| `betterenchantcommands.command.unenchant` | 2 | `/unenchant` |
| `betterenchantcommands.command.enchantinfo` | 2 | `/enchantinfo` |
| `betterenchantcommands.command.enchantlist` | 2 | `/enchantlist` |
| `betterenchantcommands.command.enchantpreset` | 2 | `/enchantpreset` |
| `betterenchantcommands.command.repair` | 2 | `/repair` |
| `betterenchantcommands.command.enchants` | 2 | `/enchants` (status, undo, confirm) |
| `betterenchantcommands.command.enchants.admin` | 4 | `/enchants allow_all_enchantments` |

If neither LuckPerms nor fabric-permissions-api is installed, the vanilla permission levels are enforced.

## Quick Notes

- Enchantment levels must be between `1` and `255`
- `/give` item count must be between `1` and `6400`
- Duplicate enchantment ids in one `/give` call are rejected up front
- Unknown enchantments, invalid levels, and incompatible enchantments return clear error messages
- If a target player's inventory is full, the item is dropped instead
- Messages use vanilla translation keys under `better-enchant-commands.*` and always render readable English fallbacks

## Installation

### Server

This mod is server-side only.

- install [Fabric Loader](https://fabricmc.net/use/server/)
- install [Fabric API](https://modrinth.com/mod/fabric-api) on the server
- (optional) install [fabric-permissions-api-v0](https://github.com/lucko/fabric-permissions-api) or LuckPerms for permission-node support
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
- provides a safety net (strict compatibility + undo + confirmation) so mistakes are recoverable
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
