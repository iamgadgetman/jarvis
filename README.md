# Jarvis v0.0.8 - AI Companion for Minecraft

An AI-powered NPC companion for Minecraft servers using Citizens and multiple AI backends.

## Features

### Core Systems
- **AI-Powered NPC** - Companion that follows, assists, and responds to natural language
- **Multi-AI Support** - OpenAI, Claude, Grok, Gemini, Ollama (local)
- **Auto AI Switching** - Automatically fails over between providers on errors
- **Natural Language Commands** - Talk to Jarvis through chat

### Mining System (v0.0.8 - Rewritten)
- **Simplified State Machine** - 5-phase mining: SEARCHING → MOVING → MINING → COLLECTING → RETURNING
- **Reliable Movement** - Teleport-based fallback when pathfinding fails
- **Ore Detection** - Finds valuable ores within configurable radius
- **Stuck Detection** - Automatically clears blocking obstacles
- **Auto-Collect** - Picks up dropped items after mining
- **Torch Placement** - Places torches in dark areas

### Building System
- **Schematic Support** - Build from .schem files (WorldEdit format)
- **Litematica Conversion** - Converts .litematic to .schem
- **Build Previews** - Preview builds before placing
- **Undo System** - Revert recent builds

### Quest System
- **AI-Generated Quests** - Dynamic quest creation
- **Quest Templates** - Pre-made quest library
- **Progress Tracking** - Statistics and leaderboards

## Requirements

- Minecraft 1.21+
- Citizens 2.x plugin
- Java 17+
- Optional: WorldEdit, WorldGuard

## Installation

1. Place `jarvis-0.0.8.jar` in your server's `plugins/` folder
2. Restart the server
3. Edit `plugins/Jarvis/config.yml` with your AI API keys
4. Use `/jarvis summon` to spawn your companion

## Commands

| Command | Description |
|---------|-------------|
| `/jarvis summon` | Spawn Jarvis at your location |
| `/jarvis dismiss` | Dismiss Jarvis |
| `/jarvis mine` | Start mining for ores |
| `/jarvis mine stop` | Stop mining |
| `/jarvis branch` | Start branch mining |
| `/jarvis follow` | Follow the player |
| `/jarvis return` | Return to player |
| `/jarvis inventory` | Open Jarvis inventory |
| `/jarvis stats` | View statistics |
| `/jarvis quest` | Quest commands |
| `/jarvis build <schematic>` | Build a schematic |
| `/jarvis debug` | Show debug info |
| `/jarvis reload` | Reload configuration |

## Configuration

```yaml
ai:
  provider: auto  # auto, openai, claude, grok, gemini, ollama
  openai:
    api-key: "YOUR_KEY"
    model: "gpt-4o-mini"
  claude:
    api-key: "YOUR_KEY"
    model: "claude-sonnet-4-20250514"
  grok:
    api-key: "YOUR_KEY"
  gemini:
    api-key: "YOUR_KEY"
  ollama:
    url: "http://localhost:11434"
    model: "llama3.2"

mining:
  search-radius: 10
  max-depth: 64
  place-torches: true
  torch-spacing: 8
```

## Version History

### v0.0.8 (Current)
- Complete mining system rewrite
- Simplified 5-phase state machine
- Teleport-based movement fallback
- Improved stuck detection and recovery
- Debug logging for troubleshooting

### v0.0.7
- Improved NPC movement
- Ollama support for local AI
- Auto AI provider switching
- Bug fixes

### v0.0.6
- Mining improvements
- Branch mining
- Statistics system
- Quest templates

## Building from Source

```bash
mvn clean package
```

The JAR will be in `target/jarvis-0.0.8.jar`

## License

MIT License

---

**Jarvis** - Your AI companion in Minecraft
