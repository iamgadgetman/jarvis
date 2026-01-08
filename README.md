# Jarvis v0.0.3 Release Notes

## 🎯 Smart Mining Update

Version 0.0.3 focuses on making Jarvis mine intelligently - no more straight down to bedrock!

---

## 🆕 What's New in 0.0.3

### 1. ✅ **No More Mining to Bedrock**

**The Problem:** Jarvis would mine straight down until he hit bedrock and got stuck.

**The Solution:**
- Won't target ores more than 10 blocks below current position
- Prefers ores at similar Y level (±3 blocks)
- Horizontal exploration prioritized over vertical

**Result:** Jarvis mines like a smart player, exploring horizontally! ⛏️

---

### 2. ✅ **Smart Tunnel Sizing**

**The Problem:** Jarvis dug 2-block tall tunnels even when going straight down (inefficient).

**The Solution:**
```
Digging straight down: 1x1 tunnel (efficient)
Digging horizontally:  2-block tall (player can walk through)
```

**How it works:** Calculates if vertical distance > horizontal distance → 1x1, otherwise 2-block

**Result:** Efficient mining adapted to direction! 📐

---

### 3. ✅ **New Ore Priority System**

**4-Tier Priority:**

**Priority 1** (Highest): Ores within 4 blocks at similar Y level (±3)
- Example: Coal 3 blocks away, same level → Mine first!

**Priority 2**: Ores within 4 blocks below
- Example: Diamond 3 blocks away, 5 blocks down → Mine second

**Priority 3**: Distant ores at similar Y level (boosted)
- Example: Gold 15 blocks away, same level → Preferred over below

**Priority 4** (Lowest): Ores beyond 4 blocks and more than 3 blocks up/down
- Limited to max 10 blocks below to prevent bedrock mining

**Result:** Smart, horizontal-first exploration! 🧠

---

### 4. ✅ **Return Command Stops Tasks**

**The Problem:** `/jarvis return` brought Jarvis back, but he'd resume mining/attacking.

**The Solution:** Return command now calls `stopTask()` automatically.

**Usage:**
```
/jarvis mine
[Jarvis is mining far away]
/jarvis return
→ Jarvis stops mining and teleports back ✓
```

**Result:** Better control over Jarvis! 🎮

---

### 5. ✅ **Faster Greeting**

**The Problem:** Greeting took 1.5 seconds to start, felt slow.

**The Solution:**
```
Old: Start after 1.5s, toggle every 0.4s
New: Start after 0.5s, toggle every 0.25s
```

**Result:** Snappier, more responsive greeting! 👋

---

### 6. ✅ **Tools Don't Drop on Dismiss**

**The Problem:** When dismissed, Jarvis dropped his pickaxe/sword with the loot.

**The Solution:** Only drop inventory items, exclude equipped items.

**Before:**
```
/jarvis dismiss
→ Drops: Diamonds, coal, iron, PICKAXE, SWORD ❌
```

**After:**
```
/jarvis dismiss
→ Drops: Diamonds, coal, iron ✓
→ Keeps: Pickaxe, sword (equipment) ✓
```

**Result:** No more losing or duplicating tools! 🛠️

---

## 📊 Complete Changes

### Added
- Intelligent vertical mining prevention (max 10 blocks down)
- Horizontal ore preference (±3 blocks Y level)
- Smart tunnel sizing (1x1 vs 2-block)
- Return stops all tasks

### Changed
- Ore finding algorithm (4-tier priority)
- Greeting animation timing (faster)
- Loot system (equipment protected)

### Fixed
- Mining straight down to bedrock
- Inefficient tunnel sizing
- Tool loss on dismiss
- Tasks persisting after return

---

## 🧪 Testing Guide

### Test 1: Horizontal Mining Preference
```bash
# Create test: 
- Coal 5 blocks away, same Y level
- Diamond 5 blocks away, 8 blocks down

/jarvis summon
/jarvis mine

Expected: Mines COAL first (same level) ✓
```

### Test 2: Bedrock Prevention
```bash
# Place ores:
- Some at Y=60
- Some at Y=5 (near bedrock)

# Start Jarvis at Y=60
/jarvis mine

Expected: 
- Mines ores at Y=60, Y=55, Y=50 ✓
- Won't chase Y=5 ores (too far down) ✓
```

### Test 3: Smart Tunnels
```bash
# Horizontal tunnel
/jarvis mine (with ore ahead, same level)
Expected: 2-block tall tunnel ✓

# Vertical shaft
/jarvis mine (with ore directly below)
Expected: 1x1 shaft ✓
```

