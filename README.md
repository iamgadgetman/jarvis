# Jarvis — AI-Powered Minecraft Butler Plugin

Paper / Purpur · Java 17 bytecode · Minecraft 1.21.11 – 26.2 (26.x servers need Java 25)

Jarvis is an AI companion plugin that spawns a Citizens NPC who follows you,
fights for you, mines for you, builds for you — and understands plain English
via OpenAI, Claude, Grok, Gemini, or a local Ollama model.

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
- **Earns his kit.** A service ladder from Probationary to Without Equal; better
  tools and enchantments arrive as he works, not from a config file.
- **Learns.** He remembers builds that went well and reasons about a request
  before searching his memory for a precedent.
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

### Requirements
- Paper / Purpur 1.21+ (or any fork with Bukkit API)
- Java 17+
- [Citizens 2 on spigot](https://www.spigotmc.org/resources/citizens.13811/) or [Citizens 2 Jenkins build](https://ci.citizensnpcs.co/job/citizens2/)
- [WorldEdit](https://enginehub.org/worldedit/) (optional — for schematic pasting)
- At least one AI API key (or local Ollama)
- An embedding model for experience memory (optional but recommended):
  `ollama pull nomic-embed-text` — without it memory still works, but on the
  weaker keyword-matching path

### Steps
1. Download **`Jarvis-<version>.jar`** from the
   [latest release](https://github.com/iamgadgetman/jarvis/releases/latest)
   and drop it into your `plugins/` folder
2. Drop `Citizens.jar` and `WorldEdit.jar` into `plugins/` (if not already present)
3. Start the server — Jarvis will generate `plugins/Jarvis/config.yml`
4. Add your AI API key(s) to `config.yml` (see below)
5. Restart or `/reload confirm`

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
