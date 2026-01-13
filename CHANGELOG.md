# Changelog

All notable changes to the Jarvis Minecraft AI Companion plugin will be documented in this file.

## [0.0.4] - 2026-01-08

### 🎉 "Smart Assistant" Update

This is a MAJOR update focused on making Jarvis truly intelligent, safe, and feature-rich.

### Added
- **Vein Mining**: Jarvis now detects and mines entire ore veins
  - Scans for connected ore blocks
  - Mines systematically through the vein
  - Reports vein size: "Found diamond vein (12 blocks)!"
  - Configurable: `mining.enable-vein-mining`
  
- **Visual Feedback System**: Particle effects and chat messages
  - Happy particles when ore found
  - Particle beam from Jarvis to ore
  - Mining particles at target block
  - Chat alerts for discoveries, inventory status, dangers
  - Fully configurable in `mining.visual-feedback`
  
- **Auto-Return When Full**: Automatically returns to drop off loot
  - Returns at 90% inventory (configurable)
  - Drops items near player
  - Resumes mining automatically
  - Chat message: "Inventory 90% full! Returning"
  
- **Danger Detection**: Safety features to prevent losses
  - Lava detection (avoids ores near lava)
  - Too-deep detection (won't descend more than 15 blocks)
  - Alerts player: "Lava detected! Finding safer ore"
  - Configurable: `mining.danger-detection`
  
- **Mining Modes**: Target specific ores
  - `/jarvis mine` - Mine any ore (default)
  - `/jarvis mine diamond` - Only diamond ore
  - `/jarvis mine iron` - Only iron ore
  - Works with all ore types
  
- **Stop Command**: Emergency stop for all tasks
  - `/jarvis stop` - Immediately cancels mining/attacking
  - Clears navigation
  - Returns control instantly
  
- **Battle Mode**: Jarvis vs Jarvis PvP!
  - `/jarvis battle <player>` - Fight another player's Jarvis
  - Both Jarvis instances engage in combat
  - Damage indicators with particles
  - Auto-disengages if too far apart
  - FUN feature for PvP servers!

### Changed
- **Bedrock Mining Fix (FINAL)**: Tracks starting Y level properly
  - Uses starting Y, not current Y, for depth limit
  - Hard limit at Y=10 (never goes below)
  - Force-ascends if descends more than 15 blocks
  - Config: `mining.max-depth-below-start`
  - **THIS FINALLY FIXES THE BEDROCK ISSUE!**
  
- **Return Command**: Now stops current tasks
  - `/jarvis return` cancels mining/attacking before teleporting
  - Cleaner state management
  
- **Greeting Animation**: Even faster!
  - Starts after 0.5s (was 1.5s in 0.0.3)
  - Toggles every 0.25s
  - More responsive feel

### Fixed
- **Critical: Bedrock mining** - Won't mine to bedrock anymore!
- **Tool drops on dismiss** - Keeps working as of 0.0.3
- **Wall clipping** - Continues to work well
- **Task persistence** - Return command now properly stops tasks

### Technical
- Added `MiningState.startingY` tracking
- Added `MiningState.currentVein` for vein mining
- Added `MiningState.targetOreType` for specific ore modes
- New methods: `detectVein()`, `shouldReturnForDropoff()`, `returnAndDropOff()`, `isLavaNearby()`, `spawnOreDiscoveryParticles()`, `stop()`, `battle()`
- Config greatly expanded with mining and visual feedback sections
- Particle system using Bukkit Particle API
- ~300 lines of new code

### Configuration
```yaml
mining:
  max-depth-below-start: 10
  hard-bedrock-limit: 10
  auto-return-threshold: 90
  enable-vein-mining: true
  danger-detection:
    enabled: true
  visual-feedback:
    particles: true
    chat-messages: true
```

---

## [0.0.3] - 2026-01-07
### Added
- Intelligent vertical mining prevention
- Horizontal ore preference
- Smart tunnel sizing
- Faster greeting

### Fixed
- Mining straight down (partially)
- Tool loss on dismiss

---

## [0.0.2] - 2026-01-05 to 2026-01-07
### Added
- Schematic building
- Claude AI support
- Quest system overhaul

### Fixed
- Java 17 compatibility
- Mining iterations

---

## [0.0.1] - Initial Release
### Added
- NPC companion
- Natural language commands
- AI building
- Quest generation
- Mining automation

---

## Links
- [GitHub](https://github.com/iamgadgetman/jarvis)
- [v0.0.4 Release Notes](v0.0.4_RELEASE_NOTES.md)
