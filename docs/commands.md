# Commands

*[← README](../README.md) · [Features](features.md) · [Commands](commands.md) · [Configuration](configuration.md) · [Troubleshooting](troubleshooting.md)*

---

| Command | Description | Permission |
|---|---|---|
| `/jarvis summon` | Summon your Jarvis NPC | `jarvis.use` |
| `/jarvis dismiss` | Dismiss him; he keeps issued gear, hands back the rest | `jarvis.use` |
| `/jarvis return` | Recall him to your side | `jarvis.use` |
| `/jarvis follow` | Follow you and carry loot | `jarvis.use` |
| `/jarvis stop` | Stop current NPC task | `jarvis.use` |
| `/jarvis version` | What is actually running | `jarvis.use` |
| `/jarvis help` | The full list, in game | `jarvis.use` |
| `/jarvis mine [ore]` | Start mining (optional ore type) | `jarvis.use` |
| `/jarvis mine here` | Dig a complete torch-lit branch mine | `jarvis.use` |
| `/jarvis dig ...` | Sink a shaft | `jarvis.use` |
| `/jarvis tunnel [n\|s\|e\|w] [length]` | Drive a 3×3 passage (Peerless rank) | `jarvis.use` |
| `/jarvis guard [passive\|defensive\|aggressive]` | Bodyguard mode | `jarvis.use` |
| `/jarvis attack` | Weapons free | `jarvis.use` |
| `/jarvis watch` | Night watch: hold this position | `jarvis.use` |
| `/jarvis patrol add\|start\|clear` | Guard a waypoint circuit | `jarvis.use` |
| `/jarvis farm [crop]` | Harvest and replant the field once | `jarvis.use` |
| `/jarvis tend [crop]` | Stay on as a farmhand | `jarvis.use` |
| `/jarvis chop [n]` | Fell trees, replant saplings | `jarvis.use` |
| `/jarvis fish` | A spot of fishing | `jarvis.use` |
| `/jarvis light [radius] [type] [spacing]` | Spawn-proof an area | `jarvis.use` |
| `/jarvis dance` | The performance | `jarvis.use` |
| `/jarvis chest` | Register the chest you are looking at | `jarvis.use` |
| `/jarvis deposit` | Deliver his cargo to that chest | `jarvis.use` |
| `/jarvis loot` | Open NPC inventory | `jarvis.use` |
| `/jarvis clearloot` | Clear NPC inventory | `jarvis.use` |
| `/jarvis home set`, `/jarvis home` | Save a home point; be escorted back | `jarvis.use` |
| `/jarvis recover` | Retrieve your death drops | `jarvis.use` |
| `/jarvis bell` | Get the controller bell | `jarvis.use` |
| `/jarvis report` | Server status briefing | `jarvis.use` |
| `/jarvis duties`, `/jarvis duty add <min> <msg>` | Standing scheduled duties | `jarvis.use` |
| `/jarvis rank` | Service record and what he has earned | `jarvis.use` |
| `/jarvis queue <order>`, `/jarvis queue list\|clear` | Line up an order for when he is free | `jarvis.use` |
| `/jarvis ask <question>` | Ask him anything | `jarvis.use` |
| `/jarvis heal` | Heal yourself | `jarvis.use` |
| `/jarvis feed` | Feed yourself | `jarvis.use` |
| `/jarvis schematic list\|paste\|save\|rotate\|scan` | The schematic library (Paper, WorldEdit) | `jarvis.use` |
| `/jarvis schematic litematic\|convert\|convertall` | Litematica files, converted to `.schem` | `jarvis.use` |
| `/jarvis time <day\|night>` | Set time | `jarvis.admin` |
| `/jarvis weather <clear\|rain\|storm>` | Set weather | `jarvis.admin` |
| `/jarvis reload` | Re-read config.yml | `jarvis.admin` |
| `/jarvis debug` | Provider, model, memory and subsystem status | `jarvis.admin` |
| `/jarvis export-dataset` | Dump intent and build pairs as JSONL | `jarvis.admin` |
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
| `/jarvis portal` | Lead you to the nearest portal he has seen | `jarvis.use` |
| `/jarvis portal where` | Where this side's portal comes out on the other | `jarvis.use` |
| `/jarvis portals` | Portals he has noted in this world | `jarvis.use` |
| `/jarvis quiet` | Mute (or unmute) his idle remarks, for you | `jarvis.use` |
| `/jarvis voice` | Where voice stands: voice chat, the gate, what he last heard, the speech server | `jarvis.use` |
| `/jarvis voice enable\|disable\|engine <e>\|endpoint <url>\|gate <g>\|speak on\|off\|test` | Set up voice from the console; the bell menu's Admin > Voice setup does the same | `jarvis.admin` |
| `/jarvis voice bench [threads]`, `/jarvis voice threads <n>` | Time the embedded recogniser at several thread counts and name the fastest; keep a count (0 = automatic) | `jarvis.admin` |
| `/jarvis ai [status]` | Which AI providers are on, and their models | `jarvis.use` |
| `/jarvis ai enable\|disable\|key\|endpoint\|model\|models\|test ...` | Set up a provider from the console | `jarvis.admin` |
| `/jarvis <anything>` | Natural language — Jarvis figures it out | `jarvis.use` |

On Fabric and NeoForge there are no permission nodes: `jarvis.admin` means
server operator, and everything else is open to all players. The full wiki
page is [Commands & Permissions](https://github.com/iamgadgetman/jarvis/wiki/Commands-and-Permissions).

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
