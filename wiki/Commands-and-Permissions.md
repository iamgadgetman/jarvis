# Commands & Permissions

Everything is under `/jarvis`. You can also just **type in chat**: a line that
mentions Jarvis, or that reads as an order, goes to the same place. `/jarvis
help` prints this list in game.

---

## The butler

| Command | What it does |
|---|---|
| `/jarvis summon` | Bring Jarvis to you |
| `/jarvis dismiss` | Send Jarvis away; he keeps issued gear, hands back the rest |
| `/jarvis return` | Recall him to your side |
| `/jarvis follow` | Follow you and carry loot |
| `/jarvis stop` | Stop the current task |
| `/jarvis version` | What is actually running |
| `/jarvis help` | The full list, in game |

## Mining

| Command | What it does |
|---|---|
| `/jarvis mine [ore]` | Hunt nearby ores, or a named one (`diamond`, `iron`, `ancient_debris`) |
| `/jarvis mine here` | Dig a complete torch-lit branch mine |
| `/jarvis dig ...` | Sink a shaft |
| `/jarvis tunnel [n\|s\|e\|w] [length]` | Drive a 3×3 passage — Peerless rank and above |

## Combat

| Command | What it does |
|---|---|
| `/jarvis guard [passive\|defensive\|aggressive]` | Bodyguard mode |
| `/jarvis attack` | Weapons free |
| `/jarvis watch` | Night watch: hold this position and clear spawns |
| `/jarvis patrol add\|start\|clear` | Guard a waypoint circuit |

## Groundskeeping

| Command | What it does |
|---|---|
| `/jarvis farm [crop]` | Harvest and replant the field once |
| `/jarvis tend [crop]` | Stay on as a farmhand |
| `/jarvis chop [n]` | Fell trees and replant saplings |
| `/jarvis fish` | A spot of fishing |
| `/jarvis light [radius] [type] [spacing]` | Spawn-proof an area (`torch`, `end_rod`, `lantern`) |
| `/jarvis dance` | The performance |

## Carrying and the household

| Command | What it does |
|---|---|
| `/jarvis chest` | Register the chest you are looking at |
| `/jarvis deposit` | Deliver his cargo to that chest |
| `/jarvis loot` | Open his inventory |
| `/jarvis clearloot` | Drop everything he carries |
| `/jarvis home set` | Save this spot as home |
| `/jarvis home` | Be escorted home, torch-lighting the road |
| `/jarvis recover` | Travel to your death point and bring your things back |
| `/jarvis bell` | Get the controller bell |

## Building

| Command | What it does |
|---|---|
| `/jarvis build <description>` | Design and build it |
| `/jarvis build undo` | Revert the last build |
| `/jarvis build cancel` | Stop the build in progress |
| `/jarvis build wall\|floor\|pillar\|cube [size]` | Simple shapes, no AI |

Paper with WorldEdit also has the schematic library:

| Command | What it does |
|---|---|
| `/jarvis schematic list` | What is in the library |
| `/jarvis schematic paste <name>` | Paste one by name |
| `/jarvis schematic save <name>` | Save your clipboard |
| `/jarvis schematic rotate <name> <deg>` | Paste rotated |
| `/jarvis schematic scan` | Rescan the folder |
| `/jarvis schematic litematic` / `convert <name>` / `convertall` | Litematica files, converted to `.schem` |

## The estate

| Command | What it does |
|---|---|
| `/jarvis report` | The briefing: TPS, players, his cargo, pending requests |
| `/jarvis duties` | Standing scheduled duties |
| `/jarvis duty add <minutes> <message>` | Add one; survives restarts |
| `/jarvis rank` | His service record and what he has earned |
| `/jarvis queue <order>` | Line up an order for when he is free |
| `/jarvis queue list\|clear` | Review or tear up the list |
| `/jarvis quiet` | Mute his idle remarks, for you |

## Portals

| Command | What it does |
|---|---|
| `/jarvis portal` | Lead you to the nearest portal he has seen |
| `/jarvis portal where` | Where this side's portal comes out on the other |
| `/jarvis portals` | Every portal he has noted in this world |

## Voice

| Command | What it does |
|---|---|
| `/jarvis voice` | Every link of the chain, and how long the last order took |
| `/jarvis voice enable\|disable` | Turn listening on or off |
| `/jarvis voice engine <embedded\|server>` | Where speech runs |
| `/jarvis voice gate <whisper\|always\|wake-word>` | How he knows he is being addressed |
| `/jarvis voice speak <on\|off>` | Whether he answers out loud |
| `/jarvis voice bench [threads]` | Time the recogniser here, and name the fastest thread count |
| `/jarvis voice threads <n>` | Keep a thread count; 0 is automatic |
| `/jarvis voice endpoint <url>` | The speech server, when the engine is `server` |
| `/jarvis voice test` | The status report again |

All of these except `status` need `jarvis.admin`. See [Voice](Voice).

## AI

| Command | What it does |
|---|---|
| `/jarvis ai [status]` | Which providers are on, their models, and their health |
| `/jarvis ai enable\|disable <provider>` | Switch one on or off |
| `/jarvis ai key <provider> <key>` | Set an API key |
| `/jarvis ai endpoint <provider> <url>` | Set an address, for Ollama |
| `/jarvis ai model <provider> <model>` | Choose the model |
| `/jarvis ai models` | What your Ollama server has pulled |
| `/jarvis ai test <provider>` | One small request, past the routing |

The setting commands need `jarvis.admin`. See [AI Providers](AI-Providers).

## Requests and confirmation

| Command | What it does | Permission |
|---|---|---|
| `/jarvis confirm` | Confirm a pending dangerous action | `jarvis.use` |
| `/jarvis cancel` | Cancel it | `jarvis.use` |
| `/jarvis requests` | Pending item requests | `jarvis.admin` |
| `/jarvis approve <id>` / `deny <id>` | Decide one | `jarvis.admin` |

## Admin

| Command | What it does |
|---|---|
| `/jarvis reload` | Re-read `config.yml`; voice and AI settings take effect at once |
| `/jarvis debug` | Provider, model, memory and subsystem status |
| `/jarvis export-dataset` | Dump intent and build pairs as JSONL |

## Anything else

| Command | What it does |
|---|---|
| `/jarvis ask <question>` | Ask him anything |
| `/jarvis <anything>` | Natural language — he works it out |

---

## Permissions

| Permission | Default | What it allows |
|---|---|---|
| `jarvis.use` | everyone | Every standard command |
| `jarvis.admin` | ops | Admin commands, the admin menu page, dangerous butler actions |
| `jarvis.menu.use` | everyone | The bell menu |

On **Fabric and NeoForge** there are no permission nodes: admin means server
operator, and everything else is open to all players.

With LuckPerms:

```bash
lp user <player> permission set jarvis.use true
lp group admin   permission set jarvis.admin true
lp user <player> permission unset jarvis.use
```

---

## Dangerous actions

Some actions always ask first, whoever requested them: giving items,
enchanting, potion effects, healing, feeding, game mode, teleports, time,
weather, game rules, difficulty, broadcasts, clearing mobs or drops, saving
the world, console commands, warps, pasting schematics, and permission
changes.

Jarvis describes what he is about to do and waits:

```
Jarvis: I'm about to give you 64 diamonds.
        /jarvis confirm to proceed, /jarvis cancel to abort.
```

The window expires after `confirmation.timeout-seconds`, 30 by default.
Console commands always require `jarvis.admin` as well, by design. On an
Ollama-only server they are refused outright unless
`ai.reduced-mode.allow-risky-actions` is turned on.
