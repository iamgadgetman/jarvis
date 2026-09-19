# Jarvis Fabric spike

The proving ground for a Fabric butler, from the last section of
[docs/dev/platform-interface.md](../docs/dev/platform-interface.md). It is not
the Fabric adapter. It answers one question: can a client-less player be
spawned, walked along a path the core plans, and made to break a block, all
from the server side and without a navigator?

It is a Gradle Loom project, separate from the Maven reactor, because Fabric's
toolchain is Gradle. It compiles `jarvis-nav` and the four core value types it
needs straight from their sources.

## Building

The Fabric and Mojang repositories have to be reachable. The
`fabric-spike` GitHub workflow builds it on every push that touches it and
attaches `jarvis-fabric-spike-<version>.jar`; locally:

```
cd jarvis-fabric-spike
gradle build          # Gradle 9.5.1, JDK 25
```

## Trying it

Drop the jar into `mods/` of a Fabric server (or client) for Minecraft 26.3 with
Fabric Loader 0.19.5 or later. No Fabric API needed. Then, as an operator:

```
/jspike spawn             Jarvis appears where you stand, in his uniform
/jspike spawn <name>      or someone else, wearing that account's skin
/jspike goto  x y z       he walks there
/jspike dig   x y z       he walks within reach and breaks that block
/jspike status            where he is and what he is doing
/jspike stop
/jspike kill
```

The server log reports each plan (`path N steps, M nodes`) and each replan
after he gets stuck or strays. The skin comes from the Mojang account of that
name, looked up the way Citizens does it on Paper; on an offline-mode server
or without network he spawns with a default skin and the log says why.

## What is Carpet's

The fake player itself is adapted from the Carpet mod (MIT); see
`THIRD-PARTY-LICENSES.md` for the files and the licence.
