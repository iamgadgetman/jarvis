# Changelog

All notable changes to the Jarvis Minecraft AI Companion plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.2] - 2026-01-05

### Added
- **Schematic Building System**: Real Minecraft schematics instead of AI-generated blocks
  - AI-powered schematic selection based on natural language
  - Download schematics directly from URLs
  - Automatic scanning of schematic folder
  - Database-backed metadata storage
  - Commands: `/jarvis schematics list`, `/jarvis schematics download`, `/jarvis schematics scan`
- **Ask Command**: Simple Q&A interface with AI
  - `/jarvis ask <question>` for instant Minecraft advice
  - Powered by your chosen AI provider (Claude, OpenAI, Grok, or Gemini)
- **Claude AI Support**: Full integration with Anthropic's Claude Sonnet 4
  - Latest model: `claude-sonnet-4-20250514`
  - Better context understanding and creative responses
- **Greeting Animation**: Jarvis crouches twice after being summoned to say hello
- **Ore Reachability Check**: Jarvis now verifies ores are reachable before targeting them

### Changed
- **Quest System**: Complete overhaul of item tracking
  - Now properly tracks item pickup events (both old and new API)
  - Flexible material matching for all block/item types
  - Improved matching for flowers and material variants
  - Multiple event listeners for reliability
- **Ore Mining Priority**: Distance-first strategy with value-based tiebreaker
  - Prioritizes nearest ores first (within 10 block threshold)
  - Then picks most valuable ore in that group
  - Example: Nearby coal mined before distant diamonds
  - More efficient mining patterns
- **Deepslate Emerald Ore**: Now uses Silk Touch enchantment automatically
  - Keeps emerald ore blocks intact for collection
- **Mining Navigation**: Smooth walking with jumping capability
  - Removed buggy scaffolding system completely
  - Smooth walking movement (no more hopping)
  - Can jump up 1 block obstacles
  - Better terrain climbing
  - Update frequency: 10 ticks (0.5 seconds) for smoother movement
  - Move speed adjusted to 0.5 for natural walking pace

### Fixed
- **Java 17 Compatibility**: All compilation errors resolved
  - Fixed `instanceof` pattern matching for Java 17
  - Fixed `ChatColor` string concatenation
  - Fixed WorldEdit `getVolume()` method call
  - Fixed Citizens Navigator API incompatibility
- **Scaffolding Issues**: Completely removed problematic scaffolding logic
  - Fixed: NPC dropping scaffolding at every block
  - Fixed: NPC unable to climb scaffolding
  - Fixed: Excessive scaffolding placement
- **Quest Collection Tracking**: 
  - Fixed: Flowers not being tracked when collected
  - Fixed: Items only counting when broken, not picked up
  - Fixed: Material name mismatches
- **Mining Movement**: 
  - Fixed: Hopping/jerky movement during mining
  - Fixed: Targeting unreachable ores on cliffs
  - Fixed: Getting stuck on terrain
  - Fixed: Ignoring nearby ores for distant valuable ones

### Technical
- Updated project version to 0.0.2
- Added comprehensive .gitignore for GitHub
- Added MIT License
- Improved code organization and documentation
- Database schema updates for schematics
- Enhanced error handling throughout
- Optimized NPC movement updates (10 ticks vs 5 ticks)
- Added ore reachability validation

## [0.0.1] - 2025-01-XX

### Added
- Initial release
- Natural language command processing
- Autonomous NPC companion (Citizens integration)
- AI-powered building assistant (block-by-block placement)
- Dynamic quest generation system
- Mining automation with ore detection
- Combat mode for mob fighting
- Multi-AI provider support (OpenAI, Grok, Gemini)
- Controller bell item for easy menu access
- Database integration for player stats
- Configuration system

### Features
- Natural language commands via chat
- NPC summoning and control
- Automatic ore mining with pathfinding
- Quest generation based on biome and level
- Real-time quest progress tracking
- AI dialogue generation
- Item inventory management for NPC
- WorldEdit integration for building

---

## Version Numbering

- **0.0.x**: Initial development releases
- **0.x.0**: Minor feature additions
- **x.0.0**: Major releases with breaking changes

## Links

- [GitHub Repository](https://github.com/yourusername/jarvis-minecraft)
- [Documentation](README_v3.1.md)
- [Installation Guide](INSTALLATION.md)
- [Feature Guide](v3.1_UPDATES.md)
