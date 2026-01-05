# Quick Start: Schematic Building System

## 🚀 5-Minute Setup Guide

### Step 1: Download Some Schematics (2 minutes)

**Option A: From Planet Minecraft**
1. Go to: https://www.planetminecraft.com/
2. Search for "house schematic" or "cottage schematic"
3. Download 3-5 schematics you like (.schem files)

**Option B: Quick Test Files**
Use these pre-made test links (if available in your community):
```
# Example URLs - replace with real ones
https://example.com/cottage.schem
https://example.com/tower.schem
https://example.com/barn.schem
```

### Step 2: Add to Server (1 minute)

**Method 1: Manual Upload (Recommended)**
```bash
# On your server
cd plugins/Jarvis/schematics/

# Upload your .schem files here using:
# - SCP: scp cottage.schem user@server:/plugins/Jarvis/schematics/
# - FTP: Upload via FileZilla
# - Or use your hosting panel's file manager
```

**Method 2: In-Game Download**
```
/jarvis schematics download https://example.com/cottage.schem cottage
```

### Step 3: Scan & Verify (30 seconds)

In-game:
```
/jarvis schematics scan
/jarvis schematics list
```

You should see your schematics listed!

### Step 4: Build Something! (30 seconds)

```
/jarvis build cottage
```

Or natural language:
```
jarvis build me a house
```

**Done!** You now have professional-looking buildings in seconds!

---

## 📝 Finding Good Schematics

### Recommended Sites

#### 1. Planet Minecraft ⭐ (Best)
**URL:** https://www.planetminecraft.com/resources/projects/?share=schematic

**Why:** Largest collection, high quality, active community

**How to download:**
1. Find a schematic you like
2. Click "Download"
3. Select "Schematic" format
4. Upload to your server

**Search tips:**
- "small house schematic"
- "medieval cottage"
- "starter base"
- "simple tower"

#### 2. GrabCraft
**URL:** https://grabcraft.com/minecraft/

**Why:** Easy interface, good blueprints

**Categories:**
- Houses & Buildings
- Castles & Fortresses
- Towers & Lighthouses
- Modern Buildings

#### 3. Minecraft-Schematics.com
**URL:** https://www.minecraft-schematics.com/

**Why:** Well-organized, quality builds

**Best sections:**
- Medieval category
- Modern housing
- Fantasy builds

### What to Look For

✅ **Good Schematics:**
- Clear screenshots
- Size listed (not too huge)
- Good ratings/reviews
- Appropriate for your style

❌ **Avoid:**
- Massive schematics (100+ blocks) for first tries
- Low-quality or incomplete builds
- Incompatible versions (very old)

### Starter Pack Recommendation

Get these types first:
1. **Small Cottage** (10x10x8) - For starter homes
2. **Guard Tower** (8x20x8) - For defense/decoration
3. **Barn** (15x12x10) - For farming areas
4. **Wall Section** (10x5x2) - For fortifications
5. **Modern House** (12x12x10) - Alternative style

---

## 🎯 Usage Examples

### Example 1: Building a Village

```
# You have these schematics:
- cottage.schem
- barn.schem  
- well.schem
- blacksmith.schem

# Build them:
/jarvis build cottage
[Move to new location]
/jarvis build barn
[Move to new location]
/jarvis build blacksmith

# Or use natural language:
jarvis build me a cottage
jarvis build a barn over here
jarvis build a blacksmith shop
```

### Example 2: Creating a Base

```
# Schematics available:
- modern_house.schem
- garage.schem
- fence.schem

# Natural language flow:
Player: "jarvis build me a modern house"
Jarvis: "Let me find the perfect schematic..."
Jarvis: "Built 'modern_house' successfully! Size: 15x12x12"

Player: *moves to side*
Player: "jarvis build a garage next to it"
Jarvis: "Built 'garage' successfully! Size: 8x8x10"
```

### Example 3: Medieval Fortress

```
# Downloaded schematics:
- castle_wall.schem
- castle_tower.schem
- castle_gate.schem
- barracks.schem

# Build sequence:
/jarvis build castle wall
/jarvis build tower
/jarvis build tower
/jarvis build gate
/jarvis build barracks

# The AI will select the right schematic each time!
```

---

## 💡 Pro Tips

### Tip 1: Naming Strategy
Name your schematics descriptively:
```
✅ Good:
- small_oak_cottage.schem
- medieval_stone_tower.schem  
- modern_glass_house.schem

❌ Bad:
- house1.schem
- build.schem
- untitled.schem
```

**Why:** Better AI matching with good names!

