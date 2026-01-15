package com.yourname.jarvis.quests;

import com.yourname.jarvis.Jarvis;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * QuestSystem - Hybrid template + AI quest generation
 *
 * Combines pre-made quest templates from QuestLibrary with
 * AI-generated quests for variety and dynamic gameplay.
 */
public class QuestSystem implements Listener {

    private final Jarvis plugin;
    private final QuestLibrary questLibrary;
    private final Map<UUID, List<Quest>> activeQuests = new HashMap<>();
    private final Random random = new Random();

    // Configuration
    private int maxQuestsPerPlayer = 3;
    private double aiGenerationChance = 0.3; // 30% AI, 70% template
    private double rewardMultiplier = 1.0;

    public QuestSystem(Jarvis plugin) {
        this.plugin = plugin;
        this.questLibrary = new QuestLibrary();
        loadConfig();

        // Register event listeners
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("Quest system initialized with " + questLibrary.getTemplateCount() + " templates");
    }

    private void loadConfig() {
        maxQuestsPerPlayer = plugin.getConfig().getInt("quests.max-active-per-player", 3);
        aiGenerationChance = plugin.getConfig().getDouble("quests.ai-generation-chance", 0.3);
        rewardMultiplier = plugin.getConfig().getDouble("quests.reward-multiplier", 1.0);
    }

    // ==================== QUEST DATA STRUCTURES ====================

    public static class Quest {
        public String id;
        public String title;
        public String description;
        public List<Objective> objectives;
        public Map<String, Integer> rewards;
        public long assignedTime;
        public boolean completed;
        public boolean fromTemplate;
        public String templateId;

        public Quest() {
            this.id = UUID.randomUUID().toString();
            this.objectives = new ArrayList<>();
            this.rewards = new HashMap<>();
            this.assignedTime = System.currentTimeMillis();
            this.completed = false;
        }

        public boolean isComplete() {
            return objectives.stream().allMatch(Objective::isComplete);
        }

        public int getProgressPercent() {
            if (objectives.isEmpty()) return 100;
            int total = 0;
            int current = 0;
            for (Objective obj : objectives) {
                total += obj.amount;
                current += Math.min(obj.current, obj.amount);
            }
            return total > 0 ? (current * 100) / total : 0;
        }
    }

    public static class Objective {
        public String type;      // mine, kill, collect, build
        public String target;    // Material or EntityType name
        public int amount;
        public int current;
        public String displayName;

        public Objective(String type, String target, int amount, String displayName) {
            this.type = type;
            this.target = target.toUpperCase().replace(" ", "_");
            this.amount = amount;
            this.current = 0;
            this.displayName = displayName != null ? displayName : target;
        }

        public boolean isComplete() {
            return current >= amount;
        }

        public void increment(int value) {
            current = Math.min(current + value, amount);
        }
    }

    // ==================== QUEST GENERATION ====================

    /**
     * Generate and assign a quest to player using hybrid approach
     */
    public void generateAndAssignQuest(Player player) {
        List<Quest> playerQuests = activeQuests.getOrDefault(player.getUniqueId(), new ArrayList<>());

        if (playerQuests.size() >= maxQuestsPerPlayer) {
            player.sendMessage(ChatColor.RED + "You already have " + maxQuestsPerPlayer + " active quests!");
            player.sendMessage(ChatColor.GRAY + "Complete or abandon a quest first with /jarvis quest clear");
            return;
        }

        int playerLevel = player.getLevel();

        // Hybrid approach: 70% template, 30% AI-generated
        if (random.nextDouble() < aiGenerationChance) {
            assignQuestFromAI(player, playerLevel);
        } else {
            assignQuestFromTemplate(player, playerLevel);
        }
    }

