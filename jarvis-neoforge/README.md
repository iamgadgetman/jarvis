# Jarvis for NeoForge

The NeoForge adapter: `jarvis-core` on a NeoForge server, with the butler as
a fake player walked by `jarvis-nav`. See
[docs/dev/platform-interface.md](../docs/dev/platform-interface.md) for the
design; this module is the thin layer that design leaves to an adapter.

A ModDevGradle project, separate from the Maven reactor. It compiles core,
nav and the loader-neutral server code in `../jarvis-vanilla` straight from
their sources and bundles core's libraries (org.json, snakeyaml, HikariCP,
sqlite-jdbc) as Jar-in-Jar, so the one mod file is all a server needs. The
only class in this module is the entry point; everything else is shared with
the Fabric mod in `../jarvis-fabric`.

## Building

NeoForge's and Mojang's repositories have to be reachable. The `neoforge`
GitHub workflow builds it on every push that touches it, starts a dedicated
server with it to prove it loads, and attaches `jarvis-neoforge-<version>.jar`;
locally:

```
cd jarvis-neoforge
gradle build          # Gradle 9.5.1, JDK 25
gradle runServer      # a dev server in run/server (accept the EULA there first)
```

For an older game, override the versions (the comment in
`gradle.properties` has them) and pick the `mc26.1` compatibility shim, which
covers 26.1.x and 26.2:

```
gradle build -Pminecraft_version=26.1.2 '-Pminecraft_version_range=[26.1.2]' \
  -Pneo_version=26.1.2.109 '-Pneo_version_range=[26.1.2,26.1.3)' -Pmc_compat=26.1
```

Or the `mc1.21.1` shim, for Minecraft 1.21.1 and NeoForge 21.1.x, which is
what All the Mods 10 runs on. 1.21.1 is on Java 21:

```
gradle build -Pminecraft_version=1.21.1 '-Pminecraft_version_range=[1.21.1]' \
  -Pneo_version=21.1.259 '-Pneo_version_range=[21.1.200,21.2)' -Pmc_compat=1.21.1 \
  -Pjava_version=21
```

1.21.1 is older than every 26.x and differs from them in more than a few
names, so its shim does two things. Single calls that changed (Identifier vs
ResourceLocation, `level()` vs `serverLevel()`, `hurtServer` vs `hurt`, and
so on) go through `McCompat`, like the 26.x ones. A class whose overridden
signatures changed, and so cannot go through `McCompat` (the fake player, its
connection and packet listener, the two chest menus, the `PlayerList` mixin
and the skin lookup), has a whole 1.21.1 copy in
`jarvis-vanilla/src/mc1.21.1/java` at the same path, and the build uses that
copy in place of the one in `src/main/java`. Each copy says so at the top;
when the main file changes, change its copy too. NeoForge's own API moved
once, for the damage event: `src/neo21.1` and `src/neo26` here.

The jar keeps the name `jarvis-neoforge-<version>.jar` whatever it was built
for; the game version it accepts is in its `neoforge.mods.toml`.

## Running

Players take `jarvis-neoforge-<version>.jar` from the
[releases page](https://github.com/iamgadgetman/jarvis/releases); every
release carries the Paper, Fabric and NeoForge files together.

Minecraft 26.3, NeoForge 26.3.0.x, by default. The same source also builds
for **26.2** (NeoForge 26.2.0.x), **26.1.2** (NeoForge 26.1.2.x) and
**1.21.1** (NeoForge 21.1.x, Java 21: All the Mods 10); a jar is for one game
version only, so take the one that matches the server.
Drop the jar in `mods/`. On first start it
writes `config/jarvis/config.yml` (the same file the plugin uses) and
`config/jarvis/databases.yml`; set up your AI from the bell menu (Admin, AI
setup) or in config.yml.

Then it is the plugin: `/jarvis summon`, the bell, chat, the menus. What
differs from Paper is what differs on Fabric, see
[../jarvis-fabric/README.md](../jarvis-fabric/README.md): a fake player for
a butler, operator for admin, voice through the Simple Voice Chat mod, no
WorldEdit, and the JSON planner for freeform builds.

## What is Carpet's

The fake player is adapted from the Carpet mod (MIT); see
`../jarvis-vanilla/THIRD-PARTY-LICENSES.md` for the files and the licence.
