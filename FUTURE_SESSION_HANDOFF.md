# Better Enchant Commands: Future Session Handoff

## Goal
- Deliver one production jar that works on Fabric for Minecraft `1.21.1` through `1.21.11`.
- Keep `/enchant` and `/give` **core** behavior unchanged (same syntax, same level range, same drop-on-full fallback).
- Offer an expanded command surface (`/unenchant`, `/enchantinfo`, `/enchantlist`, `/enchantpreset`, `/repair`, `/enchants`) that can be disabled individually via permission nodes.
- Survive differing Fabric API patch versions without hard-pinning to one runtime.

## 2026-04-14 Change Set
- Hot-path perf + reliability pass (see `PROJECT_LOG.md` entry dated 2026-04-14).
- Added config, undo, audit, confirmation, presets, LuckPerms soft-dep, and i18n.
- Verify the new code compiles against the production mapping set before shipping — this session only performed structural edits; the CI loom plugin fetch failed in the sandbox so no final gradle build was run here.

## Current Repo State
- The repo already contains an initial cross-version compatibility pass.
- The mod currently builds successfully on the `1.21.11` toolchain.
- The built jar is `build/libs/better-enchant-commands-1.0.0.jar`.
- The working tree is intentionally dirty with compatibility edits still in progress.

## Latest External Matrix Attempt (2026-03-10)
- Created a disposable external Loom harness at:
  - `C:\Users\aGamingGod\AppData\Local\Temp\better-enchant-commands-matrix\1.21.11-0.18.4-0.141.3+1.21.11`
- The harness launch shape is now proven:
  - `gradlew.bat runServer --args="nogui"`
  - candidate jar copied into `run/mods`
  - `run/eula.txt` and `run/server.properties` copied from the main repo and patched only for isolated world/port values
- The first required tuple booted successfully and loaded:
  - `better-enchant-commands 1.0.0`
  - `fabric-loader 0.18.4`
  - `fabric-api 0.141.3+1.21.11`
  - `minecraft 1.21.11`
- The matrix stopped on that first tuple per plan because the stress verifier failed immediately.
- Preserved failing log:
  - `C:\Users\aGamingGod\AppData\Local\Temp\better-enchant-commands-matrix\1.21.11-0.18.4-0.141.3+1.21.11\probe-1.21.11.log`
- Failure detail:
  - `MinecraftCompatibility.withPermissionLevel(...)` threw `IllegalStateException: Failed compatibility invocation: net.minecraft.commands.arguments.EntityAnchorArgument$Anchor#valueof`
  - the stack reached `InGameStressVerifier.runOnServerThread(...)` before the command stress checks began
- No repo code or metadata was changed after this failure.
- After that stop, the user explicitly requested a fresh build and local redeploy anyway.
- `.\gradlew.bat clean build` completed successfully on `2026-03-10`.
- The rebuilt jar was copied to:
  - `C:\Users\aGamingGod\AppData\Roaming\.minecraft\mods\better-enchant-commands-1.0.0.jar`
- Treat that deployed jar as a manual in-progress test build, not as a matrix-cleared release.

## What Was Completed
- Fixed the original live crash for `1.21.11`.
- Added `src/main/java/com/agaminggod/betterenchantcommands/compat/MinecraftCompatibility.java`.
- Rewired unstable direct calls in:
  - `src/main/java/com/agaminggod/betterenchantcommands/command/EnchantCommand.java`
  - `src/main/java/com/agaminggod/betterenchantcommands/command/GiveCommand.java`
  - `src/main/java/com/agaminggod/betterenchantcommands/verification/InGameStressVerifier.java`
- Rebuilt successfully with:
  - `.\gradlew.bat clean build`
- Verified the current target in dev with:
  - `.\gradlew.bat runServer --args="nogui"`
  - `JAVA_TOOL_OPTIONS=-Dbetterenchantcommands.stressTest=true`
- Confirmed the dev stress verifier previously passed on `1.21.11` with `1532` checks and `0` failures.

## Binary Compatibility Work Already Done
- Inspected the remapped production jar with `javap -verbose`.
- Confirmed the jar no longer directly links to the earlier bad patch-specific symbols that caused the live crash:
  - `net/minecraft/class_1799.method_57824`
  - `net/minecraft/class_2168.method_75037`
  - `net/minecraft/class_12099.field_63210`
  - `net/minecraft/class_1799.method_58695`
  - `net/minecraft/class_2378.method_46746`