### Tip 2: Testing New Schematics
```
# Always check size first:
/jarvis schematics list

# Test in creative or flat area first
# Make sure you have enough space
```

### Tip 3: Organize by Theme
```
schematics/
├── medieval/
│   ├── castle_tower.schem
│   ├── stone_cottage.schem
│   └── guard_house.schem
├── modern/
│   ├── glass_house.schem
│   └── apartment.schem
└── fantasy/
    ├── wizard_tower.schem
    └── fairy_cottage.schem
```

Then use subdirectories (feature not implemented yet, but good practice!)

### Tip 4: Natural Language Works Best
Instead of:
```
/jarvis build medieval_stone_cottage_small_v2
```

Just say:
```
jarvis build a small medieval cottage
```

The AI will find the best match!

---

## 🔥 Common Workflows

### Workflow 1: Daily Building Session
```
1. Check available schematics:
   /jarvis schematics list

2. Pick what you need:
   jarvis build me a house
   
3. If you don't like it:
   /minecraft:undo (if you have WorldEdit permissions)
   or
   jarvis build something else
```

### Workflow 2: Adding New Content
```
1. Download 5 new schematics
2. Upload to server's schematic folder
3. In-game: /jarvis schematics scan
4. Test them: /jarvis build <name>
5. Keep favorites, delete others
```

### Workflow 3: Themed Build Project
```
# Example: Medieval Village

1. Download all medieval schematics you can find
2. Upload them all at once
3. Scan: /jarvis schematics scan
4. Build village:
   - jarvis build a cottage
   - jarvis build a blacksmith
   - jarvis build a tavern
   - jarvis build guard towers
   - jarvis build walls
```

---

## 🛠️ Troubleshooting

### Problem: "No schematics available"

**Check 1:** Folder exists?
```bash
ls plugins/Jarvis/schematics/
```

**Check 2:** Files are there?
```bash
ls -la plugins/Jarvis/schematics/
# Should show .schem or .schematic files
```

**Check 3:** Scanned?
```
/jarvis schematics scan
```

### Problem: "Schematic not found"

Use exact name from list:
```
/jarvis schematics list
# Copy exact name shown
/jarvis build <exact_name>
```

Or let AI match:
```
jarvis build something similar to <description>
```

### Problem: Build in wrong location

**Solution:** Stand where you want it built!
```
# Jarvis builds 3 blocks in front of you
# Face the direction you want
# Stand at the location
# Then: /jarvis build <name>
```

### Problem: "WorldEdit not found"

**Solution:** Install WorldEdit:
```
1. Download from: https://dev.bukkit.org/projects/worldedit
2. Place in plugins/
3. Restart server
4. Verify: /we version
```

---

## 📊 Performance Tips

### Schematic Size Guidelines

| Size | Build Time | Lag? | Recommended For |
|------|------------|------|-----------------|
| Small (< 10x10x10) | Instant | None | Decorations, small builds |
| Medium (10-20 each dimension) | 1-2 sec | None | Houses, shops |
| Large (20-50 each dimension) | 2-5 sec | Minimal | Castles, large builds |
| Huge (50+ any dimension) | 5-15 sec | Possible | Careful! Test first |

### Best Practices

✅ **Do:**
- Start with small schematics
- Test on flat ground first
- Build in Creative mode initially
- Check size before building

❌ **Don't:**
- Build huge schematics on populated server
- Build without checking dimensions
- Build multiple large structures simultaneously

---

## 🎓 Learn More

### Understanding the System

**How AI Selection Works:**
1. You say: "build a cottage"
2. AI looks at all schematics
3. AI checks: names, descriptions, tags
4. AI picks best match
5. WorldEdit pastes it

**Tags are auto-generated from filenames:**
```
cottage.schem           → tags: cottage, house
medieval_tower.schem    → tags: tower, medieval
small_house.schem       → tags: house, small
```

**AI considers:**
- Exact name matches (highest priority)
- Tag matches
- Description keywords
- Size preferences (small/large)

---

## 🎉 You're Ready!

You now know:
- ✅ Where to find schematics
- ✅ How to add them to server
- ✅ How to build with them
- ✅ How to troubleshoot issues
- ✅ Best practices for great results

**Start building and have fun!** 🏰

---

## Need Help?

Run these commands:
```
/jarvis schematics folder    # Show folder location
/jarvis schematics list      # Show available builds
/jarvis debug                # System status
```

Or ask Jarvis:
```
/jarvis ask how do I add schematics?
/jarvis ask what schematics do I have?
```
