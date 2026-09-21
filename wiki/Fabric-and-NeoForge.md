# Fabric & NeoForge

Jarvis is a mod as well as a plugin. `jarvis-fabric-<version>.jar` and
`jarvis-neoforge-<version>.jar` carry the same butler as the Paper plugin:
the same config file, the same commands, the same bell menu, the same AI
routing. Only the layer underneath differs.

Everything that is not a loader API lives in a platform-free core that all
three builds share, so a fix to mining or building lands on all three at once.

---

## What you need

| | Fabric | NeoForge |
|---|---|---|
| Minecraft | 26.3 | 26.3 |
| Loader | Fabric Loader 0.19.5+ | NeoForge 26.3.0.x |
| Also required | [Fabric API](https://modrinth.com/mod/fabric-api) | nothing |
| Java | 25 | 25 |

Drop the jar in `mods/`. Core's libraries ride inside it, so that one file is
all the server needs.

---

## The butler is a fake player

There is no Citizens on a mod, so the butler is a **server-side player**: a
real entity that the game and other mods treat as a player. He is dressed in
the skin of the Mojang account named Jarvis, the same skin Citizens fetches on
Paper.

He walks on Jarvis's own A\* pathfinder rather than Citizens', opens doors,
swims, climbs ladders, and breaks blocks at tool speed through the game's own
game-mode code — so a protection mod sees an ordinary player breaking an
ordinary block, and stops him exactly as it would stop you.

## What differs from Paper

| | Paper | The mods |
|---|---|---|
| Body | Citizens NPC | Fake player |
| Admin | `jarvis.admin` permission | Server operator |
| Config | `plugins/Jarvis/config.yml` | `config/jarvis/config.yml` |
| Schematic library | WorldEdit | not available |
| Freeform building | JavaScript planner where GraalJS is present | the JSON planner |
| Voice | Simple Voice Chat plugin | Simple Voice Chat mod |

The JSON planner is the one both platforms use in practice, and it is the
better of the two; the shapes it produces are expanded by the core rather
than written block by block.

---

## Singleplayer

The mod runs on the integrated server, so everything works in a single-player
world, with one exception: **Simple Voice Chat has no voice server until the
world is opened to LAN.** Press Escape and choose **Open to LAN**, or type
`/publish` in chat (it needs cheats allowed in that world). Do it each time
you open the world. `/jarvis voice` will then report the voice server as up.

Without that step voice cannot hear you, and `/jarvis voice` says so.

---

## Known limits

- **No schematic library** without WorldEdit. `/jarvis build <description>`
  works; `/jarvis schematic ...` does not appear.
- **Java 25** is required, because Minecraft 26.3 is built for it.
- The mods are server-side. Other players need nothing installed beyond
  Simple Voice Chat if you want voice.
