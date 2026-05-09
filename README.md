# DBHVault

> **D**aily **B**ackup, **H**eaven's **V**ault — server-side world backups for
> Dashboarders Heaven.

A Fabric **server-only** mod for Minecraft 26.1.x, written in Kotlin.

## Toolchain

| Component | Version |
| --- | --- |
| Minecraft | 26.1 |
| Fabric Loader | 0.19.2 |
| Fabric API | 0.145.1+26.1 |
| [Fabric Language Kotlin](https://github.com/FabricMC/fabric-language-kotlin) | 1.13.11+kotlin.2.3.21 |
| Loom | 1.16.1 |
| Gradle | 9.4.1 |
| Kotlin | 2.3.21 |
| Java | 25 |

## Getting started

1. Install JDK 25 (`brew install openjdk@25`).
2. Run a dev server:
   ```sh
   ./gradlew runServer
   ```
3. Build the distributable jar:
   ```sh
   ./gradlew build
   ```

## Project layout

```
src/main/
├── kotlin/dev/skrasek/dbhvault/      # Kotlin sources
│   └── DBHVault.kt                   # DedicatedServerModInitializer entrypoint
├── java/dev/skrasek/dbhvault/mixin/  # Java sources (mixins must be Java)
└── resources/
    ├── fabric.mod.json
    ├── dbhvault.mixins.json
    └── assets/dbhvault/
```

There is no `src/client/` source set — the mod ships with `"environment": "server"`
in `fabric.mod.json` and the entrypoint implements `DedicatedServerModInitializer`,
so Loader will refuse to load it on a logical client.

## Notes on 26.1.x

- 26.1 is the first **unobfuscated** Minecraft release. Loom no longer remaps your
  code, which is why dependencies use plain `implementation` instead of
  `modImplementation`.
- Yarn mappings are no longer officially supported; this project uses Mojang's
  official mappings (the default in Loom 1.15+).
- Mixins must still be written in Java, not Kotlin — they live under
  `src/main/java/.../mixin/`.

## License

Apache License 2.0. See [LICENSE](./LICENSE).
