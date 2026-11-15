# Jarvis — an AI butler for Minecraft

Paper / Purpur · Fabric · NeoForge · Minecraft 1.21.11 – 26.3

Jarvis is an AI companion who follows you, fights for you, mines for you,
builds for you, and understands plain English via OpenAI, Claude, Grok,
Gemini, or a local Ollama model. On Paper he is a Citizens NPC; on Fabric and
NeoForge he is a fake player. Same butler, same config, same commands.

```
you:     jarvis, dig me a staircase down to diamond level
Jarvis:  Right away, sir. Mind the lava.
```

---

## What he does

- **Understands sentences, not commands.** "Chop twenty trees then deposit" is a
  valid order. Local models are first-class — a 7B on your own box handles most
  of it, and the paid providers are a fallback rather than a requirement.
- **Mines, chops, farms, fishes and builds**, carrying the spoils back to a chest
  you nominate. Real pathfinding and real block-breaking — no teleport-hopping.
- **Fights.** Sword, trident in water, bow at range, with the stance chosen from
  the situation rather than from his rank alone.
- **Earns his kit.** A service ladder from Hired to Without Equal; better
  tools and enchantments arrive as he works, not from a config file.
- **Learns and remembers.** He remembers builds that went well, and what each
  player told him, so "where did I say my base was?" works after a restart.
- **Builds safely.** AI builds show a preview and wait for yes; dangerous
  blocks, size and distance are capped, and `/jarvis undo` takes down any job.
- **Welcomes new players** with the controller bell and a short tutorial.
- **Notices things.** Idle remarks on what you are carrying and how deep you
  have got — off by default, and keyed so he cannot repeat himself.
- **Finds nether portals**, remembers them, and can tell you where any of them
  comes out on the other side without looking at anything at all.
- **Talks and listens** through Simple Voice Chat, with local speech at both
  ends.

Every one of those has a fuller account, with its limits, in
**[docs/features.md](docs/features.md)**.

---

## Installation

One release, three files. Take the one for your server from the
[latest release](https://github.com/iamgadgetman/jarvis/releases/latest):

| Server | File | Needs |
|---|---|---|
| Paper / Purpur 1.21.11 – 26.2 | `jarvis-paper-<version>.jar` | [Citizens 2](https://ci.citizensnpcs.co/job/citizens2/) 2.0.43+ · Java 21 (25 on 26.x) |
| Fabric, Minecraft 26.3 | `jarvis-fabric-<version>.jar` | Fabric Loader 0.19.5+ · [Fabric API](https://modrinth.com/mod/fabric-api) · Java 25 |
| NeoForge 26.1.2, 26.2 and 26.3 | `jarvis-neoforge-<version>.jar` | Java 25 |

On every platform: an AI provider is optional (every slash command works
without one; natural language and freeform building need one), and
[Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) is
optional (for talking to him and hearing him back; the plugin on Paper, the
mod on Fabric and NeoForge). Nothing else: speech recognition and his voice
run inside the server, and their models are fetched the first time voice is
turned on.

### Zero-cost start

Jarvis talks with no spend at all. Pick one, in this order:

1. **Ollama on your own box** (free, local): install [Ollama](https://ollama.com),
   then `ollama pull mistral`. Jarvis looks for it at `localhost:11434`.
2. **A free Gemini key**: get one at [aistudio.google.com](https://aistudio.google.com/),
   then `export GEMINI_API_KEY=...` before starting the server.
3. **Jarvis Cloud**: `/jarvis cloud activate` in-game, no key to manage. Free
   plan with 125 requests a month and a two-week trial; paid plans at
   [jarvis-cloud.app](https://jarvis-cloud.app). Optional: the plugin never
   contacts it unless you run that command.

On startup the console says which providers can answer
(`AI providers: ollama OK (mistral) · gemini no key · …`), and if none can it
prints these three options. `/jarvis setup` checks again.

### Paper / Purpur

1. Drop `jarvis-paper-<version>.jar` and `Citizens.jar` into `plugins/`.
   [WorldEdit](https://enginehub.org/worldedit/) too if you want the
   schematic library.
2. Start the server. Jarvis writes `plugins/Jarvis/config.yml` and logs which
   AI providers it found.
3. If none: one of the free options above, then `/jarvis setup`. Paid keys work
   too: `/jarvis bell`, ring it, **Admin > AI setup**, or set them in
   `config.yml` and `/jarvis reload`.

Optional but recommended for experience memory: `ollama pull nomic-embed-text`
on your Ollama box. Without it memory still works, on the weaker
keyword-matching path.

### Fabric

1. Drop `jarvis-fabric-<version>.jar` and Fabric API into `mods/`.
2. Start the server (or the world). Jarvis writes `config/jarvis/config.yml`.
3. Same as Paper from there: the bell, **Admin > AI setup**.

### NeoForge

1. Drop `jarvis-neoforge-<version>.jar` into `mods/`.
2. Start the server (or the world). Jarvis writes `config/jarvis/config.yml`.
3. Same as Paper from there: the bell, **Admin > AI setup**.

What differs on the mods: the butler is a fake player rather than a Citizens
NPC, admin means operator, there is no WorldEdit and so no schematic
library, and freeform builds use the JSON planner. Details in
[jarvis-fabric/README.md](jarvis-fabric/README.md) and
[jarvis-neoforge/README.md](jarvis-neoforge/README.md). In singleplayer, the
mod runs on the integrated server: open the world to LAN for voice (Escape >
Open to LAN, or `/publish` in chat), since
Simple Voice Chat has no voice server until you do.

### First run

```
/jarvis bell        # get the controller bell
/jarvis summon      # summon Jarvis
/jarvis debug       # provider, model and experience-memory status
/jarvis loot        # see what he is carrying
```

Then just talk to him in chat: `jarvis start mining`.

---

## Talking to him

```
jarvis, mine some diamonds
jarvis, build me a small stone house
jarvis, take me home
jarvis, where does this portal come out?
jarvis, follow me
```

More in **[docs/commands.md](docs/commands.md)**, which also lists every slash
command.

---

## Documentation

| Page | What is in it |
|---|---|
| **[Features](docs/features.md)** | Every subsystem, what it does and where it stops |
| **[Commands](docs/commands.md)** | The full command table, permissions, and natural-language examples |
| **[Configuration](docs/configuration.md)** | `config.yml` in full, AI providers and API keys, permission nodes |
| **[Troubleshooting](docs/troubleshooting.md)** | When something misbehaves — plus updating, performance and building from source |
| **[Voice](docs/voice.md)** | Talking to him and hearing him back: what it needs, what it costs, the gate |
| **[Releasing](docs/dev/releasing.md)** | How a version ships: the workflow, the tag, Modrinth and CurseForge |
| **[CHANGELOG](CHANGELOG.md)** | The full history, and why each thing was done that way |
| **[ROADMAP](ROADMAP.md)** | Decided but not built, with the reasoning kept alongside |

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full history, and the
[releases page](https://github.com/iamgadgetman/jarvis/releases) for downloads.

---

## License

MIT License — see [LICENSE](LICENSE) for details.

---

*Built with Citizens, WorldEdit, Adventure API, and a lot of caffeine.*
