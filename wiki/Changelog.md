# Changelog

The full history lives in
**[CHANGELOG.md](https://github.com/iamgadgetman/jarvis/blob/main/CHANGELOG.md)**,
which records what changed in each version and why it was done that way. Each
release also carries its own notes on the
[releases page](https://github.com/iamgadgetman/jarvis/releases).

This page keeps only the shape of recent history; the file is the record.

---

## v0.17.0 — the same butler on Fabric and NeoForge

One release, three files: `jarvis-paper`, `jarvis-fabric`, `jarvis-neoforge`.

- **Jarvis as a mod.** Fabric and NeoForge adapters on Minecraft 26.3, with
  the butler as a server-side fake player walking on Jarvis's own pathfinder.
  See [Fabric & NeoForge](Fabric-and-NeoForge).
- **A platform-free core.** Everything that is not a loader API is shared, and
  the build refuses to let it import Bukkit, Citizens, Fabric or Minecraft.
- **AI setup from the bell menu.** Providers, keys, models and a test, with no
  config editing and no restart. See [AI Providers](AI-Providers).
- **Builds that come out whole, and furnished.** Shapes rather than block
  lists; a dwelling gets a bed, a chest, a crafting table and light. See
  [Building System](Building-System).
- **Voice on every platform, inside the server.** whisper.cpp and Piper in
  process; no container, no speech service. See [Voice](Voice).
- **The Paper jar is `jarvis-paper-<version>.jar`.**

## Before that

| Version | What it brought |
|---|---|
| 0.16.0 | Nether portals: sightings, memory, the 1:8 arithmetic, the escort |
| 0.15.0 | Idle remarks, off by default |
| 0.14.0 | Archery and combat doctrine |
| 0.13.0 | Voice, the progression ladder, the bell menu |
| 0.12.x | Dataset export, reasoning before retrieval, self-explaining recovery |
| 0.9.0 | The building assistant, rebuilt |
| 0.8.0 | Experience memory |
| 0.7.0 | Groundskeeping: farm, chop, fish, light |
| 0.6.0 | Death-drop recovery, home and escort |
| 0.5.0 | The steward: briefings, standing duties |
| 0.4.0 | The defender, reworked |
| 0.3.0 | Tiered AI routing, Ollama first |
| 0.2.0 | Branch mining, deposit chests |
| 0.1.0 | Smart mining, reworked |
