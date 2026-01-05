# Jarvis v3.0 - Installation & Setup Guide

## 📋 Prerequisites Checklist

Before installing Jarvis, ensure you have:

- [ ] **Minecraft Server** (Purpur/Spigot/Paper 1.21+)
- [ ] **Java 17 JDK** installed
- [ ] **Maven 3.9+** installed
- [ ] **Citizens plugin** downloaded
- [ ] **WorldEdit plugin** downloaded
- [ ] **AI API Key** (Claude, OpenAI, Grok, or Gemini)

---

## 🔽 Step 1: Download Required Plugins

### Citizens Plugin (REQUIRED)
```
Download: https://www.spigotmc.org/resources/citizens.13811/
Version: Latest for 1.21
```

### WorldEdit Plugin (REQUIRED for Building)
```
Download: https://dev.bukkit.org/projects/worldedit
Version: 7.3.0 or later
```

---

## 🛠️ Step 2: Build Jarvis Plugin

### Option A: Build from Source (Recommended)

1. **Clone or extract the Jarvis source code**
   ```bash
   cd /path/to/jarvis
   ```

2. **Build with Maven**
   ```bash
   mvn clean package
   ```

3. **Locate the JAR file**
   ```
   Output: target/jarvis-0.0.1.jar
   ```

### Option B: Use Pre-built JAR
If you received a pre-built JAR, skip to Step 3.

---

## 📂 Step 3: Install on Server

1. **Stop your Minecraft server** (if running)
   ```bash
   ./stop.sh
   # or
   screen -X -S minecraft quit
   ```

2. **Copy plugins to plugins directory**
   ```bash
   cp citizens-X.X.X.jar /path/to/server/plugins/
   cp worldedit-bukkit-X.X.X.jar /path/to/server/plugins/
   cp jarvis-0.0.1.jar /path/to/server/plugins/
   ```

3. **Start your server**
   ```bash
   ./start.sh
   # or
   java -Xmx4G -Xms4G -jar server.jar nogui
   ```

4. **Wait for first-time setup**
   - Jarvis will create: `plugins/Jarvis/config.yml`
   - Jarvis will create: `plugins/Jarvis/databases.yml`
   - Jarvis will create: `plugins/Jarvis/database.db`

---

## 🔑 Step 4: Get AI API Key

Choose ONE AI provider and get an API key:

### Option 1: Claude (Recommended) 🌟
**Why:** Best context understanding, creative building, latest model

1. Visit: https://console.anthropic.com/
2. Sign up for an account
3. Navigate to "API Keys"
4. Click "Create Key"
5. Copy your key (starts with `sk-ant-`)

**Pricing:** Pay-as-you-go, ~$0.003 per request

### Option 2: OpenAI (GPT)
1. Visit: https://platform.openai.com/
2. Sign up for an account
3. Navigate to "API Keys"
4. Create new secret key
5. Copy your key (starts with `sk-`)

**Pricing:** Pay-as-you-go, varies by model

### Option 3: xAI Grok
1. Visit: https://x.ai/
2. Request API access
3. Generate API key
4. Copy your key

### Option 4: Google Gemini
1. Visit: https://makersuite.google.com/
2. Sign in with Google account
3. Generate API key
4. Copy your key

---

## ⚙️ Step 5: Configure Jarvis

1. **Stop the server again**
   ```bash
   ./stop.sh
   ```

2. **Edit config.yml**
   ```bash
   nano plugins/Jarvis/config.yml
   # or
   vim plugins/Jarvis/config.yml
   ```

3. **Add your AI provider configuration**

   **For Claude (Recommended):**
   ```yaml
   ai:
     provider: claude
     claude:
       api-key: "sk-ant-your-actual-key-here"
       model: "claude-sonnet-4-20250514"
       endpoint: "https://api.anthropic.com/v1/messages"
   ```

   **For OpenAI:**
   ```yaml
   ai:
     provider: openai
     openai:
       api-key: "sk-your-actual-key-here"
       model: "gpt-3.5-turbo"
   ```

4. **Configure features** (optional)
   ```yaml
   natural-language:
     enabled: true
     prefix: "jarvis"
     require-prefix: false
   
   quests:
     enabled: true
     max-active-per-player: 3
     reward-multiplier: 1.0
   
   build:
     fallback-material: minecraft:stone
   ```

5. **Save and exit**
   - nano: `Ctrl+X`, then `Y`, then `Enter`
   - vim: `:wq`

---

## 🚀 Step 6: Start and Test

1. **Start your server**
   ```bash
   ./start.sh
   ```

2. **Check console for success messages**
   ```
   [Jarvis] Jarvis AI Companion enabling...
   [Jarvis] AI Connector initialized with provider: claude
   [Jarvis] NPC system initialized.
   [Jarvis] Natural language processing enabled.
   [Jarvis] Building assistant initialized.
   [Jarvis] Quest system initialized.
   [Jarvis] Jarvis AI Companion v3.0 enabled successfully!
   ```

3. **Join your server and test**
   ```
   /jarvis
   ```
   You should see the help menu!

---

## ✅ Step 7: Verify Installation

Run these tests in-game:

### Test 1: Basic Commands
```
/jarvis summon
```
✅ Jarvis should appear near you

