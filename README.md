# Better Enchant Commands

Better Enchant Commands is a Fabric server-side mod for Minecraft 1.21.x that overrides:

- `/enchant` to allow enchantment levels `1..255`
- `/give` to support optional simple enchantment syntax:
  - `enchantments:<id>:<level>,<id>:<level>,...`

## Features

- Vanilla command override for `/enchant` and `/give`
- Defensive command execution with error feedback and logging
- No mixins
- Java 21 + Mojang mappings
- Built for Minecraft `1.21` with broad `1.21.x` compatibility intent

## Build

Windows:

```powershell
.\gradlew.bat clean build
```

Linux/macOS:

```bash
./gradlew clean build
```

## Stress Verification (Optional)

Run the integrated in-game stress verifier:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dbetterenchantcommands.stressTest=true'
.\gradlew.bat runServer --args="nogui"
Remove-Item Env:JAVA_TOOL_OPTIONS
```

The verifier runs automated command checks and shuts down the server when complete.
