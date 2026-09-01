# Jarvis — AI-Powered Minecraft Butler Plugin

**Version 0.8.6** | Paper / Purpur | Java 17 bytecode | Minecraft 1.21.11 – 26.2 (26.x servers need Java 25)

Jarvis is a feature-rich AI companion plugin that spawns a Citizens NPC who follows you, fights for you, mines for you, builds for you — and understands natural language via OpenAI, Claude, Grok, Gemini, or a local Ollama model.

---

## Features

### AI Natural Language Understanding
Speak to Jarvis in plain English — in chat or via command. Jarvis routes your request through an AI model, picks the right action, and executes it (with a witty response).

Supports multiple AI backends with **tiered, Ollama-first routing** (new in 0.3.0):
- Light work (chat intents, banter) runs on your local Ollama box when configured
- Heavy work (build planning) prefers your cloud provider (Claude, OpenAI, Grok, Gemini)
- Per-provider health tracking with automatic failover between tiers
- **Ollama-only "reduced mode"**: fully usable with zero cloud keys — freeform build
  planning turns off and risky console actions require opt-in
- Jarvis remembers your recent conversation (butler memory)
- `/jarvis ai` shows routes, provider health, and who answered last

### NPC Companion
- **Summon / Dismiss** — Jarvis appears at your side or disappears on demand
- **Follow** — stays close and teleports if you get too far away
- **Right-click Menu** — opens a GUI for quick access to all Jarvis actions
- **Custom skin & name** — configurable in `config.yml`

### Branch Mining (new in 0.2.0)
- `/jarvis mine here` — Jarvis digs a full torch-lit branch mine: staircase to diamond level, main gallery, branch tunnels on a grid
- Harvests every ore the tunnels expose, follows veins, seals lava pockets with cobblestone
- The mine stays lit and walkable for you afterwards

### Butler Services (new in 0.2.0)
- `/jarvis follow` — trails behind you, picking up loot as you go
- `/jarvis chest` — register the chest you're looking at as his deposit chest
- `/jarvis deposit` — he carries the loot over and unloads it; auto-delivers when his bags fill mid-mine

### Smart Mining (reworked in 0.1.0)
- **Real movement** — Citizens A* pathfinding, no more teleport-hopping
- **Real mining** — vanilla break timing with arm swings and crack animations (Citizens BlockBreaker)
- **Digs like a player** — when a path is blocked, Jarvis mines through the obstruction instead of warping
- **Async ore scanning** — chunk-snapshot scans off the main thread, no TPS hit
- `mine` — mines the nearest ore; `mine diamond` / `mine iron` / `mine ancient debris` — targets a type
- Understands: diamond, emerald, gold, iron, copper, redstone, lapis, quartz, coal, netherite/ancient debris
- Collects drops automatically, filters out junk (cobblestone, dirt, gravel, etc.)

### Defender (reworked in 0.4.0)
- `/jarvis guard [passive|defensive|aggressive]` — bodyguard with stances; anchor-and-leash combat (he never chases into the night)
- `/jarvis watch` — night watch: holds a fixed post, clears spawns, returns after every fight
- Threat callouts: "Creeper, behind you, sir!" for hostiles outside your view
- Diamond sword in guard mode, creeper-priority targeting, retaliation memory

### Groundskeeping (new in 0.7.0)
- `/jarvis farm [crop]` / `/jarvis tend [crop]` — harvest & replant the field once, or stay on as a farmhand
- `/jarvis chop [n]` — fells whole trees (timber cascade!), collects logs, replants saplings
- `/jarvis fish` — casts from the water's edge with real sounds and vanilla-ish loot odds
- `/jarvis dance` — the performance; short victory bops on big milestones too
- `/jarvis patrol add` + `/jarvis patrol` — he walks a saved waypoint circuit as an armed sentry
- Waves and greets you when you return; glances at what you're doing when idle

