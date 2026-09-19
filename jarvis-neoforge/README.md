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

## Running

Minecraft 26.3, NeoForge 26.3.0.x. Drop the jar in `mods/`. On first start it
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
