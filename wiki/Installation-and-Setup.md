# Installation & Setup

One release, three files. Take the one for your server from the
[latest release](https://github.com/iamgadgetman/jarvis/releases/latest).

| File | Server | Needs |
|---|---|---|
| `jarvis-paper-<version>.jar` | Paper / Purpur **1.21.11 – 26.2** | [Citizens 2](https://ci.citizensnpcs.co/job/citizens2/) 2.0.43+ · Java 21 (25 on 26.x) |
| `jarvis-fabric-<version>.jar` | Fabric, Minecraft **26.3** | Fabric Loader 0.19.5+ · [Fabric API](https://modrinth.com/mod/fabric-api) · Java 25 |
| `jarvis-neoforge-<version>.jar` | NeoForge **26.3.0.x** | Java 25 |

Optional on every platform:

- **An AI provider** — Ollama, Claude, OpenAI, Grok or Gemini. Every slash
  command works without one; natural-language chat and freeform building need
  one. See [AI Providers](AI-Providers).
- **[Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat)** — for
  talking to him and hearing him back. The plugin on Paper, the mod on Fabric
  and NeoForge. See [Voice](Voice).
- **WorldEdit** (Paper only) — for the schematic library.

Nothing else. Speech recognition and his voice run inside the server, and
their model files are fetched the first time voice is turned on.

---

## Paper / Purpur

1. Drop `jarvis-paper-<version>.jar` and `Citizens.jar` into `plugins/`. Add
   [WorldEdit](https://enginehub.org/worldedit/) if you want schematics.
2. Start the server. Jarvis writes:

```
plugins/
└── Jarvis/
    ├── config.yml        the main configuration
    ├── databases.yml     database settings, rarely edited
    ├── data.yml          homes, chests, portal sightings
    ├── jarvis.db         SQLite, created on first run
    ├── models/           speech models, once voice is on
    └── schematics/       .schem files for the library
```

3. `/jarvis bell`, ring it, **Admin → AI setup**, pick a provider and paste
   your key or your Ollama address. It takes effect at once.

> Jarvis refuses to load without Citizens, and says so in the console.

Optional, and worth it for experience memory: `ollama pull nomic-embed-text`
on your Ollama box. Without it memory still works, on the weaker
keyword-matching path.

## Fabric

1. Drop `jarvis-fabric-<version>.jar` and Fabric API into `mods/`.
2. Start the server, or open the world. The config appears in
   `config/jarvis/`.
3. The bell and the menu work the same: `/jarvis bell`, **Admin → AI setup**.

## NeoForge

1. Drop `jarvis-neoforge-<version>.jar` into `mods/`.
2. Start the server, or open the world. The config appears in
   `config/jarvis/`.
3. Same as above.

See [Fabric & NeoForge](Fabric-and-NeoForge) for what differs on the mods.

---

## First run

```
/jarvis bell        get the controller bell
/jarvis summon      bring him out
/jarvis version     what is actually running
/jarvis ai          which provider answered, and how fast
/jarvis help        every command
```

Then talk to him in chat — no command prefix needed:

```
jarvis, mine some diamonds
jarvis, build me a small stone house
jarvis, take me home
```

---

## Updating

1. Stop the server.
2. Replace the jar. On Paper the file is now `jarvis-paper-<version>.jar`;
   delete any older `jarvis-<version>.jar` so you do not load both.
3. Start. New config keys are added to your file with their comments intact;
   your settings, database and data are kept.

---

## Building from source

```bash
git clone https://github.com/iamgadgetman/jarvis.git
cd jarvis
mvn clean package -DskipTests          # the Paper plugin
cd jarvis-fabric   && gradle build     # the Fabric mod   (JDK 25)
cd ../jarvis-neoforge && gradle build  # the NeoForge mod (JDK 25)
```

The plugin lands at `jarvis-paper/target/jarvis-paper-<version>.jar`, the mods
at `jarvis-<loader>/build/libs/`. The mods need Fabric's, NeoForge's and
Mojang's repositories reachable; GitHub Actions builds all three on every push
if your network cannot.

---

## Permissions

| Permission | Default | What it allows |
|---|---|---|
| `jarvis.use` | everyone | Every standard command |
| `jarvis.admin` | ops | Admin commands, the admin menu page, dangerous butler actions |
| `jarvis.menu.use` | everyone | The bell menu |

On Fabric and NeoForge there are no permission nodes: **admin means
server operator**, and everything else is open to all players.

With LuckPerms:

```bash
lp group default permission set jarvis.use true
lp group admin   permission set jarvis.admin true
```
