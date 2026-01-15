# Jarvis v0.0.6 - Implementation Package

## 📦 What's Included

This package contains everything you need to upgrade from v0.0.2 to v0.0.6.

### ✅ New Files (Copy to your project):
1. **StatisticsManager.java** → `src/main/java/com/yourname/jarvis/statistics/`
2. **UndoManager.java** → `src/main/java/com/yourname/jarvis/building/`
3. **QuestTemplate.java** → `src/main/java/com/yourname/jarvis/quests/`
4. **QuestLibrary.java** → `src/main/java/com/yourname/jarvis/quests/`
5. **SchematicPreview.java** → `src/main/java/com/yourname/jarvis/building/`

### ✅ Updated Files (Replace existing):
1. **config.yml** → `src/main/resources/`
2. **plugin.yml** → `src/main/resources/`
3. **pom.xml** → root directory

### ✅ Files to Update Manually:
Use the UPDATE_GUIDE files to modify these:
1. **Jarvis.java** (see UPDATE_GUIDE_Jarvis.md)
2. **JarvisCommands.java** (see UPDATE_GUIDE_JarvisCommands.md)
3. **JarvisNPC.java** (complex - full file needed)
4. **BuildingAssistant.java** (minor changes)
5. **QuestSystem.java** (minor changes)

### ✅ Documentation:
1. **CHANGELOG.md** - Version history
2. **IMPLEMENTATION_GUIDE_v0.0.6.md** - Step-by-step deployment
3. **DEPLOYMENT_PACKAGE_v0.0.6.md** - Feature overview

---

## 🚀 Quick Start (5 Minutes)

### Step 1: Backup
```bash
cd ~/apps/jarvis/jarvis
git add . && git commit -m "Pre-v0.0.6 backup" && git tag v0.0.2
```

### Step 2: Copy New Files
```bash
# Create directories
mkdir -p src/main/java/com/yourname/jarvis/statistics

# Copy new files
cp StatisticsManager.java src/main/java/com/yourname/jarvis/statistics/
cp UndoManager.java src/main/java/com/yourname/jarvis/building/
cp QuestTemplate.java src/main/java/com/yourname/jarvis/quests/
cp QuestLibrary.java src/main/java/com/yourname/jarvis/quests/
cp SchematicPreview.java src/main/java/com/yourname/jarvis/building/

# Replace config files
cp config.yml src/main/resources/
cp plugin.yml src/main/resources/
cp pom.xml ./

# Copy documentation
cp CHANGELOG.md ./
```

### Step 3: Update Existing Files
Follow the UPDATE_GUIDE files:
1. Open `UPDATE_GUIDE_Jarvis.md` - update Jarvis.java
2. Open `UPDATE_GUIDE_JarvisCommands.md` - update JarvisCommands.java
3. JarvisNPC.java, BuildingAssistant.java, QuestSystem.java need significant updates

**Note**: Due to complexity, I recommend requesting complete updated versions of:
- JarvisNPC.java (most complex)
- BuildingAssistant.java
- QuestSystem.java

### Step 4: Build
```bash
mvn clean package
```

### Step 5: Deploy
```bash
./stop.sh
cp target/jarvis-0.0.6.jar /server/plugins/
./start.sh
```

### Step 6: Configure
Edit `config.yml` and add your AI API key:
```yaml
ai:
  provider: openai  # or claude, grok, gemini
  openai:
    api-key: "YOUR_KEY_HERE"
```

### Step 7: Test
```
/jarvis summon
/jarvis mine        # Test torch placement
/jarvis stats       # Test statistics
/jarvis quest templates  # Test quest templates
```

---

## 🆕 New Features

### 1. Enhanced Vein Mining
Automatically detects and mines entire ore veins (up to 64 blocks)

### 2. Branch Mining
`/jarvis branch` - Creates systematic mining tunnels

### 3. Torch Placement
Auto-places torches every 8 blocks while mining

### 4. Build Undo
`/jarvis undo` - Undo builds within 5 minutes

### 5. Quest Templates
20+ pre-made quests mixed with AI-generated ones

### 6. Schematic Previews
`/jarvis preview <schematic>` - Preview before building

### 7. Statistics & Leaderboards
`/jarvis stats` and `/jarvis leaderboard` - Track and compete

### 8. Performance
Better caching, async operations, reduced lag

---

## 📝 New Commands

- `/jarvis branch` - Start branch mining
- `/jarvis branch stop` - Stop branch mining
- `/jarvis undo` - Undo last build
- `/jarvis preview <schematic>` - Preview schematic
- `/jarvis stats [player]` - View statistics
- `/jarvis leaderboard [category]` - View rankings
- `/jarvis quest templates` - List quest templates

---

## ⚙️ Important Config

```yaml
mining:
  place-torches: true
  torch-spacing: 8
  enable-vein-mining: true
  enable-branch-mining: false  # Enable when ready

build:
  enable-undo: true
  undo-timeout: 300  # 5 minutes

quests:
  use-templates: true
  template-weight: 0.5  # 50% templates, 50% AI

statistics:
  enabled: true
  enable-leaderboards: true
```

---

## 🆘 Need Complete Updated Files?

The following files are complex and may be easier to replace completely:

1. **JarvisNPC.java** - Torch placement, branch mining, statistics
2. **BuildingAssistant.java** - Undo integration
3. **QuestSystem.java** - Template integration

**Request these if needed!**

---

## 🐛 Troubleshooting

### Build Errors
- Check all files are in correct directories
- Verify package names match
- Run `mvn clean package`

### Features Not Working
- Check config.yml settings
- Verify API key is set
- Check server logs for errors

### Statistics Not Saving
- Check databases.yml connection
- Verify `statistics.enabled: true`

---

## ✅ Checklist

- [ ] Backed up to git (v0.0.2 tag)
- [ ] Copied all new files
- [ ] Updated config files
- [ ] Updated Jarvis.java
- [ ] Updated JarvisCommands.java
- [ ] Updated complex files (or requested full versions)
- [ ] Built with `mvn clean package`
- [ ] Deployed JAR to server
- [ ] Added API key to config
- [ ] Tested new features
- [ ] Committed: `git add . && git commit -m "v0.0.6"`
- [ ] Tagged: `git tag v0.0.6 && git push --tags`

---

## 📚 Documentation

- **IMPLEMENTATION_GUIDE_v0.0.6.md** - Detailed deployment steps
- **DEPLOYMENT_PACKAGE_v0.0.6.md** - Feature details
- **CHANGELOG.md** - What changed
- **UPDATE_GUIDE_*.md** - How to update specific files

---

**Jarvis v0.0.6** - Smarter mining, better building, more fun! 🎮
