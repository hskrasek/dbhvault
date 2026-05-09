# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**DBHVault** — a server-only Fabric mod for Minecraft 26.1.x, written in Kotlin. Provides world backups for the Dashboarders Heaven server.

## Repository

This repo uses git for version control.

## Commands

| Task | Command |
| --- | --- |
| Run dev dedicated server | `./gradlew runServer` |
| Build the mod jar | `./gradlew build` |
| Decompile Minecraft for IDE navigation | `./gradlew genSources` |
| Clean build outputs | `./gradlew clean` |

There is intentionally no `runClient` task — see "Server-only enforcement" below. The wrapper auto-downloads Gradle 9.4; JDK 25 must be selectable via `JAVA_HOME` or Gradle toolchain auto-provisioning.

## Architecture

### Server-only enforcement (three layers)

The mod refuses to run on a client through three independent mechanisms — keep all three aligned when changing entrypoints or environment:

1. **`fabric.mod.json`** declares `"environment": "server"`, so Fabric Loader skips the mod on a logical client entirely.
2. **The entrypoint** `dev.skrasek.dbhvault.DBHVault` implements `DedicatedServerModInitializer` (not `ModInitializer`). Its `onInitializeServer()` only fires on a *dedicated* server — never on the integrated server inside single-player.
3. **`build.gradle.kts`** does *not* call `splitEnvironmentSourceSets()`, and the `loom { mods { } }` block only registers `sourceSets["main"]`. There is no `src/client/`.

When writing new code, do not import `net.minecraft.client.*`. Those classes resolve in the IDE because Loom's dev classpath includes them, but they crash on a dedicated server with `NoClassDefFoundError`.

### Kotlin entrypoint via fabric-language-kotlin

`DBHVault` is declared as a Kotlin `object` (singleton). The entrypoint in `fabric.mod.json` therefore uses `"adapter": "kotlin"` — without it, Loader would call a no-arg Java constructor and fail because Kotlin `object` only exposes a static `INSTANCE` field. Any new entrypoint declared as a Kotlin `object` must also set `"adapter": "kotlin"`.

`fabric-language-kotlin` is both a build dependency (`implementation` in `build.gradle.kts`) and a runtime dependency users must install (declared in `depends`).

### Mixins must be Java

Mixin classes live under `src/main/java/dev/skrasek/dbhvault/mixin/` and are registered in `dbhvault.mixins.json`. Mixin's bytecode-rewriting annotation processor does not understand Kotlin's synthetic constructs, so Kotlin files cannot be mixin targets. To call into Kotlin from a mixin, annotate Kotlin members with `@JvmStatic` on a Kotlin `object` or `companion object` and call them from Java.

The mixin config's `compatibilityLevel` is `JAVA_25` and must match the Java toolchain in `build.gradle.kts`.

### Minecraft 26.1 specifics

26.1 is the first **unobfuscated** Minecraft release. Day-to-day consequences for editing the build:

- Mod dependencies use plain `implementation(...)` — *not* `modImplementation(...)`.
- There is no `mappings(...)` line; Mojang ships parameter names directly.
- The publishable artifact comes from `tasks.jar` — there is no `remapJar` step.

### Version pinning

`gradle.properties` is the single source of truth for every external version (Minecraft, Loader, Loom, Fabric API, Kotlin, fabric-language-kotlin, mod identity). Plugin versions are threaded through `settings.gradle.kts`'s `pluginManagement` block so `build.gradle.kts`'s `plugins { }` carries no version literals. Bumping anything starts in `gradle.properties`; never hardcode a version in `build.gradle.kts`.

### Resource templating

`tasks.processResources` expands `${version}` in `fabric.mod.json` from `project.version`. No other tokens are expanded — extend the `expand(...)` map in `build.gradle.kts` if you need more.

### Where to add code

- New mod logic → `src/main/kotlin/dev/skrasek/dbhvault/` (Kotlin).
- New mixin classes → `src/main/java/dev/skrasek/dbhvault/mixin/` (Java only — see above) and register in `dbhvault.mixins.json`.
- New translation strings → `src/main/resources/assets/dbhvault/lang/en_us.json`.

### Renaming the mod ID

The string `dbhvault` is hardcoded in five places. Renaming requires touching all of them:

1. `fabric.mod.json` — `id`, `mixins[]`, `entrypoints.server[].value` package, `icon` path
2. `dbhvault.mixins.json` — filename and `package` field
3. `src/main/resources/assets/dbhvault/` — directory name
4. `build.gradle.kts` — `loom { mods { register("dbhvault") } }`
5. `gradle.properties` — `archives_base_name`

Plus `settings.gradle.kts`'s `rootProject.name` and the Kotlin/Java package directories.