- Compared remaining direct intermediary references against cached `1.21.1` and `1.21.11` intermediary jars.
- Confirmed these specific symbols exist in both cached versions:
  - `class_2960.method_60654`
  - `class_2960.method_12832`
  - `class_2960.method_12836`
  - `class_5321.method_29177`
  - `class_2378.method_10235`
  - `class_1799.method_57379`
  - `class_1799.method_7964`
  - `class_1799.method_7960`
  - `class_9304.field_49385`
  - `class_9304.method_57536`
  - `class_9304$class_9305.method_57547`
  - `class_9304$class_9305.method_57549`
  - `class_5455.method_30530`

## Important Runtime Test Result
- A temporary external Fabric server harness was eventually made to launch successfully by copying the main repo `build.gradle` structure instead of using a minimal Loom script.
- The harness loaded:
  - `better-enchant-commands 1.0.0`
  - `fabric-api 0.141.3+1.21.11`
  - `fabric-loader 0.18.4`
  - `minecraft 1.21.11`
- The mod initialized correctly under that external harness.
- The run stopped before stress execution because the temporary run directory did not yet contain `server.properties` and `eula.txt`.
- This means the external-harness path is viable; it just needs dedicated server bootstrap files in each test run dir.

## What Is Still Left To Do
- Fix the external-runtime blocker discovered on `2026-03-10`:
  - tighten the permission-elevation compatibility logic in `src/main/java/com/agaminggod/betterenchantcommands/compat/MinecraftCompatibility.java`
  - rebuild the candidate jar
  - rerun the external matrix starting again with `1.21.11` before attempting the lower versions
- Decide and then set final metadata in `src/main/resources/fabric.mod.json`:
  - `minecraft` should likely become `>=1.21.1 <=1.21.11`
  - loader floor likely needs to move down from `>=0.18.4` to something compatible with the lower tested runtime, probably `>=0.16.7`
- Finish real runtime stress validation outside the named dev environment for at least:
  - `1.21.1` + `fabric-loader 0.16.7` + `fabric-api 0.116.7+1.21.1`
  - `1.21.4` + `fabric-loader 0.16.10` + `fabric-api 0.119.4+1.21.4`
  - `1.21.11` + `fabric-loader 0.18.4` + `fabric-api 0.141.3+1.21.11`
- Optional but useful:
  - `1.21.11` + older cached `fabric-api 0.139.4+1.21.11`
- If all runtime checks pass:
  - rebuild
  - replace `C:\Users\aGamingGod\AppData\Roaming\.minecraft\mods\better-enchant-commands-1.0.0.jar`
  - update `PROJECT_LOG.md` with the final compatibility completion entry

## Known Open Question
- The source still does not compile cleanly if the main project is directly retargeted to `1.21.1` named mappings.
- Current strategy is binary compatibility through the remapped production jar plus reflection, not lowest-patch source compilation.
- Do not throw away that approach unless you are prepared for a larger mapping-level rewrite.

## Working Tree Files To Review First
- `src/main/java/com/agaminggod/betterenchantcommands/compat/MinecraftCompatibility.java`
- `src/main/java/com/agaminggod/betterenchantcommands/command/EnchantCommand.java`
- `src/main/java/com/agaminggod/betterenchantcommands/command/GiveCommand.java`
- `src/main/java/com/agaminggod/betterenchantcommands/verification/InGameStressVerifier.java`
- `src/main/resources/fabric.mod.json`
- `gradle.properties`
- `gradle/wrapper/gradle-wrapper.properties`

## Cached Versions Already Present Locally
- Fabric API:
  - `0.116.7+1.21.1`
  - `0.119.4+1.21.4`
  - `0.139.4+1.21.11`
  - `0.141.3+1.21.11`
- Fabric Loader:
  - `0.16.0`
  - `0.16.10`
  - `0.16.14`
  - `0.18.2`
  - `0.18.4`
- Minecraft installs:
  - `1.21.1`
  - `1.21.4`
  - `1.21.11`

## Recommended Next Move
1. Fix the permission-elevation reflection selection in `MinecraftCompatibility` so the external harness no longer trips `EntityAnchorArgument$Anchor#valueOf` during `withPermissionLevel(...)`.
2. Rebuild `build/libs/better-enchant-commands-1.0.0.jar`.
3. Reuse the external harness layout that now exists under `C:\Users\aGamingGod\AppData\Local\Temp\better-enchant-commands-matrix\1.21.11-0.18.4-0.141.3+1.21.11`.
4. Rerun the required matrix from the top.
5. Only after all required tuples pass, widen `fabric.mod.json` compatibility and redeploy the jar.
