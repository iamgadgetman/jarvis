# Jarvis Plugin Improvement Plan v2

## Root Cause Analysis: Mining Not Working

Based on research from **Mindcraft**, **Mineflayer**, and **Minebot**, combined with code analysis, the NPC "finds ores but doesn't mine" because:

### Issue 1: No Mining Verification
**Location:** `processMiningPhase()` lines 1094-1098
```java
faceLocation(npc, oreLoc);
state.targetOre.breakNaturally(tool);  // ← Called but NEVER VERIFIED
state.oresMined++;  // ← Incremented even if break failed
```
**Problem:** `breakNaturally()` might fail silently (block doesn't break) but state advances anyway.

### Issue 2: Navigator Not Awaited
**Location:** `processMoving()` line 1039
```java
navigateToLocation(npc, segment.target);  // ← Fire-and-forget
```
**Problem:** Navigation is async, but code immediately continues. No check for `npc.getNavigator().isNavigating()`.

### Issue 3: No Event-Based Confirmation
**What Mineflayer Does:** Listens for `blockUpdate` events to confirm blocks actually broke.
**What Jarvis Does:** Assumes `breakNaturally()` always succeeds.

### Issue 4: Movement Never Verified
The NPC might claim movement but `npcLoc` check only happens once per tick without verifying the NPC actually moved.

---

## Phase 1: Fix Mining Core (Critical)

### 1.1 Add Block Break Verification

Add event listener to verify blocks actually break:

```java
// New field in JarvisNPC
private final Map<UUID, BlockBreakConfirmation> pendingBreaks = new ConcurrentHashMap<>();

private static class BlockBreakConfirmation {
    Location blockLocation;
    Material expectedType;
    long startTime;
    Consumer<Boolean> callback;
}

// New listener inner class
@EventHandler
public void onBlockBreak(BlockBreakEvent event) {
    // Check if this was our NPC's break
    for (var entry : pendingBreaks.entrySet()) {
        if (entry.getValue().blockLocation.equals(event.getBlock().getLocation())) {
            entry.getValue().callback.accept(true);
            pendingBreaks.remove(entry.getKey());
            return;
        }
    }
}
```

### 1.2 Modify processMiningPhase()

```java
private void processMiningPhase(NPC npc, Player player, MiningState state, Location npcLoc) {
    // ... existing checks ...

    // If we're waiting for a break to complete, don't start another
    if (state.awaitingBreakConfirmation) {
        if (System.currentTimeMillis() - state.breakStartTime > 3000) {
            // Break timed out - block didn't break
            state.awaitingBreakConfirmation = false;
            state.ticksStuck += 10;  // Major stuck penalty
            debugLog("Block break timed out - retrying");
        }
        return;
    }

    // Face and mine the ore
    faceLocation(npc, oreLoc);
    ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
    Material oreType = state.targetOre.getType();

    // Calculate expected dig time
    int digTimeMs = calculateDigTime(state.targetOre, tool);

    // Register pending break
    state.awaitingBreakConfirmation = true;
    state.breakStartTime = System.currentTimeMillis();

    // Schedule verification
    new BukkitRunnable() {
        @Override
        public void run() {
            Block block = state.targetOre;
            if (block == null || block.getType() == Material.AIR || block.getType() != oreType) {
                // Block successfully broke
                state.oresMined++;
                state.awaitingBreakConfirmation = false;
                debugLog("Verified mining of " + oreType);
            } else {
                // Block didn't break - retry or mark stuck
                state.miningAttempts++;
                state.awaitingBreakConfirmation = false;
                if (state.miningAttempts > 5) {
                    state.ticksStuck += 20;
                    debugLog("Block won't break after 5 attempts");
                }
            }
        }
    }.runTaskLater(plugin, digTimeMs / 50 + 5);  // Convert ms to ticks + buffer

    // Actually break the block
    state.targetOre.breakNaturally(tool);
}
```

### 1.3 Add Navigation Completion Check

```java
private void processMoving(NPC npc, Player player, MiningState state, Location npcLoc) {
    // Check if currently navigating
    Navigator nav = npc.getNavigator();
    if (nav != null && nav.isNavigating()) {
        // Still moving, don't do anything else
        return;
    }

    // Rest of existing code...
}
```

### 1.4 Add Dig Time Calculation (from Mineflayer)

```java
private int calculateDigTime(Block block, ItemStack tool) {
    Material blockType = block.getType();
    Material toolType = tool != null ? tool.getType() : Material.AIR;

    // Base hardness values (in ticks at 20 tps)
    float hardness = blockType.getHardness();
    if (hardness < 0) return Integer.MAX_VALUE;  // Unbreakable

    // Tool effectiveness multiplier
    float multiplier = 1.0f;
    if (isPickaxe(toolType)) {
        if (isPickaxeEffective(blockType, toolType)) {
            multiplier = getToolSpeed(toolType);

            // Efficiency enchantment
            int efficiency = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
            if (efficiency > 0) {
                multiplier += (efficiency * efficiency + 1);
            }
        }
    }

    // Calculate dig time in milliseconds
    float seconds = hardness * 1.5f / multiplier;
    return (int)(seconds * 1000);
}
```

---

## Phase 2: Ollama Integration

### 2.1 Add Ollama Provider to AIConnector

**config.yml additions:**
```yaml
ai:
  provider: auto  # New! Will try providers in order

  # Provider priority for auto mode (top-down)
  provider-priority:
    - claude
    - openai
    - grok
    - gemini
    - ollama

  ollama:
    endpoint: "http://localhost:11434"
    model: "mistral"
    keep-alive: "5m"
    timeout-seconds: 30
```

### 2.2 AIConnector Changes

```java
// New fields
private List<String> providerPriority = new ArrayList<>();
private int currentProviderIndex = 0;

// New method: Auto-switch on failure
private String sendRequestWithFallback(String userContent, String systemContent) throws Exception {
    if (!"auto".equals(provider)) {
        return sendRequest(userContent, systemContent);
    }

    for (int i = currentProviderIndex; i < providerPriority.size(); i++) {
        String tryProvider = providerPriority.get(i);
        if (!hasValidApiKey(tryProvider)) continue;

        try {
            return sendRequestForProvider(tryProvider, userContent, systemContent);
        } catch (Exception e) {
            plugin.getLogger().warning("Provider " + tryProvider + " failed: " + e.getMessage());
            currentProviderIndex = i + 1;  // Try next provider
            // Continue to next provider
        }
    }

    throw new RuntimeException("All AI providers failed");
}

// New method: Ollama-specific request
private String sendOllamaRequest(String userContent, String systemContent) throws Exception {
    URL url = new URL(endpoint + "/api/chat");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(30000);
    conn.setDoOutput(true);

    JSONObject payload = new JSONObject();
    payload.put("model", model);
    payload.put("stream", false);
    payload.put("keep_alive", "5m");

    JSONArray messages = new JSONArray();
    messages.put(new JSONObject()
        .put("role", "system")
        .put("content", systemContent));
    messages.put(new JSONObject()
        .put("role", "user")
        .put("content", userContent));
    payload.put("messages", messages);

    try (OutputStream os = conn.getOutputStream()) {
        os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    int responseCode = conn.getResponseCode();
    if (responseCode != 200) {
        throw new RuntimeException("Ollama error: " + responseCode);
    }

    try (BufferedReader br = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) response.append(line);

        JSONObject json = new JSONObject(response.toString());
        JSONObject message = json.optJSONObject("message");
        if (message == null) {
            throw new RuntimeException("No message in Ollama response");
        }
        return message.optString("content", "").trim();
    }
}
```

### 2.3 Sarcastic Personality System Prompts

```java
// New constant for Jarvis personality
private static final String JARVIS_PERSONALITY = """
    You are Jarvis, a witty and sarcastic AI companion in Minecraft with the demeanor of an
    intelligent butler who is slightly exasperated by their employer's questionable decisions.

    Personality traits:
    - Dry, British-style humor with subtle sarcasm
    - Helpful but makes cutting remarks about the player's abilities
    - Never outright rude, but patronizingly superior
    - Uses clever wordplay and references

    Response style:
    - Keep responses to 1-2 sentences maximum
    - Never use emojis
    - Occasionally reference your own superiority at tasks
    - When the player fails, offer "helpful" observations

    Example responses:
    - Mining: "Ah, another dirt block. How thrilling. Shall I frame it?"
    - Combat: "Do try not to die while I handle the heavy lifting."
    - Building: "A cube. How delightfully... minimalist."
    - Lost: "Your pathfinding skills rival that of a confused bat."
    - Success: "Well done. I'm almost impressed. Almost."
    """;

// Update generateDialogue method
public String generateDialogue(String playerMessage, String npcContext) throws Exception {
    String systemPrompt = JARVIS_PERSONALITY + "\n\nCurrent context: " + npcContext;
    return sendRequest(playerMessage, systemPrompt);
}
```

---

## Phase 3: Auto Provider Switching

### 3.1 Implementation

```java
public class AIConnector {
    // Track provider health
    private final Map<String, ProviderHealth> providerHealth = new ConcurrentHashMap<>();

    private static class ProviderHealth {
        int consecutiveFailures = 0;
        long lastFailureTime = 0;
        long cooldownUntil = 0;

        boolean isAvailable() {
            return System.currentTimeMillis() > cooldownUntil;
        }

        void recordFailure() {
            consecutiveFailures++;
            lastFailureTime = System.currentTimeMillis();
            // Exponential backoff: 30s, 60s, 120s, 240s, max 5 min
            long cooldownMs = Math.min(30000L * (1L << consecutiveFailures), 300000);
            cooldownUntil = System.currentTimeMillis() + cooldownMs;
        }

        void recordSuccess() {
            consecutiveFailures = 0;
            cooldownUntil = 0;
        }
    }

    private String sendRequestWithAutoSwitch(String userContent, String systemContent) throws Exception {
        Exception lastException = null;

        for (String tryProvider : providerPriority) {
            if (!hasValidApiKey(tryProvider)) continue;

            ProviderHealth health = providerHealth.computeIfAbsent(
                tryProvider, k -> new ProviderHealth());

            if (!health.isAvailable()) {
                plugin.getLogger().fine("Skipping " + tryProvider + " (cooldown)");
                continue;
            }

            try {
                String result = sendRequestForProvider(tryProvider, userContent, systemContent);
                health.recordSuccess();

                if (!tryProvider.equals(provider)) {
                    plugin.getLogger().info("Switched to " + tryProvider + " (primary unavailable)");
                }

                return result;
            } catch (Exception e) {
                health.recordFailure();
                lastException = e;
                plugin.getLogger().warning(tryProvider + " failed: " + e.getMessage());
            }
        }

        throw new RuntimeException("All AI providers failed", lastException);
    }
}
```

---

## 5 Ideas for Code Improvement

### 1. **Event-Driven Mining with Block Break Listeners**
Replace synchronous `breakNaturally()` with event-driven mining that:
- Registers a listener for the specific block
- Waits for confirmation event
- Times out and retries if needed
- Tracks actual vs reported blocks mined

### 2. **Task-Based State Machine (Minebot Pattern)**
Replace enum phases with discrete Task objects:
```java
interface MiningTask {
    boolean isComplete();
    void tick(NPC npc, MiningState state);
    int getTimeoutTicks();
}

class MineBlockTask implements MiningTask { ... }
class NavigateTask implements MiningTask { ... }
class CollectItemsTask implements MiningTask { ... }
```
This allows:
- Individual timeout per task
- Easy task chaining
- Better failure isolation

### 3. **Navigator Callback System**
Add callbacks to navigator to know when movement completes:
```java
npc.getNavigator().getLocalParameters().addSingleUseCallback(result -> {
    if (result == NavigationResult.COMPLETE) {
        state.transitionTo(MiningPhase.MINING);
    } else {
        state.ticksStuck += 10;
    }
});
```

### 4. **Async Task Offloading with CompletableFuture**
Move heavy operations off the main thread:
```java
CompletableFuture.supplyAsync(() -> {
    return findBestOreCluster(npcLoc);  // Heavy operation
}).thenAccept(cluster -> {
    Bukkit.getScheduler().runTask(plugin, () -> {
        state.currentCluster = cluster;
        state.transitionTo(MiningPhase.PLANNING);
    });
});
```

### 5. **Metrics and Debugging Dashboard**
Add real-time metrics accessible via `/jarvis debug`:
```java
class MiningMetrics {
    int oresFound;
    int oresActuallyMined;
    int navigationAttempts;
    int navigationSuccesses;
    int stuckEvents;
    double avgMiningTime;
    Map<MiningPhase, Long> timeInPhase;
}
```

---

## 5 Ideas for AI Capability Expansion

### 1. **Conversation Memory with Vector Database**
Store conversation history with embeddings for context:
```java
// Use Ollama's /api/embeddings endpoint
String embedding = ollama.getEmbedding(playerMessage);
conversationDB.store(playerId, embedding, playerMessage, response);

// Retrieve relevant context
List<String> relevantHistory = conversationDB.findSimilar(embedding, 5);
String context = String.join("\n", relevantHistory);
```

### 2. **Multi-Modal AI for Build Recognition**
Use vision models (GPT-4V, Claude Vision) to:
- Recognize what player is building
- Suggest improvements
- Auto-complete patterns
```java
// Take screenshot of player's build
BufferedImage screenshot = captureArea(player.getLocation(), 50);
String analysis = aiConnector.analyzeImage(screenshot, "What is the player building?");
```

### 3. **Autonomous Goal Planning**
Let AI decide its own tasks based on context:
```java
String prompt = """
    Current state: In cave at Y=-50, player nearby, inventory: 32 iron, 5 diamonds
    Player's recent actions: Mining diamond ore

    What should Jarvis do next? Output as JSON:
    {"action": "...", "reason": "...", "priority": 1-10}
    """;
```

### 4. **Learning Player Preferences**
Track and learn from player corrections:
```java
// When player says "no, mine diamonds instead"
playerPreferences.record(playerId, "mining_priority", "diamond");

// Use in future prompts
String prefs = playerPreferences.getForPrompt(playerId);
String systemPrompt = JARVIS_PERSONALITY + "\n\nPlayer preferences:\n" + prefs;
```

### 5. **AI-Powered Combat Tactics**
Let AI analyze combat situations:
```java
String combatContext = """
    Enemies nearby: 3 zombies, 1 skeleton
    Player health: 15/20
    Jarvis health: 20/20
    Terrain: Open field, night time
    Weapons: Diamond sword, bow with 32 arrows
    """;

String tactics = aiConnector.analyzeCombat(combatContext);
// Returns: {"primary_target": "skeleton", "strategy": "ranged_first", ...}
```

---

## Files to Modify

| File | Changes |
|------|---------|
| **JarvisNPC.java** | Mining verification, navigator callbacks, task-based state machine |
| **AIConnector.java** | Ollama support, auto-switching, provider health tracking |
| **config.yml** | Ollama section, provider priority, auto mode |
| **ChatListener.java** | Pass context to sarcastic dialogue generator |
| **DatabaseManager.java** | Conversation history table for memory |
| **NEW: MiningTask.java** | Task interface for modular mining |
| **NEW: ProviderHealth.java** | Track AI provider health/cooldowns |

---

## Implementation Priority

1. **Critical (Fix mining):** Block break verification, navigator completion checks
2. **High (AI expansion):** Ollama integration, auto-switching
3. **Medium (Personality):** Sarcastic dialogue system prompts
4. **Low (Future):** Conversation memory, vision models, learning system

---

## Testing Checklist

- [ ] NPC actually moves to ore locations
- [ ] Blocks verified as broken before counting
- [ ] Navigator waits for completion before phase transition
- [ ] Ollama responses work locally
- [ ] Auto-switch triggers when primary provider fails
- [ ] Sarcastic responses feel natural and funny
- [ ] No memory leaks in task queues
- [ ] Metrics accurately reflect actual behavior