### Test 4: Return Stops Mining
```bash
/jarvis summon
/jarvis mine
[Wait for Jarvis to start mining]
/jarvis return

Expected:
- Jarvis stops breaking blocks ✓
- Teleports back to player ✓
- Doesn't resume mining ✓
```

### Test 5: Tool Protection
```bash
/jarvis summon
/jarvis mine
[Let Jarvis collect items]
/jarvis dismiss

Expected:
- Ores drop on ground ✓
- Pickaxe does NOT drop ✓
```

### Test 6: Faster Greeting
```bash
/jarvis summon

Expected:
- Crouches after ~0.5 seconds ✓
- Quick crouch animation ✓
- More responsive feel ✓
```

---

## 🔧 Technical Details

### Ore Finding Algorithm

```java
// Priority 1: Close + Same Level
if (dist <= 4.0 && verticalDist <= 3.0) {
    return ore; // Highest priority
}

// Priority 2: Close but below
if (dist <= 4.0) {
    return ore;
}

// Priority 3: Distant + Same level (boosted)
if (verticalDist <= 5.0) {
    priority += 5; // Boost for horizontal
}

// Prevention: Skip ores too far below
if (ore.getY() < center.getY() - 10) {
    continue; // Avoid bedrock mining
}
```

### Tunnel Size Detection

```java
double verticalDist = Math.abs(oreLoc.getY() - npcLoc.getY());
double horizontalDist = Math.sqrt(dx² + dz²);

boolean diggingDown = (oreBelow) && (verticalDist > horizontalDist);

if (diggingDown) {
    // Dig 1x1 (eye level only)
} else {
    // Dig 2-block (eye + feet level)
}
```

### Equipment Protection

```java
// Get all equipped items
ItemStack handItem = equipment.get(HAND);
ItemStack sword = equipment.get(HAND);
// ... other equipment ...

// Only drop non-equipped items
for (ItemStack item : inventory) {
    if (!item.isSimilar(handItem)) {
        world.dropItem(item); // Only loot
    }
}
```

---

## 📝 Migration from 0.0.2

### Breaking Changes
None! All changes are improvements.

### Behavioral Changes
- **Mining patterns**: More horizontal, less vertical
- **Return command**: Now stops tasks (new behavior)
- **Dismiss**: Tools stay with Jarvis (loot protection)

### Configuration
No config changes required.

### Upgrade Steps
```bash
# 1. Stop server
# 2. Replace JAR
cp jarvis-0.0.3.jar /server/plugins/
# 3. Start server
# 4. Test with /jarvis summon
```

---

## 🗒️ Notes on 0.0.2 Development

Version 0.0.2 was a major development cycle with extensive iteration on the mining system. Key milestones:

1. **Initial Implementation**: Scaffolding system (removed - buggy)
2. **Iteration 1**: Manual movement, value-first priority
3. **Iteration 2**: Distance-first priority, ore reachability
4. **Iteration 3**: Radius-first (4 blocks), smooth movement
5. **Final 0.0.2**: Working but mines straight down

**Key Learnings:**
- Citizens Navigator API varies by version
- Manual movement can be smoother than pathfinding
- Ore selection needs multi-dimensional priority
- Equipment vs inventory separation matters

**Issues Resolved in 0.0.3:**
- ✅ Vertical mining behavior
- ✅ Tunnel sizing intelligence
- ✅ Tool protection
- ✅ Task cancellation

---

## 🚀 What's Next?

Potential future improvements:
- Vein mining (detect and mine entire ore veins)
- Branch mining patterns
- Configurable mining strategies
- Mining level preferences
- Torch placing while mining

---

## 📦 Download & Deploy

### Build
```bash
mvn clean package
```

### Output
```
target/jarvis-0.0.3.jar
```

### Deploy
```bash
cp target/jarvis-0.0.3.jar /server/plugins/
# Restart server
```

### Verify
```
/jarvis summon
Jarvis: At your service—let's make some magic.
[Quick crouch animation] ✓

/version Jarvis
Jarvis version 0.0.3 ✓
```

---

## 🎉 Summary

Version 0.0.3 makes Jarvis a truly smart miner:
- ✅ No more bedrock diving
- ✅ Intelligent horizontal exploration
- ✅ Efficient tunnel sizing
- ✅ Better command control
- ✅ Tool protection

**Jarvis now mines like a professional player, not a confused robot!** ⛏️✨

---

## 📞 Support

- **GitHub Issues**: https://github.com/iamgadgetman/jarvis/issues
- **Documentation**: See repository for full guides
- **Changelog**: See CHANGELOG.md for complete history

**Enjoy the update!** 🎮