### Butler Services (new in 0.6.0)
- `/jarvis recover` — he fetches your death drops: travels to where you died, collects everything, brings it back
- `/jarvis home set` + `/jarvis home` — saved home point; he escorts you back, torch-lighting the road and waiting when you lag behind
- Supply handoff — hungry or your tool nearly broken? He hands over food or a spare from his own bags

### Steward (new in 0.5.0)
- `/jarvis report` — the briefing: TPS/ms-tick with health coloring, players online, his cargo, pending requests; compact version on join
- `/jarvis duty add <minutes> <message>` — standing broadcasts that survive restarts; `/jarvis duties` to review
- "Jarvis, build me a house" picks the best schematic from your library (works even on local-only AI); freeform AI building is just the fallback

### Building Assistant
- Describe a structure in natural language; Jarvis plans and builds it block by block
- Paste WorldEdit schematics by name (fuzzy matching — no need for exact filenames)

### Lamplighter (new in 0.8.3)
`/jarvis light [radius] [type] [spacing]` — Jarvis lights an area against mob
spawns, on the actual spawn rule (hostiles spawn at block light 0) rather than a
guess.
- Torches, end rods or lanterns; ground placement by default, `lighting.placement: wall` for walls
- Skips spots already bright enough, and drops sea lanterns for grid points that land in shallow water
- Works from chat: *"jarvis, light this place up"*
- He swims now, with a lifeguard monitor watching for a submerged NPC

### Experience Memory (new in 0.8.0)
Jarvis remembers how past builds turned out and shows the AI the plans that
worked for similar requests.
- Labels come from what you do, not a rating prompt: a build you let finish is a
  success, one you cancel or undo is not
- Retrieval matches your wording first, then re-ranks on where you are — the
  same request underground and on the surface pulls up different examples
- Embeddings run on your Ollama box and never fall back to a paid provider, so
  the feature costs nothing to run. If Ollama is down it falls back to keyword
  matching rather than failing
- **Ollama-only servers:** freeform build planning is normally disabled, but it
  unlocks itself once 20 successful builds are remembered — the examples carry
  the load the block was there to avoid
- `/jarvis debug` shows how many builds are stored and whether the unlock has fired

### Inventory Management
- Jarvis has his own NPC inventory
- Right-click him or use `/jarvis loot` to browse / take items
- `/jarvis clearloot` to clear his inventory

### Admin Butler Actions (v0.0.9)
Jarvis can execute powerful server management actions, all gated behind a click-to-confirm prompt for dangerous operations:

| Action | Description |
|---|---|
| `give_item` | Give a player an item |
| `enchant` | Enchant a player's held item |
| `potion_effect` | Apply a potion effect |
| `heal` / `feed` | Restore player health or hunger |
| `set_gamemode` | Change a player's game mode |
| `teleport` | Teleport a player |
| `set_time` | Set the world time |
| `set_weather` | Set the weather |
| `set_gamerule` | Change a game rule |
| `set_difficulty` | Change the world difficulty |
| `broadcast` / `announce_all` | Send a message to all players (chat + title) |
| `schedule_broadcast` | Schedule a recurring or delayed server message |
| `clear_mobs` | Remove mobs near a player or in a radius |
| `clear_drops` | Remove all item drops from the world |
| `save_world` | Force save all worlds |
| `console_command` | Execute a single server console command |
| `console_commands` | Execute multiple server console commands |
| `warp` | Warp a player to a named location |
| `paste_schematic` | Paste a WorldEdit schematic |
| `discord_broadcast` | Send a message to a Discord webhook |
| `lp_group_add` / `lp_group_remove` | Manage LuckPerms groups |

### Player Request System (v0.0.9)
Players can ask Jarvis for items ("Jarvis, can I get 64 iron ingots?"). Jarvis queues the request and notifies online admins. Admins review and approve or deny with a single click.

### Butler Events (v0.0.9)
- **Auto-Greet** — Jarvis greets players when they join with an AI-generated personalized welcome
- **Death Commentary** — Jarvis provides witty AI-generated commentary when a player dies
- **TPS Monitor** — Jarvis warns admins in-game when server TPS drops below a configurable threshold

---

## Commands

