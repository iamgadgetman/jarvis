# Jarvis Minecraft AI Companion v3.1

Your intelligent AI assistant for Minecraft - now with professional schematic building, fixed quests, and AI Q&A!

## ⚡ What's New in v3.1

### 🏗️ Schematic Building System
- Use REAL Minecraft schematics instead of AI-generated blocks
- Download from anywhere or use local files
- AI automatically selects the best schematic for your description
- Professional-looking builds in seconds

### ✅ Quest System Fixed
- Now properly tracks item collection (flowers, materials, etc.)
- Flexible matching for all material types
- Multiple event tracking for reliability

### 🤔 Ask Command
- Ask Jarvis anything about Minecraft
- Get instant AI-powered answers
- Examples: enchantments, crafting, strategies

---

## 🌟 Core Features

### 1. Natural Language Commands
Talk to Jarvis naturally - no complex commands needed!
```
"jarvis come here"
"jarvis start mining diamonds"
"jarvis build me a cottage"
"jarvis give me a quest"
```

### 2. Schematic Building
Professional builds using actual schematics:
```bash
/jarvis build cottage                    # AI selects best match
/jarvis schematics list                  # Show available
/jarvis schematics download <url> <n>   # Add new schematics
```

### 3. Dynamic Quest System
AI-generated quests that adapt to you:
- Mine specific ores
- Defeat monsters
- Collect materials (NOW WORKS WITH FLOWERS!)
- Build structures

### 4. Autonomous NPC
Your personal assistant that can:
- Mine ores automatically
- Fight hostile mobs
- Follow you around
- Store items in inventory

### 5. AI Question & Answer
```bash
/jarvis ask what are the best enchantments for a sword?
/jarvis ask how do I make a beacon?
/jarvis ask what's the best level for diamonds?
```

---

## 📦 Installation

### Prerequisites
- Minecraft Server (Purpur/Spigot/Paper 1.21+)
- Java 17+
- Maven 3.9+
- **Citizens plugin** (required)
- **WorldEdit plugin** (required for building)

### Quick Install

1. **Build the plugin:**
   ```bash
   mvn clean package
   ```

