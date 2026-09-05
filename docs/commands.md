# Jarvis Commands

See also: [README](../README.md) • [Features](features.md) • [Troubleshooting](troubleshooting.md)

## Command Reference

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
| `/jarvis time <day or night>` | Set time | `jarvis.admin` |
| `/jarvis weather <clear, rain, or storm>` | Set weather | `jarvis.admin` |
| `/jarvis build <description>` | Build it — pastes a matching schematic, or has the AI design one | `jarvis.use` |
| `/jarvis build undo` | Revert the last build | `jarvis.use` |
| `/jarvis build cancel` | Stop the build in progress | `jarvis.use` |
| `/jarvis build <wall, floor, pillar, or cube> [size]` | Simple shapes, no AI | `jarvis.use` |
| `/jarvis paste <name>` | Paste a schematic by name, no AI | `jarvis.use` |
| `/jarvis requests` | List pending player requests | `jarvis.admin` |
| `/jarvis approve <id>` | Approve a player item request | `jarvis.admin` |
| `/jarvis deny <id>` | Deny a player item request | `jarvis.admin` |
| `/jarvis confirm` | Confirm a pending dangerous action | `jarvis.use` |
| `/jarvis cancel` | Cancel a pending dangerous action | `jarvis.use` |
| `/jarvis <anything>` | Natural language — Jarvis figures it out | `jarvis.use` |

You can also just **type in chat** (no command needed) — if your message mentions Jarvis or contains a recognized keyword, he'll respond.

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
