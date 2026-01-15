# Changelog

All notable changes to Jarvis will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.5] - 2026-01-14

### 🎯 Major Update: Smart Mining & Code Improvements

#### Added
- **Exposed Ore Detection**: Uses raytrace to detect visible ores - mines exposed ores first!
- **Smart Priority System**: 3-tier priority (Exposed > Value > Distance)
- **Dirt Pillar Climbing**: Replaces scaffolding - places dirt blocks to climb, auto-cleanup
- **Debug Mode**: Set DEBUG constant to true for detailed logging
- **Performance Optimizations**: Reduced tick rates, smarter ore scanning
- **Better Constants**: All magic numbers extracted to named constants
- **Enhanced State Management**: Cleaner MiningState class with ore counter

#### Changed
- **Reduced Search Radius**: 32 blocks → 16 blocks (stays closer to player)
- **Mining Tick Rate**: 5 ticks (was variable)
- **Combat Tick Rate**: 10 ticks (was 10, now documented)
- **Movement Speed**: Optimized to 0.25 for smooth movement
- **Climb Threshold**: Only climbs if >2 blocks up
- **Max Pillar Height**: Limited to 8 blocks

#### Fixed
- **Version Consistency**: All files now show 0.0.5 (pom.xml, plugin.yml, Jarvis.java)
- **Long-Distance Mining**: No longer targets ores 30+ blocks away
- **Exposed Ore Priority**: Now mines visible ores before hidden ones
- **Climbing System**: Dirt pillars work better than scaffolding
- **Code Organization**: Better structure with constants and comments

#### Technical Improvements
- Added comprehensive JavaDoc comments
- Extracted all configuration to constants at top of class
- Improved error handling with null checks
- Better logging with debugLog() method
- Cleaner separation of concerns
- Performance improvements in ore scanning

#### Removed
- Scaffolding system (replaced with dirt pillars)
- Duplicate movement code
- Unnecessary variable declarations

---

## [0.0.4] - Unreleased

This version was mentioned but not found on GitHub. If it exists locally, changes should be documented here.

---

## [0.0.3] - 2026-01-08

### Smart Mining Update

#### Added
- Intelligent vertical mining prevention (max 10 blocks down)
- Horizontal ore preference (±3 blocks Y level)
- Smart tunnel sizing (1x1 vs 2-block)
- Return command stops all tasks

#### Changed
- Ore finding algorithm (4-tier priority)
- Greeting animation timing (faster)
- Loot system (equipment protected)

#### Fixed
- Mining straight down to bedrock
- Inefficient tunnel sizing
- Tool loss on dismiss
- Tasks persisting after return

---

## [0.0.2] - 2026-01-05

### Mining System Implementation

#### Added
- Basic mining functionality
- Ore priority system
- Automatic ore detection
- Tool enchantments

#### Fixed
- Mining priorities
- NPC spawning
- Equipment handling

---

## [0.0.1] - Initial Release

### Initial Features

#### Added
- NPC companion system
- Basic AI integration
- Command system
- Controller bell
- Basic mining
- Combat mode
- Database system

---

## Migration Notes

### From 0.0.4 to 0.0.5
- No breaking changes
- Mining behavior significantly improved
- Set DEBUG=true in JarvisNPC.java for troubleshooting

### From 0.0.3 to 0.0.5
- Replace scaffolding with dirt blocks (automatic)
- Mining stays closer to player now
- Exposed ores prioritized

### From 0.0.2 to 0.0.5
- Complete mining overhaul
- Much smarter ore selection
- Better climbing system

---

## Known Issues

### v0.0.5
- None reported yet

### Previous Versions
- v0.0.4: Missing from GitHub
- v0.0.3: Occasional bedrock diving
- v0.0.2: Inefficient tunneling

---

## Upgrade Instructions

### General Upgrade
1. Stop server
2. Replace `jarvis-X.X.X.jar` with new version
3. Start server
4. Run `/jarvis reload` (optional)

### From any version to 0.0.5
```bash
# 1. Build new version
mvn clean package

# 2. Stop server
./stop.sh

# 3. Replace JAR
cp target/jarvis-0.0.5.jar /path/to/server/plugins/

# 4. Start server
./start.sh

# 5. Verify version
/jarvis debug
```

---

## Development Notes

### v0.0.5 Development Focus
- Prioritized exposed ore detection
- Improved climbing mechanics
- Code quality and maintainability
- Performance optimizations
- Version consistency

### Testing Checklist v0.0.5
- [ ] Exposed ores mined first
- [ ] Search radius limited to 16 blocks
- [ ] Dirt pillars work correctly
- [ ] Auto-cleanup works
- [ ] Version shows 0.0.5 everywhere
- [ ] Debug mode functional

---

## Future Roadmap

### Planned for 0.0.6
- Vein mining (detect connected ore blocks)
- Branch mining patterns
- Configurable mining strategies
- Torch placement while mining
- Better pathfinding around lava

### Planned for 0.1.0
- AI chat integration improvements
- Quest system enhancements
- Building system improvements
- Multi-NPC support

---

## Contributors

- @iamgadgetman - Project owner and maintainer

---

## Links

- GitHub: https://github.com/iamgadgetman/jarvis
- Issues: https://github.com/iamgadgetman/jarvis/issues
- License: MIT