### Test 2: Natural Language
```
Chat: jarvis come here
```
✅ Jarvis should respond and move to you

### Test 3: AI Building (requires WorldEdit)
```
/jarvis build small house
```
✅ Should start building

### Test 4: Quest System
```
/jarvis quest new
```
✅ Should generate a quest

### Test 5: Debug Info
```
/jarvis debug
```
✅ Should show all systems as initialized

---

## 🔍 Troubleshooting Installation

### Problem: "Citizens not found"
**Solution:**
1. Verify Citizens plugin is in plugins folder
2. Check server console for Citizens errors
3. Ensure Citizens is compatible with your server version
4. Try redownloading Citizens

### Problem: "WorldEdit not found"
**Solution:**
1. Download WorldEdit from official source
2. Place in plugins folder
3. Restart server
4. Building features will be disabled without WorldEdit (other features work)

### Problem: "AI API error"
**Solutions:**
1. **Check API key:**
   - Open `plugins/Jarvis/config.yml`
   - Verify key is correct (no quotes issues, no extra spaces)
   - Ensure key matches your provider

2. **Verify API key validity:**
   - Log into your AI provider's console
   - Check if key is active
   - Verify you have credits/quota

3. **Test with curl (Linux/Mac):**
   ```bash
   # For Claude:
   curl https://api.anthropic.com/v1/messages \
     -H "x-api-key: YOUR_KEY" \
     -H "anthropic-version: 2023-06-01" \
     -H "content-type: application/json" \
     -d '{"model":"claude-sonnet-4-20250514","max_tokens":100,"messages":[{"role":"user","content":"test"}]}'
   ```

4. **Check firewall:**
   - Ensure server can make outbound HTTPS requests
   - Check if your hosting provider blocks API calls

### Problem: "Database init error"
**Solution:**
1. Check file permissions: `plugins/Jarvis/` should be writable
2. Delete `database.db` and restart server
3. Check disk space

### Problem: Natural language not responding
**Solutions:**
1. Check `natural-language.enabled: true` in config
2. Verify AI provider is configured correctly
3. Try with explicit prefix: "jarvis <command>"
4. Check server console for errors

### Problem: Building not working
**Solutions:**
1. Verify WorldEdit is installed: `/we version`
2. Check you have build permissions in the area
3. Try simpler structures first: `/jarvis build wall`
4. Check console for JSON parsing errors from AI

---

## 📝 Post-Installation Configuration

### Adjust Natural Language Sensitivity
If getting too many false positives:
```yaml
natural-language:
  require-prefix: true  # Only process messages starting with "jarvis"
```

### Adjust Quest Difficulty
```yaml
quests:
  reward-multiplier: 2.0  # Double all rewards
  max-active-per-player: 5  # Allow more concurrent quests
```

### Change AI Model
For better quality (costs more):
```yaml
ai:
  claude:
    model: "claude-opus-4-20250514"  # More powerful model
```

---

## 🔐 Permissions Setup

### Give admin permissions
```yaml
# In your permissions plugin (LuckPerms, etc.)
- jarvis.admin      # Reload, debug, quest clear
- jarvis.use        # Basic usage
- jarvis.menu.use   # Bell controller
```

### Server operators
Already have all permissions by default.

---

## 🔄 Updating Jarvis

1. **Backup your config**
   ```bash
   cp plugins/Jarvis/config.yml ~/jarvis-config-backup.yml
   ```

2. **Stop server**

3. **Replace JAR**
   ```bash
   rm plugins/jarvis-*.jar
   cp jarvis-NEW-VERSION.jar plugins/
   ```

4. **Start server**

5. **Check for config updates**
   - New config options are added automatically
   - Your settings are preserved

---

## 📊 Performance Tuning

### For Large Servers (50+ players)
```yaml
natural-language:
  enabled: true
  require-prefix: true  # Reduce processing load
```

### For Small Servers (1-10 players)
```yaml
natural-language:
  require-prefix: false  # More natural experience
quests:
  reward-multiplier: 1.5  # Faster progression
```

---

## 🆘 Getting Help

### Check Debug Info
```
/jarvis debug
```
Provides:
- AI provider and model
- API key status
- Active NPCs count
- Active tasks count
- Feature status

### View Logs
```bash
tail -f logs/latest.log | grep Jarvis
```

### Common Issues
1. AI not responding → Check API key and credits
2. NPC not spawning → Check Citizens installation
3. Building not working → Check WorldEdit installation
4. Commands not working → Check permissions

---

## ✨ First Time Usage Guide

After installation, try this sequence:

```bash
# 1. Get the controller bell
/jarvis bell

# 2. Summon Jarvis
/jarvis summon

# 3. Try natural language
Chat: jarvis start mining

# 4. Get a quest
/jarvis quest new

# 5. Build something
/jarvis build small cottage

# 6. Check Jarvis's inventory
/jarvis loot
```

---

## 🎓 Learning Resources

- **README.md** - Full documentation
- **FEATURES.md** - Detailed feature guide
- **This file** - Installation help

Need more help? Check:
- Server console logs
- `/jarvis debug` output
- AI provider status page

---

**Installation complete! Enjoy your AI-powered Jarvis companion! 🎉**
