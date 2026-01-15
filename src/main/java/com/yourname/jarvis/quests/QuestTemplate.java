package com.yourname.jarvis.quests;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QuestTemplate {

    private final String id;
    private final String title;
    private final String description;
    private final QuestCategory category;
    private final int minLevel;
    private final int maxLevel;
    private final List<ObjectiveTemplate> objectives;
    private final Map<String, Integer> rewards;
    
    public enum QuestCategory {
        MINING("Mining", "⛏"),
        COMBAT("Combat", "⚔"),
        BUILDING("Building", "🏗"),
        EXPLORATION("Exploration", "🗺"),
        COLLECTION("Collection", "📦"),
        MIXED("Mixed", "✨");
        
        public final String displayName;
        public final String icon;
        
        QuestCategory(String displayName, String icon) {
            this.displayName = displayName;
            this.icon = icon;
        }
    }
    
    public static class ObjectiveTemplate {
        public final String type; // mine, kill, collect, build, explore
        public final String target;
        public final int amount;
        public final String displayName;
        
        public ObjectiveTemplate(String type, String target, int amount, String displayName) {
            this.type = type;
            this.target = target;
            this.amount = amount;
            this.displayName = displayName != null ? displayName : target;
        }
        
        public ObjectiveTemplate(String type, String target, int amount) {
            this(type, target, amount, null);
        }
    }
    
    private QuestTemplate(Builder builder) {
        this.id = builder.id;
        this.title = builder.title;
        this.description = builder.description;
        this.category = builder.category;
        this.minLevel = builder.minLevel;
        this.maxLevel = builder.maxLevel;
        this.objectives = builder.objectives;
        this.rewards = builder.rewards;
    }
    
    // Getters
    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public QuestCategory getCategory() { return category; }
    public int getMinLevel() { return minLevel; }
    public int getMaxLevel() { return maxLevel; }
    public List<ObjectiveTemplate> getObjectives() { return objectives; }
    public Map<String, Integer> getRewards() { return rewards; }
    
    /**
     * Check if player level qualifies for this quest
     */
    public boolean isLevelAppropriate(int playerLevel) {
        return playerLevel >= minLevel && playerLevel <= maxLevel;
    }
    
    /**
     * Scale rewards based on player level
     */
    public Map<String, Integer> getScaledRewards(int playerLevel) {
        Map<String, Integer> scaled = new HashMap<>();
        double multiplier = 1.0 + (playerLevel - minLevel) * 0.1; // 10% per level above min
        
        for (Map.Entry<String, Integer> entry : rewards.entrySet()) {
            scaled.put(entry.getKey(), (int)(entry.getValue() * multiplier));
        }
        
        return scaled;
    }
    
    // Builder pattern for easy template creation
    public static class Builder {
        private String id;
        private String title;
        private String description;
        private QuestCategory category = QuestCategory.MIXED;
        private int minLevel = 1;
        private int maxLevel = 100;
        private List<ObjectiveTemplate> objectives = new ArrayList<>();
        private Map<String, Integer> rewards = new HashMap<>();
        
        public Builder(String id, String title) {
            this.id = id;
            this.title = title;
        }
        
        public Builder description(String desc) {
            this.description = desc;
            return this;
        }
        
        public Builder category(QuestCategory cat) {
            this.category = cat;
            return this;
        }
        
        public Builder levelRange(int min, int max) {
            this.minLevel = min;
            this.maxLevel = max;
            return this;
        }
        
        public Builder addObjective(String type, String target, int amount) {
            objectives.add(new ObjectiveTemplate(type, target, amount));
            return this;
        }
        
        public Builder addObjective(String type, String target, int amount, String displayName) {
            objectives.add(new ObjectiveTemplate(type, target, amount, displayName));
            return this;
        }
        
        public Builder mineObjective(Material material, int amount) {
            objectives.add(new ObjectiveTemplate("mine", material.toString(), amount, 
                material.toString().replace("_", " ").toLowerCase()));
            return this;
        }
        
        public Builder killObjective(EntityType entity, int amount) {
            objectives.add(new ObjectiveTemplate("kill", entity.toString(), amount,
                entity.toString().replace("_", " ").toLowerCase()));
            return this;
        }
        
        public Builder collectObjective(Material material, int amount) {
            objectives.add(new ObjectiveTemplate("collect", material.toString(), amount,
                material.toString().replace("_", " ").toLowerCase()));
            return this;
        }
        
        public Builder rewardXP(int xp) {
            rewards.put("xp", xp);
            return this;
        }
        
        public Builder rewardItem(Material material, int amount) {
            rewards.put(material.toString(), amount);
            return this;
        }
        
        public QuestTemplate build() {
            // Validation
            if (id == null || title == null) {
                throw new IllegalStateException("Quest must have ID and title");
            }
            if (objectives.isEmpty()) {
                throw new IllegalStateException("Quest must have at least one objective");
            }
            if (rewards.isEmpty()) {
                rewards.put("xp", 50); // Default XP reward
            }
            
            return new QuestTemplate(this);
        }
    }
    
    @Override
    public String toString() {
        return String.format("QuestTemplate{id='%s', title='%s', category=%s, objectives=%d}",
            id, title, category, objectives.size());
    }
}