| Command | Description | Permission |
|---|---|---|
| `/jarvis summon` | Summon your Jarvis NPC | `jarvis.use` |
| `/jarvis dismiss` | Dismiss your Jarvis NPC | `jarvis.use` |
| `/jarvis mine [ore]` | Start mining (optional ore type) | `jarvis.use` |
| `/jarvis stop` | Stop current NPC task | `jarvis.use` |
| `/jarvis follow` | Return NPC to your side | `jarvis.use` |
| `/jarvis attack` | Attack nearby mobs | `jarvis.use` |
| `/jarvis loot` | Open NPC inventory | `jarvis.use` |
| `/jarvis clearloot` | Clear NPC inventory | `jarvis.use` |
| `/jarvis heal` | Heal yourself | `jarvis.use` |
| `/jarvis feed` | Feed yourself | `jarvis.use` |
| `/jarvis time <day\|night>` | Set time | `jarvis.admin` |
| `/jarvis weather <clear\|rain\|storm>` | Set weather | `jarvis.admin` |
| `/jarvis build <description>` | Build it — pastes a matching schematic, or has the AI design one | `jarvis.use` |
| `/jarvis build undo` | Revert the last build | `jarvis.use` |
| `/jarvis build cancel` | Stop the build in progress | `jarvis.use` |
| `/jarvis build wall\|floor\|pillar\|cube [size]` | Simple shapes, no AI | `jarvis.use` |
| `/jarvis paste <name>` | Paste a schematic by name, no AI | `jarvis.use` |
| `/jarvis requests` | List pending player requests | `jarvis.admin` |
| `/jarvis approve <id>` | Approve a player item request | `jarvis.admin` |
| `/jarvis deny <id>` | Deny a player item request | `jarvis.admin` |
| `/jarvis confirm` | Confirm a pending dangerous action | `jarvis.use` |
| `/jarvis cancel` | Cancel a pending dangerous action | `jarvis.use` |
| `/jarvis <anything>` | Natural language — Jarvis figures it out | `jarvis.use` |

You can also just **type in chat** (no command needed) — if your message mentions Jarvis or contains a recognized keyword, he'll respond.

---

## Natural Language Examples

```
jarvis, summon yourself
mine some diamonds for me
jarvis give me a diamond sword
set the difficulty to hard
clear all mobs within 50 blocks of me
make it daytime and clear weather
give Steve 64 iron ingots
broadcast "Server restart in 5 minutes" to everyone
run /op Steve on the console
can I get some food please
```

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

## Configuration

```yaml
# plugins/Jarvis/config.yml

ai:
  provider: auto            # openai | claude | grok | gemini | ollama | auto
  provider-priority:        # auto-failover order; ollama first is free and local
    - ollama
    - claude
    - openai
    - grok
    - gemini
  claude:
    api-key: ""
    model: claude-haiku-4-5
  openai:
    api-key: ""
    model: gpt-5.6-terra
  ollama:
    endpoint: "http://localhost:11434"
    model: mistral

memory:                     # experience memory (0.8.0)
  enabled: true
  embedding-model: "nomic-embed-text"   # ollama pull nomic-embed-text
  max-examples-in-prompt: 3
  # Ollama-only servers unlock freeform builds after this many successes
  min-successes-for-reduced-mode-builds: 20
  # Undoing a build within this window marks its plan as failed
  negative-signal-window-minutes: 10

npc:
  name: "Jarvis"
  skin: "Jarvis"            # player name whose skin to use
  follow-distance: 3.0
  max-distance: 30.0

mining:
  search-radius: 20
  max-ores: 10
  tool: DIAMOND_PICKAXE

natural-language:
  enabled: true
  prefix: "jarvis"
  require-prefix: false     # if true, chat must start with "jarvis"

butler:
  auto-greet: true
  death-commentary: true
  tps-warn-threshold: 18.0  # warn admins if TPS drops below this

confirmation:
  timeout-seconds: 30       # how long dangerous action prompts stay active

discord:
  webhook-url: ""           # optional — for discord_broadcast action

permissions:
  admin-permission: "jarvis.admin"
```