    /**
     * Assign a quest from the template library
     */
    public void assignQuestFromTemplate(Player player, int playerLevel) {
        QuestTemplate template = questLibrary.getRandomTemplateForLevel(playerLevel);

        if (template == null) {
            // Fallback to any template if none match level
            template = questLibrary.getRandomTemplateForLevel(1);
        }

        if (template == null) {
            player.sendMessage(ChatColor.RED + "No quests available. Please try again later.");
            return;
        }

        Quest quest = createQuestFromTemplate(template, playerLevel);
        addQuestToPlayer(player, quest);

        displayQuestAssignment(player, quest);
    }

    /**
     * Assign an AI-generated quest
     */
    public void assignQuestFromAI(Player player, int playerLevel) {
        player.sendMessage(ChatColor.GRAY + "Generating a unique quest for you...");

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String biome = player.getLocation().getBlock().getBiome().toString();
                    String activity = "general exploration";

                    String response = plugin.getAIConnector().generateQuest(playerLevel, biome, activity);
                    Quest quest = parseAIQuest(response);

                    if (quest != null) {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                addQuestToPlayer(player, quest);
                                displayQuestAssignment(player, quest);
                            }
                        }.runTask(plugin);
                    } else {
                        // Fallback to template
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                player.sendMessage(ChatColor.YELLOW + "AI quest generation failed, using template instead.");
                                assignQuestFromTemplate(player, playerLevel);
                            }
                        }.runTask(plugin);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("AI quest generation failed: " + e.getMessage());
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.sendMessage(ChatColor.YELLOW + "AI unavailable, using template quest.");
                            assignQuestFromTemplate(player, playerLevel);
                        }
                    }.runTask(plugin);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private Quest createQuestFromTemplate(QuestTemplate template, int playerLevel) {
        Quest quest = new Quest();
        quest.title = template.getTitle();
        quest.description = template.getDescription();
        quest.fromTemplate = true;
        quest.templateId = template.getId();

        // Copy objectives
        for (QuestTemplate.ObjectiveTemplate objTemplate : template.getObjectives()) {
            quest.objectives.add(new Objective(
                objTemplate.type,
                objTemplate.target,
                objTemplate.amount,
                objTemplate.displayName
            ));
        }

        // Copy scaled rewards
        Map<String, Integer> scaledRewards = template.getScaledRewards(playerLevel);
        for (Map.Entry<String, Integer> entry : scaledRewards.entrySet()) {
            int value = (int)(entry.getValue() * rewardMultiplier);
            quest.rewards.put(entry.getKey(), value);
        }

        return quest;
    }

    private Quest parseAIQuest(String jsonResponse) {
        try {
            JSONObject json = new JSONObject(jsonResponse);
            Quest quest = new Quest();
            quest.fromTemplate = false;

            quest.title = json.optString("title", "AI Quest");
            quest.description = json.optString("description", "Complete the objectives");

            // Parse objectives
            JSONArray objectives = json.optJSONArray("objectives");
            if (objectives != null) {
                for (int i = 0; i < objectives.length(); i++) {
                    JSONObject obj = objectives.getJSONObject(i);
                    String type = obj.optString("type", "collect");
                    String target = obj.optString("target", "DIAMOND");
                    int amount = obj.optInt("amount", 1);
                    String display = obj.optString("display", target);
                    quest.objectives.add(new Objective(type, target, amount, display));
                }
            } else {
                // Default objective if none provided
                quest.objectives.add(new Objective("mine", "COAL_ORE", 10, "coal ore"));
            }

            // Parse rewards
            JSONObject rewards = json.optJSONObject("rewards");
            if (rewards != null) {
                quest.rewards.put("xp", (int)(rewards.optInt("xp", 100) * rewardMultiplier));
                Iterator<String> keys = rewards.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!key.equals("xp")) {
                        quest.rewards.put(key.toUpperCase(), rewards.getInt(key));
                    }
                }
            } else {
                quest.rewards.put("xp", 100);
            }

            return quest;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse AI quest: " + e.getMessage());
            return null;
        }
    }

    // ==================== QUEST MANAGEMENT ====================

    private void addQuestToPlayer(Player player, Quest quest) {
        List<Quest> quests = activeQuests.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        quests.add(quest);
    }

    private void displayQuestAssignment(Player player, Quest quest) {
        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "========================================");
        player.sendMessage(ChatColor.GOLD + "  New Quest: " + ChatColor.YELLOW + quest.title);
        player.sendMessage(ChatColor.GREEN + "========================================");
        player.sendMessage(ChatColor.GRAY + quest.description);
        player.sendMessage("");
        player.sendMessage(ChatColor.AQUA + "Objectives:");
        for (Objective obj : quest.objectives) {
            String status = obj.isComplete() ? ChatColor.GREEN + "[DONE]" : ChatColor.GRAY + "[" + obj.current + "/" + obj.amount + "]";
            player.sendMessage(ChatColor.WHITE + "  - " + capitalizeWords(obj.type) + " " + obj.amount + " " + obj.displayName + " " + status);
        }
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "Rewards:");
        for (Map.Entry<String, Integer> reward : quest.rewards.entrySet()) {
            if (reward.getKey().equals("xp")) {
                player.sendMessage(ChatColor.GREEN + "  + " + reward.getValue() + " XP");
            } else {
                player.sendMessage(ChatColor.GREEN + "  + " + reward.getValue() + "x " + formatMaterial(reward.getKey()));
            }
        }
        player.sendMessage(ChatColor.GREEN + "========================================");
        player.sendMessage(ChatColor.GRAY + (quest.fromTemplate ? "Template Quest" : "AI-Generated Quest"));
    }

    /**
     * Show quest status for player
     */
    public void showQuestStatus(Player player) {
        List<Quest> quests = activeQuests.get(player.getUniqueId());

        if (quests == null || quests.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "You have no active quests.");
            player.sendMessage(ChatColor.GRAY + "Use /jarvis quest new to get a quest!");
            return;
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "======== Active Quests (" + quests.size() + "/" + maxQuestsPerPlayer + ") ========");

        for (int i = 0; i < quests.size(); i++) {
            Quest quest = quests.get(i);
            int progress = quest.getProgressPercent();
            String progressBar = createProgressBar(progress);

            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "" + (i + 1) + ". " + quest.title + " " + ChatColor.GRAY + "[" + progress + "%]");
            player.sendMessage(ChatColor.WHITE + "   " + progressBar);

            for (Objective obj : quest.objectives) {
                String checkmark = obj.isComplete() ? ChatColor.GREEN + "[DONE] " : ChatColor.RED + "[ ] ";
                player.sendMessage(ChatColor.GRAY + "   " + checkmark + ChatColor.WHITE +
                    capitalizeWords(obj.type) + " " + obj.displayName + ": " +
                    ChatColor.YELLOW + obj.current + "/" + obj.amount);
            }
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "=====================================");
    }

    /**
     * Clear all quests for player
     */
    public void clearQuests(Player player) {
        List<Quest> quests = activeQuests.remove(player.getUniqueId());
        if (quests != null && !quests.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "Cleared " + quests.size() + " quest(s).");
        } else {
            player.sendMessage(ChatColor.GRAY + "You have no quests to clear.");
        }
    }

    // ==================== PROGRESS TRACKING ====================

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Material material = event.getBlock().getType();
        updateProgress(player, "mine", material.toString(), 1);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity killer = event.getEntity().getKiller();
        if (killer instanceof Player) {
            Player player = (Player) killer;
            String entityType = event.getEntityType().toString();
            updateProgress(player, "kill", entityType, 1);
        }
    }

    @EventHandler
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();
        ItemStack item = event.getItem().getItemStack();
        String material = item.getType().toString();
        int amount = item.getAmount();
        updateProgress(player, "collect", material, amount);
    }

    private void updateProgress(Player player, String type, String target, int amount) {
        List<Quest> quests = activeQuests.get(player.getUniqueId());
        if (quests == null || quests.isEmpty()) return;

        for (Quest quest : quests) {
            if (quest.completed) continue;

            boolean updated = false;
            for (Objective obj : quest.objectives) {
                if (obj.isComplete()) continue;
                if (!obj.type.equalsIgnoreCase(type)) continue;
                if (!matchesTarget(obj.target, target)) continue;

                obj.increment(amount);
                updated = true;

                // Notify on objective completion
                if (obj.isComplete()) {
                    player.sendMessage(ChatColor.GREEN + "[Quest] " + ChatColor.WHITE +
                        "Completed: " + capitalizeWords(obj.type) + " " + obj.displayName);
                }
            }

            // Check quest completion
            if (updated && quest.isComplete()) {
                completeQuest(player, quest);
            }
        }
    }

    private boolean matchesTarget(String objectiveTarget, String actualTarget) {
        String objNorm = objectiveTarget.toUpperCase().replace(" ", "_");
        String actNorm = actualTarget.toUpperCase().replace(" ", "_");

        // Exact match
        if (objNorm.equals(actNorm)) return true;

        // Handle deepslate variants
        if (actNorm.startsWith("DEEPSLATE_") && objNorm.equals(actNorm.replace("DEEPSLATE_", ""))) {
            return true;
        }

        // Handle ore variants (IRON_ORE matches both IRON_ORE and DEEPSLATE_IRON_ORE)
        if (actNorm.endsWith("_ORE") && objNorm.endsWith("_ORE")) {
            String baseAct = actNorm.replace("DEEPSLATE_", "");
            if (objNorm.equals(baseAct)) return true;
        }

        return false;
    }

    // ==================== QUEST COMPLETION ====================

    private void completeQuest(Player player, Quest quest) {
        quest.completed = true;

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "========================================");
        player.sendMessage(ChatColor.GREEN + "  QUEST COMPLETE: " + ChatColor.YELLOW + quest.title);
        player.sendMessage(ChatColor.GOLD + "========================================");

        // Give rewards
        for (Map.Entry<String, Integer> reward : quest.rewards.entrySet()) {
            if (reward.getKey().equals("xp")) {
                player.giveExp(reward.getValue());
                player.sendMessage(ChatColor.GREEN + "  + " + reward.getValue() + " XP");
            } else {
                try {
                    Material mat = Material.valueOf(reward.getKey().toUpperCase());
                    ItemStack item = new ItemStack(mat, reward.getValue());
                    HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);

                    // Drop overflow items at player's feet
                    for (ItemStack dropped : overflow.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), dropped);
                    }

                    player.sendMessage(ChatColor.GREEN + "  + " + reward.getValue() + "x " + formatMaterial(reward.getKey()));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid reward material: " + reward.getKey());
                }
            }
        }

        player.sendMessage(ChatColor.GOLD + "========================================");

        // Remove completed quest
        List<Quest> quests = activeQuests.get(player.getUniqueId());
        if (quests != null) {
            quests.remove(quest);
        }

        // Play completion sound
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
    }

    // ==================== UTILITY METHODS ====================

    private String createProgressBar(int percent) {
        int filled = percent / 5; // 20 char bar
        int empty = 20 - filled;
        return ChatColor.GREEN + repeat("█", filled) + ChatColor.GRAY + repeat("░", empty);
    }

    private String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    private String capitalizeWords(String str) {
        if (str == null || str.isEmpty()) return str;
        String[] words = str.toLowerCase().replace("_", " ").split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                  .append(word.substring(1))
                  .append(" ");
            }
        }
        return sb.toString().trim();
    }

    private String formatMaterial(String material) {
        return capitalizeWords(material.replace("_", " "));
    }

    // ==================== GETTERS ====================

    public QuestLibrary getQuestLibrary() {
        return questLibrary;
    }

    public int getActiveQuestCount(Player player) {
        List<Quest> quests = activeQuests.get(player.getUniqueId());
        return quests != null ? quests.size() : 0;
    }
}
