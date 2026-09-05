# Jarvis Commands

See also: [README](../README.md) • [Features](features.md) • [Troubleshooting](troubleshooting.md)

## Command Reference

| Command | Description | Permission |
|---|---|---|
| `/jarvis bell` | Get the Jarvis controller bell | `jarvis.use` |
| `/jarvis summon` | Summon your Jarvis NPC | `jarvis.use` |
| `/jarvis dismiss` | Dismiss your Jarvis NPC | `jarvis.use` |
| `/jarvis debug` | Show provider, model, and memory status | `jarvis.use` |
| `/jarvis ai` | Show AI routing and provider health | `jarvis.use` |
| `/jarvis mine [ore]` | Start mining (optional ore type) | `jarvis.use` |
| `/jarvis mine here` | Start branch mining at your current location | `jarvis.use` |
| `/jarvis stop` | Stop current NPC task | `jarvis.use` |
| `/jarvis follow` | Return NPC to your side | `jarvis.use` |
| `/jarvis chest` | Register the looked-at chest as Jarvis deposit chest | `jarvis.use` |
| `/jarvis deposit` | Deposit carried loot into the registered chest | `jarvis.use` |
| `/jarvis attack` | Attack nearby mobs | `jarvis.use` |
| `/jarvis guard [passive&#124;defensive&#124;aggressive]` | Enable guard mode with a stance | `jarvis.use` |
| `/jarvis watch` | Hold a watch post and clear nearby threats | `jarvis.use` |
| `/jarvis loot` | Open NPC inventory | `jarvis.use` |
| `/jarvis clearloot` | Clear NPC inventory | `jarvis.use` |
| `/jarvis heal` | Heal yourself | `jarvis.admin` |
| `/jarvis feed` | Feed yourself | `jarvis.admin` |
| `/jarvis time <day&#124;night>` | Set time | `jarvis.admin` |
| `/jarvis weather <clear&#124;rain&#124;storm>` | Set weather | `jarvis.admin` |
| `/jarvis build <description>` | Build it — pastes a matching schematic, or has the AI design one | `jarvis.use` |
| `/jarvis build undo` | Revert the last build | `jarvis.use` |
| `/jarvis build cancel` | Stop the build in progress | `jarvis.use` |
| `/jarvis build <wall&#124;floor&#124;pillar&#124;cube> [size]` | Simple shapes, no AI | `jarvis.use` |
| `/jarvis paste <name>` | Paste a schematic by name, no AI | `jarvis.use` |
| `/jarvis light [radius] [type] [spacing]` | Light an area to reduce mob spawns | `jarvis.use` |
| `/jarvis farm [crop]` | Harvest and replant crops | `jarvis.use` |
| `/jarvis tend [crop]` | Continue working as a farmhand | `jarvis.use` |
| `/jarvis chop [n]` | Chop trees and collect logs | `jarvis.use` |
| `/jarvis fish` | Fish from nearby water | `jarvis.use` |
| `/jarvis dance` | Trigger Jarvis dance emote/performance | `jarvis.use` |
| `/jarvis patrol add` | Add a patrol waypoint | `jarvis.use` |
| `/jarvis patrol` | Start patrol route | `jarvis.use` |
| `/jarvis recover` | Recover your death drops | `jarvis.use` |
| `/jarvis home set` | Save a home point for escorting | `jarvis.use` |
| `/jarvis home` | Escort you to saved home point | `jarvis.use` |
| `/jarvis report` | Show server and butler status report | `jarvis.use` |
| `/jarvis duty add <minutes> <message>` | Add recurring duty broadcast | `jarvis.admin` |
| `/jarvis duties` | List configured duty broadcasts | `jarvis.admin` |
| `/jarvis requests` | List pending player requests | `jarvis.admin` |
| `/jarvis approve <id>` | Approve a player item request | `jarvis.admin` |
| `/jarvis deny <id>` | Deny a player item request | `jarvis.admin` |
| `/jarvis confirm` | Confirm your pending dangerous action prompt (for actions you are permitted to run) | `jarvis.use` |
| `/jarvis cancel` | Cancel your pending dangerous action prompt | `jarvis.use` |
| `/jarvis <anything>` | Natural language — Jarvis figures it out | `jarvis.use` |

Dangerous prompts are tied to actions you are already authorized to run (typically `jarvis.admin` for admin actions).

You can also just **type in chat** (no command needed) — when `natural-language.require-prefix` is `false`, Jarvis can respond to mentions/recognized keywords; when it's `true`, start with the prefix (for example `jarvis ...`).

## Natural Language Examples

```text
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
