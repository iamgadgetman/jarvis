# Jarvis v0.0.5 - Manual Deployment Guide

## 🎯 Quick Deploy (5 minutes)

This guide will get you to a working v0.0.5 build.

---

## Step 1: Navigate to Project

```bash
cd ~/apps/jarvis/jarvis
```

---

## Step 2: Clean Up Problem Files

```bash
# Remove duplicate/conflicting files
rm -f src/main/java/com/yourname/jarvis/stats/StatisticsManager.java
rm -f src/main/java/com/yourname/jarvis/undo/UndoManager.java
rm -f src/main/java/com/yourname/jarvis/statistics/StatisticsManager.java
rm -f src/main/java/com/yourname/jarvis/building/UndoManager.java

# Clean build
mvn clean
```

---

## Step 3: Copy v0.0.5 Files

Copy these files from `/mnt/user-data/outputs/v0.0.5-clean/` to your project:

### Core Files:
```bash
# Main plugin class
cp /mnt/user-data/outputs/v0.0.5-clean/src/main/java/com/yourname/jarvis/Jarvis.java \
   src/main/java/com/yourname/jarvis/

# Build configuration
cp /mnt/user-data/outputs/v0.0.5-clean/pom.xml .

# Plugin metadata
cp /mnt/user-data/outputs/v0.0.5-clean/src/main/resources/plugin.yml \
   src/main/resources/
```

### Stub Classes:
```bash
# Create directories if needed
mkdir -p src/main/java/com/yourname/jarvis/building
mkdir -p src/main/java/com/yourname/jarvis/quests
mkdir -p src/main/java/com/yourname/jarvis/schematics

# Copy stubs
cp /mnt/user-data/outputs/v0.0.5-clean/src/main/java/com/yourname/jarvis/building/BuildingAssistant.java \
   src/main/java/com/yourname/jarvis/building/

cp /mnt/user-data/outputs/v0.0.5-clean/src/main/java/com/yourname/jarvis/quests/QuestSystem.java \
   src/main/java/com/yourname/jarvis/quests/

cp /mnt/user-data/outputs/v0.0.5-clean/src/main/java/com/yourname/jarvis/schematics/SchematicManager.java \
   src/main/java/com/yourname/jarvis/schematics/
```

---

## Step 4: Build

```bash
mvn clean package
```

**Expected output:**
```
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

**Your JAR:**
```
target/jarvis-0.0.5.jar
```

---

## Step 5: Deploy to Server

```bash
# 1. Stop server
./stop.sh  # or however you stop your server

# 2. Remove old version
rm /path/to/server/plugins/jarvis-*.jar

# 3. Copy new version
cp target/jarvis-0.0.5.jar /path/to/server/plugins/

# 4. Start server
./start.sh
```

---

## Step 6: Verify

In-game:
```
/jarvis summon
→ Should see: "Jarvis: At your service—let's make some magic."

/jarvis debug
→ Should show: v0.0.5

/jarvis mine
→ Should work with smart mining
```

---

## ✅ What Works in v0.0.5

### Fully Functional:
- ✅ `/jarvis summon` - Spawn Jarvis
- ✅ `/jarvis dismiss` - Remove Jarvis
- ✅ `/jarvis return` - Warp back
- ✅ `/jarvis mine` - **Smart mining with exposed ore priority**
- ✅ `/jarvis attack` - Combat mode
- ✅ `/jarvis battle <player>` - PvP mode
- ✅ `/jarvis loot` - Open inventory
- ✅ `/jarvis bell` - Get controller
- ✅ `/jarvis stop` - Stop current task
- ✅ `/jarvis debug` - Debug info
- ✅ `/jarvis reload` - Reload config

### Stubs (Not Implemented):
- ⚠️ `/jarvis build` - Shows "not implemented"
- ⚠️ `/jarvis quest` - Shows "not implemented"
- ⚠️ `/jarvis schematics` - Shows "not implemented"

---

## 🐛 Troubleshooting

### Build Still Fails?

Check for these issues:

1. **Java version:**
   ```bash
   java -version
   # Should show 17 or higher
   ```

2. **Maven version:**
   ```bash
   mvn -version
   # Should show 3.9 or higher
   ```

3. **Files in wrong place:**
   ```bash
   ls -la src/main/java/com/yourname/jarvis/
   # Should see: Jarvis.java and directories
   ```

4. **Still have duplicates:**
   ```bash
   find src -name "*Statistics*" -o -name "*Undo*"
   # Should NOT show files in /stats or /undo directories
   ```

### Server Won't Start?

1. Check Citizens is installed:
   ```bash
   ls /path/to/server/plugins/Citizens*
   ```

2. Check server logs:
   ```bash
   tail -f /path/to/server/logs/latest.log
   ```

3. Look for errors mentioning "Jarvis"

---

## 📊 File Structure After Deploy

Your project should look like this:

```
jarvis/
├── pom.xml (v0.0.5)
├── src/
│   └── main/
│       ├── java/com/yourname/jarvis/
│       │   ├── Jarvis.java (v0.0.5)
│       │   ├── DatabaseManager.java (existing)
│       │   ├── ai/
│       │   │   └── AIConnector.java (existing)
│       │   ├── building/
│       │   │   └── BuildingAssistant.java (STUB)
│       │   ├── commands/
│       │   │   └── JarvisCommands.java (existing)
│       │   ├── listeners/
│       │   │   └── ChatListener.java (existing)
│       │   ├── npc/
│       │   │   └── JarvisNPC.java (existing)
│       │   ├── quests/
│       │   │   └── QuestSystem.java (STUB)
│       │   ├── schematics/
│       │   │   └── SchematicManager.java (STUB)
│       │   └── ui/
│       │       ├── UIManager.java (existing)
│       │       └── ControllerBell.java (existing)
│       └── resources/
│           ├── plugin.yml (v0.0.5)
│           ├── config.yml (existing)
│           └── databases.yml (existing)
└── target/
    └── jarvis-0.0.5.jar (after build)
```

---

## 🎉 Success Criteria

Deployment is successful when:

1. ✅ `mvn clean package` completes with BUILD SUCCESS
2. ✅ `target/jarvis-0.0.5.jar` exists
3. ✅ Server starts without errors
4. ✅ `/jarvis summon` works in-game
5. ✅ `/jarvis debug` shows v0.0.5
6. ✅ Mining prioritizes exposed ores

---

## 🆘 If All Else Fails

Contact me with:
1. Full output of `mvn clean package`
2. Output of `find src -name "*.java" | head -20`
3. Output of `cat pom.xml | grep version`

I'll get you sorted out!

---

**Ready to deploy? Run the commands in Step 2-4!** 🚀
