package com.yourname.jarvis.quests;

import com.yourname.jarvis.Jarvis;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class QuestSystem implements Listener {

    private final Jarvis plugin;
    private final Map<UUID, List<Quest>> activeQuests = new HashMap<>();
    private final boolean enabled;
    private final int maxActiveQuests;
    private final double rewardMultiplier;

    public static class Quest {
        String id;
        String title;
        String description;
        List<Objective> objectives;
        Map<String, Integer> rewards; // xp, itemType -> amount
        long assignedTime;
        boolean completed;

        public Quest(String id, String title, String description) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.objectives = new ArrayList<>();
            this.rewards = new HashMap<>();
            this.assignedTime = System.currentTimeMillis();
            this.completed = false;
        }

        public boolean isComplete() {
            return objectives.stream().allMatch(Objective::isComplete);
        }

        public int getProgress() {
            int total = objectives.size();
            int complete = (int) objectives.stream().filter(Objective::isComplete).count();
            return (complete * 100) / total;
        }
    }

    public static class Objective {
        String type; // mine, kill, collect, build
        String target; // material name or entity type
        int amount;
        int current;

        public Objective(String type, String target, int amount) {
            this.type = type;
            this.target = target;
            this.amount = amount;
            this.current = 0;
        }

        public boolean isComplete() {
            return current >= amount;
        }

        public void increment(int value) {
            current = Math.min(current + value, amount);
        }

        public String getDisplayString() {
            return String.format("%s %d/%d %s", 
                    type.substring(0, 1).toUpperCase() + type.substring(1),
                    current, amount, target);
        }
    }

    public QuestSystem(Jarvis plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("quests.enabled", true);
        this.maxActiveQuests = plugin.getConfig().getInt("quests.max-active-per-player", 3);
        this.rewardMultiplier = plugin.getConfig().getDouble("quests.reward-multiplier", 1.0);
        
        if (enabled) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            loadQuestsFromDatabase();
        }
    }

    public void generateAndAssignQuest(Player player) {
        if (!enabled) {
            player.sendMessage(ChatColor.RED + "Quests are disabled!");
            return;
        }

        List<Quest> playerQuests = activeQuests.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        
        if (playerQuests.size() >= maxActiveQuests) {
            player.sendMessage(ChatColor.RED + "Jarvis: You already have too many active quests! (" + maxActiveQuests + " max)");
            return;
        }

        player.sendMessage(ChatColor.GOLD + "Jarvis: Let me find something interesting for you...");

        // Generate quest asynchronously
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    int playerLevel = player.getLevel();
                    String biome = player.getLocation().getBlock().getBiome().toString();
                    String recentActivity = "exploring"; // Could be enhanced with more tracking

                    String questJson = plugin.getAIConnector().generateQuest(playerLevel, biome, recentActivity);
                    
                    // Parse and assign quest on main thread
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            assignQuest(player, questJson);
                        }
                    }.runTask(plugin);
                    
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to generate quest: " + e.getMessage());
                    
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            // Fallback to simple quest
                            assignSimpleQuest(player);
                        }
                    }.runTask(plugin);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void assignQuest(Player player, String questJson) {
        try {
            // Clean JSON
            questJson = questJson.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            
            JSONObject questData = new JSONObject(questJson);
            String title = questData.getString("title");
            String description = questData.getString("description");
            
            Quest quest = new Quest(UUID.randomUUID().toString(), title, description);
            
            // Parse objectives
            JSONArray objectivesArray = questData.getJSONArray("objectives");
            for (int i = 0; i < objectivesArray.length(); i++) {
                JSONObject obj = objectivesArray.getJSONObject(i);
                String type = obj.getString("type");
                String target = obj.getString("target");
                int amount = obj.getInt("amount");
                
                quest.objectives.add(new Objective(type, target, amount));
            }
            
            // Parse rewards
            JSONObject rewards = questData.optJSONObject("rewards");
            if (rewards != null) {
                quest.rewards.put("xp", (int)(rewards.optInt("xp", 100) * rewardMultiplier));
                
                JSONArray items = rewards.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        String material = item.getString("material").replace("minecraft:", "");
                        int amount = item.getInt("amount");
                        quest.rewards.put(material, amount);
                    }
                }
            }
            
            // Assign quest
            List<Quest> playerQuests = activeQuests.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
            playerQuests.add(quest);
            saveQuestToDatabase(player, quest);
            
            // Notify player
            player.sendMessage(ChatColor.GREEN + "═══════════════════════════════");
            player.sendMessage(ChatColor.GOLD + "📜 New Quest: " + ChatColor.YELLOW + title);
            player.sendMessage(ChatColor.GRAY + description);
            player.sendMessage(ChatColor.AQUA + "Objectives:");
            for (Objective obj : quest.objectives) {
                player.sendMessage(ChatColor.WHITE + "  • " + obj.getDisplayString());
            }
            player.sendMessage(ChatColor.GREEN + "═══════════════════════════════");
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse quest JSON: " + e.getMessage());
            assignSimpleQuest(player);
        }
    }

    private void assignSimpleQuest(Player player) {
        Quest quest = new Quest(UUID.randomUUID().toString(), "Mining Expedition", "Collect some basic resources");
        quest.objectives.add(new Objective("mine", "COAL_ORE", 10));
        quest.objectives.add(new Objective("mine", "IRON_ORE", 5));
        quest.rewards.put("xp", 50);
        quest.rewards.put("DIAMOND", 1);
        
        List<Quest> playerQuests = activeQuests.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        playerQuests.add(quest);
        saveQuestToDatabase(player, quest);
        
        player.sendMessage(ChatColor.GOLD + "Jarvis: Here's a simple quest to get you started!");
        showQuestDetails(player, quest);
    }

    private void showQuestDetails(Player player, Quest quest) {
        player.sendMessage(ChatColor.GREEN + "═══════════════════════════════");
        player.sendMessage(ChatColor.GOLD + "📜 " + quest.title);
        player.sendMessage(ChatColor.GRAY + quest.description);
        player.sendMessage(ChatColor.AQUA + "Objectives:");
        for (Objective obj : quest.objectives) {
            String color = obj.isComplete() ? ChatColor.GREEN.toString() : ChatColor.WHITE.toString();
            String checkmark = obj.isComplete() ? "✓ " : "  ";
            player.sendMessage(color + checkmark + "• " + obj.getDisplayString());
        }
        player.sendMessage(ChatColor.YELLOW + "Progress: " + quest.getProgress() + "%");
        player.sendMessage(ChatColor.GREEN + "═══════════════════════════════");
    }

    public void showQuestStatus(Player player) {
        List<Quest> quests = activeQuests.get(player.getUniqueId());
        
        if (quests == null || quests.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "Jarvis: You don't have any active quests.");
            player.sendMessage(ChatColor.GRAY + "Say 'jarvis give me a quest' to get started!");
            return;
        }

        player.sendMessage(ChatColor.GOLD + "═══════════════════════════════");
        player.sendMessage(ChatColor.GOLD + "📋 Your Active Quests");
        player.sendMessage(ChatColor.GOLD + "═══════════════════════════════");
        
        for (int i = 0; i < quests.size(); i++) {
            Quest quest = quests.get(i);
            player.sendMessage(ChatColor.YELLOW + "" + (i + 1) + ". " + quest.title + 
                    ChatColor.GRAY + " (" + quest.getProgress() + "%)");
            
            for (Objective obj : quest.objectives) {
                String color = obj.isComplete() ? ChatColor.GREEN.toString() : ChatColor.WHITE.toString();
                String checkmark = obj.isComplete() ? "✓" : "○";
                player.sendMessage(color + "   " + checkmark + " " + obj.getDisplayString());
            }
        }
        player.sendMessage(ChatColor.GOLD + "═══════════════════════════════");
    }

    // Event handlers for quest progress
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Material broken = event.getBlock().getType();
        
        updateQuestProgress(player, "mine", broken.toString(), 1);
        
        // Also count as "collect" for items that drop when broken
        updateQuestProgress(player, "collect", broken.toString(), 1);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() instanceof Player) {
            Player player = (Player) event.getEntity().getKiller();
            EntityType type = event.getEntityType();
            updateQuestProgress(player, "kill", type.toString(), 1);
        }
    }

    @EventHandler
    public void onPlayerPickupItem(org.bukkit.event.player.PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        Material material = event.getItem().getItemStack().getType();
        int amount = event.getItem().getItemStack().getAmount();
        
        // Track collection objectives
        updateQuestProgress(player, "collect", material.toString(), amount);
    }

    @EventHandler
    public void onEntityPickupItem(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            Material material = event.getItem().getItemStack().getType();
            int amount = event.getItem().getItemStack().getAmount();
            
            // Track collection objectives
            updateQuestProgress(player, "collect", material.toString(), amount);
        }
    }

    private void updateQuestProgress(Player player, String type, String target, int amount) {
        List<Quest> quests = activeQuests.get(player.getUniqueId());
        if (quests == null || quests.isEmpty()) return;

        boolean updated = false;
        for (Quest quest : quests) {
            if (quest.completed) continue;
            
            for (Objective obj : quest.objectives) {
                if (obj.type.equalsIgnoreCase(type) && matchesTarget(obj.target, target)) {
                    int oldProgress = obj.current;
                    obj.increment(amount);
                    
                    if (obj.current > oldProgress) {
                        updated = true;
                        
                        // Notify progress
                        if (obj.isComplete()) {
                            player.sendMessage(ChatColor.GREEN + "✓ Objective complete: " + obj.getDisplayString());
                        } else {
                            player.sendMessage(ChatColor.GRAY + "Quest progress: " + obj.getDisplayString());
                        }
                    }
                }
            }
            
            // Check if quest is complete
            if (quest.isComplete() && !quest.completed) {
                quest.completed = true;
                completeQuest(player, quest);
            }
        }

        if (updated) {
            saveQuestProgress(player);
        }
    }

    private boolean matchesTarget(String objectiveTarget, String actualTarget) {
        // Normalize both strings
        String objNorm = objectiveTarget.toUpperCase().replace(" ", "_");
        String actNorm = actualTarget.toUpperCase().replace(" ", "_");
        
        // Direct match
        if (objNorm.equals(actNorm)) return true;
        
        // Match without Material. prefix
        if (actNorm.startsWith("MATERIAL.")) {
            actNorm = actNorm.substring(9);
        }
        if (objNorm.startsWith("MATERIAL.")) {
            objNorm = objNorm.substring(9);
        }
        
        // Contains match (e.g., "POPPY" matches "POPPY" or "RED_FLOWER")
        if (objNorm.contains(actNorm) || actNorm.contains(objNorm)) return true;
        
        // Common aliases for flowers
        if (objNorm.contains("FLOWER") || objNorm.contains("ROSE") || objNorm.contains("POPPY") || 
            objNorm.contains("DANDELION") || objNorm.contains("TULIP") || objNorm.contains("ORCHID")) {
            if (actNorm.contains("FLOWER") || actNorm.contains("ROSE") || actNorm.contains("POPPY") || 
                actNorm.contains("DANDELION") || actNorm.contains("TULIP") || actNorm.contains("ORCHID")) {
                return true;
            }
        }
        
        return false;
    }

    private void completeQuest(Player player, Quest quest) {
        player.sendMessage(ChatColor.GOLD + "════════════════════════════════");
        player.sendMessage(ChatColor.GREEN + "✓ Quest Complete: " + ChatColor.YELLOW + quest.title);
        player.sendMessage(ChatColor.GRAY + "Well done!");
        
        // Give rewards
        if (quest.rewards.containsKey("xp")) {
            int xp = quest.rewards.get("xp");
            player.giveExp(xp);
            player.sendMessage(ChatColor.AQUA + "  +" + xp + " XP");
        }
        
        for (Map.Entry<String, Integer> entry : quest.rewards.entrySet()) {
            if (entry.getKey().equals("xp")) continue;
            
            try {
                Material mat = Material.valueOf(entry.getKey().toUpperCase());
                ItemStack reward = new ItemStack(mat, entry.getValue());
                player.getInventory().addItem(reward);
                player.sendMessage(ChatColor.GREEN + "  +" + entry.getValue() + "x " + 
                        mat.toString().replace("_", " ").toLowerCase());
            } catch (Exception e) {
                plugin.getLogger().warning("Invalid reward material: " + entry.getKey());
            }
        }
        
        player.sendMessage(ChatColor.GOLD + "════════════════════════════════");
        
        // Remove from active quests
        List<Quest> playerQuests = activeQuests.get(player.getUniqueId());
        if (playerQuests != null) {
            playerQuests.remove(quest);
        }
        
        deleteQuestFromDatabase(player, quest);
    }

    // Database operations
    private void saveQuestToDatabase(Player player, Quest quest) {
        // Implementation depends on your database setup
        // Store quest data in JSON format in database
    }

    private void saveQuestProgress(Player player) {
        // Update quest progress in database
    }

    private void deleteQuestFromDatabase(Player player, Quest quest) {
        // Remove completed quest from database
    }

    private void loadQuestsFromDatabase() {
        // Load active quests when plugin starts
    }

    public void clearQuests(Player player) {
        List<Quest> quests = activeQuests.remove(player.getUniqueId());
        if (quests != null && !quests.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "Jarvis: Cleared " + quests.size() + " active quests.");
        } else {
            player.sendMessage(ChatColor.GRAY + "Jarvis: You don't have any active quests.");
        }
    }
}
