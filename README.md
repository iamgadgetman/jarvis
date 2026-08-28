# Jarvis — AI-Powered Minecraft Butler Plugin

**Version 0.7.0** | Paper / Purpur | Java 17 bytecode | Minecraft 1.21.11 – 26.2 (26.x servers need Java 25)

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
| `/jarvis build <description>` | Start a build | `jarvis.use` |
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
- [Citizens 2](https://www.spigotmc.org/resources/citizens.13811/) plugin
- [WorldEdit](https://enginehub.org/worldedit/) (optional — for schematic pasting)
- At least one AI API key (or local Ollama)

### Steps
1. Drop `jarvis-0.7.0.jar` into your `plugins/` folder
2. Drop `Citizens.jar` and `WorldEdit.jar` into `plugins/` (if not already present)
3. Start the server — Jarvis will generate `plugins/Jarvis/config.yml`
4. Add your AI API key(s) to `config.yml`
5. Restart or `/reload confirm`

---

## Configuration

```yaml
# plugins/Jarvis/config.yml

ai:
  provider: openai          # openai | claude | grok | gemini | ollama
  openai-api-key: "sk-..."
  claude-api-key: "sk-ant-..."
  grok-api-key: "..."
  gemini-api-key: "..."
  ollama-url: "http://localhost:11434"
  ollama-model: "llama3"
  model: "gpt-4o"           # model name for primary provider
  fallback-order:           # auto-failover order
    - openai
    - claude
    - grok
    - gemini
    - ollama

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

---

## Permissions

| Permission | Description | Default |
|---|---|---|
| `jarvis.use` | Basic Jarvis commands | `true` |
| `jarvis.admin` | Admin-only commands and actions | `op` |

---

## Building from Source

```bash
git clone https://github.com/iamgadgetman/jarvis.git
cd jarvis
mvn clean package -DskipTests
# Output: target/jarvis-0.7.0.jar
```

Requires Citizens and WorldEdit JARs on the Maven classpath as configured in `pom.xml`.

---

## Changelog

### v0.0.9
- **Console command execution** — Jarvis can run server console commands (always confirmation-gated)
- **New admin actions** — `clear_mobs`, `clear_drops`, `save_world`, `set_difficulty`, `announce_all`, `schedule_broadcast`
- **Player request system** — players ask for items; admins approve/deny via `/jarvis requests`
- **Auto-greet** — AI-generated welcome message when players join
- **Death commentary** — Jarvis comments on player deaths with AI-generated wit
- **TPS monitor** — warns admins when server performance degrades
- **Ore targeting** — `/jarvis mine diamond` now correctly targets only diamond ore
- **Inventory cleanup** — Jarvis no longer picks up cobblestone/dirt/gravel while mining
- **Multi-word commands** — `/jarvis set time to night` and similar now work
- **Schematic fuzzy matching** — paste schematics without needing the exact filename
- **Right-click menu fix** — NPC right-click GUI now reliably opens (switched to Citizens NPCRightClickEvent)
- **Mining reliability fix** — block breaking is now synchronous and correctly detected

### v0.0.8
- Initial public release
- AI natural language processing (OpenAI, Claude, Grok, Gemini, Ollama)
- Citizens NPC companion with follow, mine, attack, build behaviors
- 5-phase mining state machine (SEARCHING → MOVING → MINING → COLLECTING → RETURNING)
- WorldEdit schematic pasting
- Quest system
- Clickable confirmation for dangerous actions
- Multi-backend AI failover

### v0.0.7
- Improved NPC movement
- Ollama support for local AI
- Auto AI provider switching

### v0.0.6
- Mining improvements
- Branch mining
- Statistics system
- Quest templates

---

## License

MIT License — see [LICENSE](LICENSE) for details.

---

*Built with Citizens, WorldEdit, Adventure API, and a lot of caffeine.*