### Getting an AI API key

Pick one provider and put its key in `config.yml`. Jarvis will fall back through
the others in `fallback-order` if the primary fails.

| Provider | Where to get a key | Key looks like |
|---|---|---|
| **Claude** (recommended) | [console.anthropic.com](https://console.anthropic.com/) → API Keys | `sk-ant-...` |
| **OpenAI** | [platform.openai.com](https://platform.openai.com/) → API Keys | `sk-...` |
| **xAI Grok** | [x.ai](https://x.ai/) → request API access | — |
| **Google Gemini** | [aistudio.google.com](https://aistudio.google.com/) | — |
| **Ollama** | none — runs locally, see [ollama.ai](https://ollama.ai) | n/a |

Ollama needs no key and no account: install it, `ollama pull mistral`, and point
`ollama-url` at it. It is the cheapest option and keeps everything on your own
hardware, at the cost of slower and less accurate responses.

---

## Permissions

| Permission | Description | Default |
|---|---|---|
| `jarvis.use` | Basic Jarvis commands | `true` |
| `jarvis.admin` | Admin-only commands and actions | `op` |

---

## Troubleshooting

**"Citizens not found"** — Citizens is required, not optional. Check it is in
`plugins/`, that its version matches your server, and that the console shows it
loading before Jarvis.

**"WorldEdit not found"** — only building and schematic features need WorldEdit.
Everything else works without it. Verify with `/we version`.

**"AI API error"** — work through these in order:

1. Check the key in `plugins/Jarvis/config.yml` — no stray quotes or spaces, and
   it belongs to the provider named in `ai.provider`.
2. Confirm the key is active and has quota in your provider's console.
3. Test connectivity from the server itself:
   ```bash
   curl https://api.anthropic.com/v1/messages \
     -H "x-api-key: YOUR_KEY" \
     -H "anthropic-version: 2023-06-01" \
     -H "content-type: application/json" \
     -d '{"model":"claude-opus-5","max_tokens":64,
          "messages":[{"role":"user","content":"test"}]}'
   ```
4. Check the host allows outbound HTTPS — some hosts block it by default.

**Natural language not responding** — confirm `natural-language.enabled: true`,
try the explicit prefix (`jarvis <command>`), and check console for errors.

**Building not working** — verify WorldEdit loaded, that you have build rights
in the area, and try something simple first (`/jarvis build wall`). AI JSON
parse errors show in the console.

**"Database init error"** — `plugins/Jarvis/` must be writable. Failing that,
delete `database.db` and restart, and check free disk space.

`/jarvis debug` prints the live provider, model and feature state — start there.

---

## Updating

1. Back up your config: `cp plugins/Jarvis/config.yml ~/jarvis-config-backup.yml`
2. Stop the server
3. Replace the jar: `rm plugins/jarvis-*.jar && cp Jarvis-<new>.jar plugins/`
4. Start the server

New config keys are added automatically and your existing settings are kept.

---

## Performance

AI calls are async and never block the main thread. Typical cost:

| Operation | Latency |
|---|---|
| Natural language | ~200–500 ms |
| AI building design | ~1–3 s, then progressive placement |

Block placement is throttled to 50 per tick so large builds do not stall the
server, natural language has a 2-second cooldown per player, and AI caching
costs roughly 50 MB of heap.

On a busy server (50+ players), set `natural-language.require-prefix: true` so
Jarvis only parses chat aimed at him. On a small server, leave it `false` for a
more natural feel.

---

## Building from Source

```bash
git clone https://github.com/iamgadgetman/jarvis.git
cd jarvis
mvn clean package -DskipTests
# Output: target/jarvis-<version>.jar
```

Requires Citizens and WorldEdit JARs on the Maven classpath as configured in `pom.xml`.

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full history, and the
[releases page](https://github.com/iamgadgetman/jarvis/releases) for downloads.

---

## License

MIT License — see [LICENSE](LICENSE) for details.

---

*Built with Citizens, WorldEdit, Adventure API, and a lot of caffeine.*