2. **Install dependencies:**
   - [Citizens](https://www.spigotmc.org/resources/citizens.13811/)
   - [WorldEdit](https://dev.bukkit.org/projects/worldedit)

3. **Deploy:**
   ```bash
   cp target/jarvis-0.0.1.jar server/plugins/
   ```

4. **Configure AI:**
   Edit `plugins/Jarvis/config.yml`:
   ```yaml
   ai:
     provider: claude  # or openai, grok, gemini
     claude:
       api-key: "your-api-key-here"
       model: "claude-sonnet-4-20250514"
   ```

5. **Add schematics (optional):**
   - Download .schem files from [Planet Minecraft](https://www.planetminecraft.com/)
   - Place in `plugins/Jarvis/schematics/`
   - Run `/jarvis schematics scan`

6. **Start server and enjoy!**

See [INSTALLATION.md](INSTALLATION.md) for detailed setup instructions.

---

## 🎮 Quick Start Guide

### First Steps

1. **Get started:**
   ```
   /jarvis summon
   ```

2. **Get the controller bell:**
   ```
   /jarvis bell
   ```
   Right-click to open menu!

3. **Try natural language:**
   ```
   Chat: jarvis give me a quest
   ```

4. **Add some schematics:**
   ```
   /jarvis schematics download <url> cottage
   ```

5. **Build something:**
   ```
   /jarvis build cottage
   ```

### Essential Commands

| Command | What It Does |
|---------|--------------|
| `/jarvis summon` | Bring Jarvis to you |
| `/jarvis mine` | Start automatic mining |
| `/jarvis attack` | Fight hostile mobs |
| `/jarvis build <desc>` | Build from schematics |
| `/jarvis quest new` | Get a new quest |
| `/jarvis ask <question>` | Ask anything |
| `/jarvis schematics` | List schematics |

---

## 📚 Documentation

- **[v3.1 Updates](v3.1_UPDATES.md)** - What's new and how to use it
- **[Schematic Quick Start](SCHEMATIC_QUICKSTART.md)** - Get building in 5 minutes
- **[Installation Guide](INSTALLATION.md)** - Detailed setup instructions
- **[Features Guide](FEATURES.md)** - Complete feature documentation
- **[Compilation Fixes](COMPILATION_FIXES.md)** - Java 17 compatibility notes

---

## 🏗️ Schematic Building

### Getting Schematics

**Recommended Sites:**
1. [Planet Minecraft](https://www.planetminecraft.com/) - Largest collection
2. [GrabCraft](https://grabcraft.com/) - Easy interface
3. [Minecraft-Schematics.com](https://www.minecraft-schematics.com/) - Well-organized

**Download and add:**
```bash
# Method 1: Manual
cd plugins/Jarvis/schematics/
# Upload .schem files here
/jarvis schematics scan

# Method 2: In-game download
/jarvis schematics download https://example.com/cottage.schem cottage
```

### Building Examples

```bash
# Specific build
/jarvis build cottage

# Natural language (AI selects best match)
jarvis build me a medieval house
jarvis build a guard tower
jarvis build something modern
```

**What happens:**
1. AI looks at all your schematics
2. Matches your description to the best one
3. Uses WorldEdit to paste it
4. Done in seconds!

---

## 🎯 Quest System

### How It Works Now (v3.1)

✅ **Fixed Issues:**
- Item collection now works (flowers, materials, etc.)
- Better matching for all block/item types
- Multiple event tracking for reliability

### Getting Quests

```bash
# Command
/jarvis quest new

# Natural language
Chat: jarvis give me a quest

# Check progress
/jarvis quest status
```

### Quest Types

| Type | Description | Example |
|------|-------------|---------|
| Mine | Break specific blocks | Mine 10 Diamond Ore |
| Kill | Defeat certain mobs | Kill 5 Zombies |
| Collect | Gather items | Collect 20 Poppies ✅ |
| Build | Construct structures | Build a house |

**Rewards:** XP and items when complete!

---

## 🤖 AI Features

### Supported AI Providers

| Provider | Model | Best For |
|----------|-------|----------|
| **Claude** ⭐ | Sonnet 4 | Overall best quality |
| OpenAI | GPT-3.5/4 | Fast responses |
| Grok | Grok-4 | Alternative option |
| Gemini | 1.5 Flash | Google integration |

### Natural Language Examples

```
# Building
"jarvis build me a house"
"jarvis build a medieval tower"

# Mining
"jarvis start mining"
"jarvis find diamonds"

# Combat
"jarvis defend me"
"jarvis attack those zombies"

# Quests
"jarvis give me a quest"
"jarvis show my quests"

# Questions
"jarvis ask how do I make glass?"
"jarvis ask what's the best armor?"
```

### Ask Command

Get instant answers to any Minecraft question:

```
/jarvis ask what are the best enchantments?
/jarvis ask how do I breed villagers?
/jarvis ask what blocks can't be moved by pistons?
```

---

## ⚙️ Configuration

### config.yml

```yaml
# AI Provider (choose one)
ai:
  provider: claude  # or: openai, grok, gemini
  claude:
    api-key: "sk-ant-xxxxx"
    model: "claude-sonnet-4-20250514"

# Features
natural-language:
  enabled: true
  prefix: "jarvis"
  require-prefix: false

quests:
  enabled: true
  max-active-per-player: 3
  reward-multiplier: 1.0

# Building
build:
  fallback-material: minecraft:stone
```

---

## 🎯 Example Workflows

### Building a Village

```bash
# 1. Get schematics
/jarvis schematics download <url> cottage
/jarvis schematics download <url> blacksmith
/jarvis schematics download <url> well

# 2. Build village
/jarvis build cottage
[move to new location]
/jarvis build blacksmith
[move to new location]
/jarvis build well
```

### Completing Quests

```bash
# 1. Get quest
/jarvis quest new
# Quest: Collect 20 Poppies

# 2. Collect flowers
*pick up poppies from ground or break them*

# 3. Track progress
Jarvis: Quest progress: Collect 5/20 POPPY
Jarvis: Quest progress: Collect 15/20 POPPY

# 4. Complete!
Jarvis: ✓ Quest Complete!
        +50 XP, +1x DIAMOND
```

### Using Jarvis NPC

```bash
# Summon
/jarvis summon

# Mine for you
/jarvis mine
*Jarvis automatically finds and mines ores*

# Check loot
/jarvis loot

# Return to you
/jarvis return
```

---

## 🔧 Troubleshooting

### Common Issues

**Quests not tracking items?**
- ✅ Fixed in v3.1! Make sure you're on the latest version
- Pick up items from ground (not just breaking blocks)
- Check `/jarvis quest status` for progress

**No schematics available?**
1. Download .schem files
2. Place in `plugins/Jarvis/schematics/`
3. Run `/jarvis schematics scan`

**AI not responding?**
- Check API key in config.yml
- Verify you have credits/quota
- Check server console for errors
- Run `/jarvis debug`

**Building not working?**
- Ensure WorldEdit is installed: `/we version`
- Make sure you have schematics added
- Try simpler description: `/jarvis build cottage`

---

## 📊 Command Reference

### Core Commands
```
/jarvis                     # Show help
/jarvis summon              # Spawn Jarvis
/jarvis dismiss             # Remove Jarvis
/jarvis return              # Teleport to you
/jarvis attack              # Combat mode
/jarvis mine                # Mining mode
/jarvis loot                # Open inventory
/jarvis bell                # Get controller
```

### Building Commands
```
/jarvis build <desc>        # Build with AI selection
/jarvis schematics          # List available
/jarvis schematics scan     # Reload folder
/jarvis schematics download <url> <n>
/jarvis schematics folder   # Show path
```

### Quest Commands
```
/jarvis quest               # Show quests
/jarvis quest new           # Get new quest
/jarvis quest status        # Progress details
```

### AI Commands
```
/jarvis ask <question>      # Q&A with AI
```

### Admin Commands
```
/jarvis reload              # Reload config
/jarvis debug               # System info
```

---

## 🎓 Best Practices

### Schematic Management
1. Name files descriptively: `small_oak_cottage.schem`
2. Organize by theme if you have many
3. Test new schematics in creative first
4. Check size before building: `/jarvis schematics list`

### Quest Efficiency
1. Take multiple quests at once (up to 3)
2. Look for complementary objectives
3. Check progress frequently: `/jarvis quest`
4. Collect items passively while doing other tasks

### Natural Language Usage
1. Be specific but natural
2. Use context clues ("build me a SMALL house")
3. Mention style ("medieval tower", "modern house")
4. Don't worry about exact syntax

---

## 📈 Performance

### Optimized For
- ✅ Large servers (50+ players)
- ✅ Multiple concurrent quests
- ✅ Frequent schematic building
- ✅ Heavy AI usage

### Resource Usage
- **Memory:** ~50MB additional
- **CPU:** Minimal (async operations)
- **Network:** Only for AI requests
- **Disk:** Varies (schematic files)

---

## 🆘 Support

### Getting Help

1. **Check documentation:** See guides above
2. **Debug info:** `/jarvis debug`
3. **Check logs:** `logs/latest.log | grep Jarvis`
4. **Verify config:** Check `plugins/Jarvis/config.yml`

### Common Solutions
- **AI errors** → Check API key and credits
- **NPC issues** → Verify Citizens is installed
- **Building issues** → Verify WorldEdit is installed
- **Quest issues** → Update to v3.1 for fixes

---

## 🚀 Roadmap

### Planned Features
- [ ] Schematic rotation and flipping
- [ ] Build previews
- [ ] Multi-schematic structures (villages)
- [ ] Quest chains and storylines
- [ ] Team/faction quests
- [ ] Voice command integration

### Want a Feature?
Let us know what you'd like to see!

---

## 📜 Version History

### v3.1 (Current)
- ✅ Schematic building system
- ✅ Quest tracking fixes (item collection)
- ✅ Ask command for Q&A
- ✅ Improved natural language

### v3.0
- Added Claude AI support
- Natural language processing
- AI building assistant (deprecated in v3.1)
- Dynamic quest system

### v2.7
- Mining navigation improvements
- Scaffolding support
- Enhanced pathfinding

---

## 📄 License

MIT License - See LICENSE file for details

---

## 🙏 Credits

- **Citizens API** - NPC functionality
- **WorldEdit** - Building operations
- **Anthropic Claude** - AI capabilities
- **OpenAI, xAI, Google** - Alternative AI providers

---

**Built with ❤️ for the Minecraft community**

Start building amazing things with Jarvis today! 🏰✨
