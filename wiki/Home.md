# Jarvis Wiki

**Jarvis** is an AI butler for Minecraft: he follows you, fights for you, mines,
farms, builds, fetches your death drops, runs your errands, and understands
plain English. He runs on **Paper/Purpur**, **Fabric** and **NeoForge** — the
same butler, the same config, the same commands, one file per platform.

On Paper he is a Citizens NPC. On the mods he is a server-side fake player.
Everything above that layer is identical.

---

## Start here

| Page | What is in it |
|---|---|
| [Installation & Setup](Installation-and-Setup) | Which file, what it needs, first run |
| [Fabric & NeoForge](Fabric-and-NeoForge) | What differs on the mods, singleplayer notes |
| [The Bell Menu](The-Bell-Menu) | The in-game interface, page by page |
| [Commands & Permissions](Commands-and-Permissions) | Every command and who may run it |
| [Configuration](Configuration) | `config.yml`, section by section |
| [AI Providers](AI-Providers) | Ollama, Claude, OpenAI, Grok, Gemini, and the routing between them |
| [Voice](Voice) | Talking to him and hearing him back |
| [Mining System](Mining-System) | Ore hunting and branch mines |
| [Building System](Building-System) | AI-designed builds and schematics |
| [Progression](Progression) | The service ladder, from Hired to Without Equal |
| [Butler Events](Butler-Events) | Greetings, death-drop recovery, briefings |
| [Player Requests](Player-Requests) | Players asking for items, admins approving |
| [Troubleshooting](Troubleshooting) | When something misbehaves |
| [Changelog](Changelog) | Version history |

---

## Quick start

1. Take the file for your server from the
   [latest release](https://github.com/iamgadgetman/jarvis/releases/latest):
   `jarvis-paper-<version>.jar` into `plugins/` (with Citizens), or
   `jarvis-fabric-<version>.jar` / `jarvis-neoforge-<version>.jar` into `mods/`.
2. Start the server. Jarvis writes its config and comes up.
3. `/jarvis bell`, ring the bell, **Admin → AI setup**, and pick a provider.
   Nothing needs editing by hand, and nothing needs a restart.
4. `/jarvis summon`, then talk to him:

```
jarvis, dig a mine here
jarvis, build me a small stone cottage
jarvis, take me home
```

Without an AI provider every slash command still works except the two that
are an AI call: `/jarvis ask`, and `/jarvis build <description>` when nothing
in the schematic library matches. You lose natural-language chat and freeform
building.

---

## Support

- **Issues:** [GitHub Issues](https://github.com/iamgadgetman/jarvis/issues)
- **Source:** [github.com/iamgadgetman/jarvis](https://github.com/iamgadgetman/jarvis)
- **Release notes:** [CHANGELOG.md](https://github.com/iamgadgetman/jarvis/blob/main/CHANGELOG.md)
