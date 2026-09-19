# Jarvis for Fabric

The Fabric adapter: `jarvis-core` on a Fabric server, with the butler as a
fake player walked by `jarvis-nav`. See
[docs/dev/platform-interface.md](../docs/dev/platform-interface.md) for the
design; this module is the thin layer that design leaves to an adapter.

A Gradle Loom project, separate from the Maven reactor, because Fabric's
toolchain is Gradle. It compiles core, nav and the loader-neutral server code
in `../jarvis-vanilla` straight from their sources and bundles core's
libraries (org.json, snakeyaml, HikariCP, sqlite-jdbc) as nested jars, so the
one mod file is all a server needs besides Fabric API. The only class in this
module is the entry point; everything else is shared with the NeoForge mod in
`../jarvis-neoforge`.

## Building

The Fabric and Mojang repositories have to be reachable. The `fabric`
GitHub workflow builds it on every push that touches it and attaches
`jarvis-fabric-<version>.jar`; locally:

```
cd jarvis-fabric
gradle build          # Gradle 9.5.1, JDK 25
```

## Running

Minecraft 26.3, Fabric Loader 0.19.5 or later, Fabric API. Drop the jar in
`mods/`. On first start it writes `config/jarvis/config.yml` (the same file
the plugin uses) and `config/jarvis/databases.yml`; set your AI endpoint in
config.yml and restart, or `/jarvis reload`.

Then it is the plugin: `/jarvis summon`, the bell, chat, the menus. What
differs from Paper:

- The butler is a fake player, not a Citizens NPC. He walks on the
  pathfinder in `jarvis-nav`, opens doors, swims, climbs ladders and breaks
  blocks at tool speed. His skin comes from the Mojang account named
  "Jarvis", as Citizens fetches it on Paper.
- Permissions: Fabric has no permission plugin, so `jarvis.admin` means
  operator and everything else is open.
- Voice works as on Paper: with the Simple Voice Chat mod installed and
  `voice.enabled` on, he hears you (whisper key, always, or a wake word)
  and answers out loud, positionally from his body or in your ear when he
  is away. The speech server (`voice.endpoint`) is the same one.
- Not on Fabric (yet): WorldEdit clipboard saves and rotated pastes, and
  the GraalJS build planner (freeform builds use the JSON planner).
  Schematics, the library and pastes work.

## What is Carpet's

The fake player is adapted from the Carpet mod (MIT); see
`../jarvis-vanilla/THIRD-PARTY-LICENSES.md` for the files and the licence.
