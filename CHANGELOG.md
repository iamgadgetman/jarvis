# Changelog

All notable changes to the Jarvis Minecraft AI Companion plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.3] - 2026-01-07

### Added
- **Intelligent vertical mining prevention**: Won't mine straight down more than 10 blocks
- **Horizontal ore preference**: Prioritizes ores at similar Y level (±3 blocks)
- **Smart tunnel sizing**: 1x1 when digging down, 2-block when tunneling horizontally
- **Return command stops tasks**: `/jarvis return` now cancels mining/attacking

### Changed
- **Ore finding algorithm**: Complete rewrite with 4-tier priority system
  - Priority 1: Ores within 4 blocks at similar Y level (±3 blocks)
  - Priority 2: Ores within 4 blocks even if below
  - Priority 3: Distant ores at similar Y level (boosted priority)
  - Priority 4: Any remaining ores (limited to -10 blocks below)
- **Greeting animation**: Faster crouch timing (0.5s start, 0.25s toggles vs 1.5s/0.4s)
- **Loot system**: Tools and weapons no longer drop when dismissed
  - Only inventory items drop, equipment stays with Jarvis

### Fixed
- **Mining straight down to bedrock**: Now avoids ores more than 10 blocks below
- **Inefficient mining patterns**: Prefers horizontal exploration over vertical
- **Tool loss on dismiss**: Pickaxes and swords no longer drop with loot
- **Tasks persisting after return**: Return command now properly stops all activities

### Technical
- Updated version to 0.0.3
- Improved ore selection with vertical distance calculations
- Enhanced tunnel detection (vertical vs horizontal)
- Equipment protection in dismiss logic

---

## [0.0.2] - 2026-01-05 to 2026-01-07

**Note:** Version 0.0.2 underwent extensive development and iteration. Below is the complete changelog for all work done under this version.

### Added - 0.0.2 Development
- **Schematic Building System**: Real Minecraft schematics instead of AI-generated blocks
  - AI-powered schematic selection based on natural language
  - Download schematics directly from URLs
  - Automatic scanning of schematic folder
  - Database-backed metadata storage
  - Commands: `/jarvis schematics list`, `/jarvis schematics download`, `/jarvis schematics scan`
  - **Litematic detection**: Detects .litematic files and provides conversion guidance
- **Ask Command**: Simple Q&A interface with AI
  - `/jarvis ask <question>` for instant Minecraft advice
  - Powered by your chosen AI provider (Claude, OpenAI, Grok, or Gemini)
- **Claude AI Support**: Full integration with Anthropic's Claude Sonnet 4
  - Latest model: `claude-sonnet-4-20250514`
  - Better context understanding and creative responses
- **Greeting Animation**: Jarvis crouches twice after being summoned to say hello
- **Loot Persistence**: Items drop on dismiss instead of being lost
- **2-Block Tunnel Digging**: Creates proper player-sized tunnels when mining (initially)

### Changed - 0.0.2 Development

#### Quest System Overhaul
- Complete overhaul of item tracking
- Now properly tracks item pickup events (both old and new API)
- Flexible material matching for all block/item types
- Improved matching for flowers and material variants
- Multiple event listeners for reliability

#### Mining System Evolution
The mining system went through multiple iterations during 0.0.2:

**Initial Attempt:**
- Tried using Citizens Navigator API for pathfinding
- Attempted complex scaffolding system

**Iteration 1:**
- Removed scaffolding system completely
- Implemented manual teleportation-based movement
- Added ore priority: valuable ores first

**Iteration 2:**
- Changed to distance-first priority (10-block threshold)
- Added ore reachability checking
- Smooth walking with jumping capability

**Iteration 3:**
- Simplified to radius-first priority (4 blocks)
- Manual smooth movement with safety checks
- 2-block tunnel creation

**Final 0.0.2 State:**
- Smooth manual movement (0.3 block steps)
- Safety checks for ground and obstacles
- Basic 4-block radius priority
- 2-block tunnels for all directions
- Issues: Would mine straight down, no vertical awareness

#### Other Changes
- **Deepslate Emerald Ore**: Uses Silk Touch enchantment automatically
- **Schematic System**: Proper WorldEdit integration with deprecated method fixes

### Fixed - 0.0.2 Development

#### Compilation & Compatibility
- **Java 17 Compatibility**: All compilation errors resolved
  - Fixed `instanceof` pattern matching for Java 17
  - Fixed `ChatColor` string concatenation
  - Fixed WorldEdit `getVolume()` method call
  - Fixed WorldEdit deprecated `getX()`, `getY()`, `getZ()` methods (now use `x()`, `y()`, `z()`)
  - Fixed Citizens Navigator API incompatibility
  - Fixed duplicate variable declarations

#### Quest System
- Fixed: Flowers not being tracked when collected
- Fixed: Items only counting when broken, not picked up
- Fixed: Material name mismatches

#### Mining System
- Fixed: Scaffolding system (removed completely)
  - NPC dropping scaffolding at every block
  - NPC unable to climb scaffolding
  - Excessive scaffolding placement
- Fixed: Hopping/jerky movement during mining
- Fixed: Wall clipping (blocks broken before moving)
- Fixed: Loot disappearing when dismissed
- **Partially addressed**: Ore selection (improved but still had vertical mining issues)

### Known Issues - 0.0.2
- Would mine straight down to bedrock
- No preference for horizontal vs vertical ores
- 2-block tunnels used even when digging down (inefficient)
- Tools/weapons would drop with loot on dismiss
- Return command didn't stop mining/attacking

### Technical - 0.0.2 Development
- Updated project version to 0.0.2
- Added comprehensive .gitignore for GitHub
- Added MIT License
- Improved code organization and documentation
- Database schema updates for schematics
- Enhanced error handling throughout
- Multiple iterations on movement and mining logic
- Extensive testing and refinement

---

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

## Development Notes

### Version 0.0.2 Development Cycle
Version 0.0.2 represented a major development cycle with extensive iteration:
- **5+ major mining system rewrites** to find the right approach
- **Multiple API compatibility fixes** for Citizens and WorldEdit
- **Complete scaffolding removal** after testing showed it wasn't viable
- **Iterative ore priority refinement** through testing and feedback
- **Smooth movement development** to eliminate hopping behavior

Key learnings:
- Citizens Navigator API has version-specific behavior
- Manual movement can be smoother than pathfinding for custom behaviors
- Ore selection needs multi-factor priority (distance, value, direction)
- Equipment vs inventory separation is important for NPC management

### Version 0.0.3 Focus
Version 0.0.3 addresses the final major mining issues discovered in 0.0.2:
- Vertical mining behavior (no more mining to bedrock)
- Smart tunnel sizing (1x1 down, 2-block horizontal)
- Equipment protection (tools don't drop)
- Task cancellation (return stops activities)

## Links

- [GitHub Repository](https://github.com/iamgadgetman/jarvis)
- [Installation Guide](INSTALLATION.md)
- [Feature Guide](FEATURES.md)
