package com.yourname.jarvis.quests;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.*;
import java.util.stream.Collectors;

public class QuestLibrary {

    private final Map<String, QuestTemplate> templates = new HashMap<>();
    private final Random random = new Random();
    
    public QuestLibrary() {
        initializeTemplates();
    }
    
    private void initializeTemplates() {
        // ==================== MINING QUESTS ====================
        
        addTemplate(new QuestTemplate.Builder("mining_basics", "Mining Basics")
            .description("Get started with mining by collecting basic resources")
            .category(QuestTemplate.QuestCategory.MINING)
            .levelRange(1, 10)
            .mineObjective(Material.COAL_ORE, 20)
            .mineObjective(Material.IRON_ORE, 10)
            .rewardXP(75)
            .rewardItem(Material.TORCH, 16)
            .rewardItem(Material.BREAD, 8)
            .build());
        
        addTemplate(new QuestTemplate.Builder("iron_rush", "Iron Rush")
            .description("The kingdom needs iron for armor and tools")
            .category(QuestTemplate.QuestCategory.MINING)
            .levelRange(5, 20)
            .mineObjective(Material.IRON_ORE, 32)
            .rewardXP(150)
            .rewardItem(Material.IRON_INGOT, 8)
            .rewardItem(Material.DIAMOND, 1)
            .build());
        
        addTemplate(new QuestTemplate.Builder("diamond_hunter", "Diamond Hunter")
            .description("Seek the rare and precious diamonds deep underground")
            .category(QuestTemplate.QuestCategory.MINING)
            .levelRange(10, 30)
            .mineObjective(Material.DIAMOND_ORE, 8)
            .mineObjective(Material.DEEPSLATE_DIAMOND_ORE, 4)
            .rewardXP(300)
            .rewardItem(Material.DIAMOND, 4)
            .rewardItem(Material.ENCHANTED_BOOK, 1)
            .build());
        
        addTemplate(new QuestTemplate.Builder("redstone_engineer", "Redstone Engineer")
            .description("Collect redstone for complex contraptions")
            .category(QuestTemplate.QuestCategory.MINING)
            .levelRange(8, 25)
            .mineObjective(Material.REDSTONE_ORE, 30)
            .mineObjective(Material.LAPIS_ORE, 15)
            .rewardXP(200)
            .rewardItem(Material.REDSTONE, 64)
            .rewardItem(Material.REPEATER, 8)
            .build());
        
        addTemplate(new QuestTemplate.Builder("ancient_treasures", "Ancient Treasures")
            .description("Brave the Nether to find ancient debris")
            .category(QuestTemplate.QuestCategory.MINING)
            .levelRange(20, 50)
            .mineObjective(Material.ANCIENT_DEBRIS, 6)
            .mineObjective(Material.NETHER_GOLD_ORE, 16)
            .rewardXP(500)
            .rewardItem(Material.NETHERITE_SCRAP, 2)
            .rewardItem(Material.DIAMOND, 8)
            .build());
        
        addTemplate(new QuestTemplate.Builder("deep_dive", "Deep Dive")
            .description("Explore the depths and mine deepslate ores")
            .category(QuestTemplate.QuestCategory.MINING)
            .levelRange(12, 35)
            .mineObjective(Material.DEEPSLATE_COAL_ORE, 15)
            .mineObjective(Material.DEEPSLATE_IRON_ORE, 20)
            .mineObjective(Material.DEEPSLATE_GOLD_ORE, 10)
            .rewardXP(250)
            .rewardItem(Material.GOLDEN_APPLE, 2)
            .build());
        
        // ==================== COMBAT QUESTS ====================
        
        addTemplate(new QuestTemplate.Builder("zombie_slayer", "Zombie Slayer")
            .description("Clear out the undead menace threatening the village")
            .category(QuestTemplate.QuestCategory.COMBAT)
            .levelRange(1, 15)
            .killObjective(EntityType.ZOMBIE, 25)
            .killObjective(EntityType.SKELETON, 15)
            .rewardXP(100)
            .rewardItem(Material.IRON_SWORD, 1)
            .rewardItem(Material.COOKED_BEEF, 16)
            .build());
        
        addTemplate(new QuestTemplate.Builder("spider_exterminator", "Spider Exterminator")
            .description("Rid the caves of dangerous spiders")
            .category(QuestTemplate.QuestCategory.COMBAT)
            .levelRange(5, 20)
            .killObjective(EntityType.SPIDER, 20)
            .killObjective(EntityType.CAVE_SPIDER, 10)
            .rewardXP(150)
            .rewardItem(Material.STRING, 32)
            .rewardItem(Material.BOW, 1)
            .build());
        
        addTemplate(new QuestTemplate.Builder("creeper_disposal", "Creeper Disposal")
            .description("Safely eliminate creepers before they cause damage")
            .category(QuestTemplate.QuestCategory.COMBAT)
            .levelRange(8, 25)
            .killObjective(EntityType.CREEPER, 15)
            .rewardXP(175)
            .rewardItem(Material.GUNPOWDER, 24)
            .rewardItem(Material.TNT, 4)
            .build());
        
        addTemplate(new QuestTemplate.Builder("nether_warrior", "Nether Warrior")
            .description("Battle the hostile forces of the Nether")
            .category(QuestTemplate.QuestCategory.COMBAT)
            .levelRange(15, 40)
            .killObjective(EntityType.ZOMBIFIED_PIGLIN, 20)
            .killObjective(EntityType.BLAZE, 10)
            .killObjective(EntityType.WITHER_SKELETON, 5)
            .rewardXP(400)
            .rewardItem(Material.BLAZE_ROD, 8)
            .rewardItem(Material.GOLDEN_APPLE, 3)
            .build());
        
        addTemplate(new QuestTemplate.Builder("enderman_enigma", "Enderman Enigma")
            .description("Study the mysterious endermen")
            .category(QuestTemplate.QuestCategory.COMBAT)
            .levelRange(12, 35)
            .killObjective(EntityType.ENDERMAN, 12)
            .rewardXP(300)
            .rewardItem(Material.ENDER_PEARL, 16)
            .rewardItem(Material.ENCHANTED_BOOK, 1)
            .build());
        
        // ==================== COLLECTION QUESTS ====================
        
        addTemplate(new QuestTemplate.Builder("woodcutter", "Woodcutter")
            .description("Gather wood for construction projects")
            .category(QuestTemplate.QuestCategory.COLLECTION)
            .levelRange(1, 10)
            .collectObjective(Material.OAK_LOG, 64)
            .collectObjective(Material.SPRUCE_LOG, 32)
            .rewardXP(50)
            .rewardItem(Material.IRON_AXE, 1)
            .build());
        
        addTemplate(new QuestTemplate.Builder("farmer", "Farming Fortune")
            .description("Harvest crops to feed the village")
            .category(QuestTemplate.QuestCategory.COLLECTION)
            .levelRange(1, 15)
            .collectObjective(Material.WHEAT, 64)
            .collectObjective(Material.CARROT, 32)
            .collectObjective(Material.POTATO, 32)
            .rewardXP(100)
            .rewardItem(Material.GOLDEN_CARROT, 8)
            .rewardItem(Material.EMERALD, 2)
            .build());
        
        addTemplate(new QuestTemplate.Builder("treasure_hunter", "Treasure Hunter")
            .description("Collect rare and valuable materials")
            .category(QuestTemplate.QuestCategory.COLLECTION)
            .levelRange(10, 30)
            .collectObjective(Material.DIAMOND, 5)
            .collectObjective(Material.EMERALD, 3)
            .collectObjective(Material.NETHERITE_SCRAP, 2)
            .rewardXP(350)
            .rewardItem(Material.DIAMOND_BLOCK, 1)
            .rewardItem(Material.EMERALD, 8)
            .build());
        
        addTemplate(new QuestTemplate.Builder("botanist", "Botanical Collection")
            .description("Gather diverse plant specimens")
            .category(QuestTemplate.QuestCategory.COLLECTION)
            .levelRange(3, 20)
            .collectObjective(Material.POPPY, 10)
            .collectObjective(Material.DANDELION, 10)
            .collectObjective(Material.LILY_PAD, 16)
            .collectObjective(Material.VINE, 24)
            .rewardXP(125)
            .rewardItem(Material.BONE_MEAL, 32)
            .rewardItem(Material.FLOWER_POT, 8)
            .build());
        
        // ==================== BUILDING QUESTS ====================
        
        addTemplate(new QuestTemplate.Builder("master_builder", "Master Builder")
            .description("Construct structures to prove your building prowess")
            .category(QuestTemplate.QuestCategory.BUILDING)
            .levelRange(5, 25)
            .addObjective("build", "STRUCTURE", 1, "Build any structure")
            .collectObjective(Material.STONE_BRICKS, 128)
            .collectObjective(Material.OAK_PLANKS, 256)
            .rewardXP(200)
            .rewardItem(Material.DIAMOND_PICKAXE, 1)
            .rewardItem(Material.EMERALD, 5)
            .build());
        
        addTemplate(new QuestTemplate.Builder("architect", "Architect's Vision")
            .description("Place a large number of blocks for a grand project")
            .category(QuestTemplate.QuestCategory.BUILDING)
            .levelRange(10, 40)
            .addObjective("build", "BLOCKS", 500, "Place 500 blocks")
            .rewardXP(300)
            .rewardItem(Material.EMERALD_BLOCK, 2)
            .rewardItem(Material.ENCHANTED_BOOK, 1)
            .build());
        
        // ==================== EXPLORATION QUESTS ====================
        
        addTemplate(new QuestTemplate.Builder("cave_explorer", "Cave Explorer")
            .description("Delve deep into unexplored caverns")
            .category(QuestTemplate.QuestCategory.EXPLORATION)
            .levelRange(5, 20)
            .mineObjective(Material.STONE, 200)
            .collectObjective(Material.IRON_ORE, 16)
            .rewardXP(150)
            .rewardItem(Material.TORCH, 64)
            .rewardItem(Material.COMPASS, 1)
            .build());
        
        addTemplate(new QuestTemplate.Builder("nether_expedition", "Nether Expedition")
            .description("Explore the dangerous Nether dimension")
            .category(QuestTemplate.QuestCategory.EXPLORATION)
            .levelRange(15, 40)
            .collectObjective(Material.NETHERRACK, 64)
            .collectObjective(Material.SOUL_SAND, 32)
            .mineObjective(Material.NETHER_QUARTZ_ORE, 20)
            .rewardXP(350)
            .rewardItem(Material.POTION, 3)
            .rewardItem(Material.GOLDEN_APPLE, 2)
            .build());
        
        // ==================== MIXED QUESTS ====================
        
        addTemplate(new QuestTemplate.Builder("survival_expert", "Survival Expert")
            .description("Prove your survival skills across all disciplines")
            .category(QuestTemplate.QuestCategory.MIXED)
            .levelRange(15, 50)
            .mineObjective(Material.IRON_ORE, 32)
            .killObjective(EntityType.ZOMBIE, 20)
            .collectObjective(Material.WHEAT, 64)
            .rewardXP(500)
            .rewardItem(Material.DIAMOND_CHESTPLATE, 1)
            .rewardItem(Material.EMERALD, 10)
            .build());
        
        addTemplate(new QuestTemplate.Builder("daily_grind", "Daily Grind")
            .description("Complete various everyday tasks")
            .category(QuestTemplate.QuestCategory.MIXED)
            .levelRange(1, 30)
            .mineObjective(Material.COAL_ORE, 16)
            .killObjective(EntityType.SPIDER, 10)
            .collectObjective(Material.OAK_LOG, 32)
            .rewardXP(150)
            .rewardItem(Material.DIAMOND, 2)
            .rewardItem(Material.GOLDEN_APPLE, 1)
            .build());
    }
    
    private void addTemplate(QuestTemplate template) {
        templates.put(template.getId(), template);
    }
    
    /**
     * Get template by ID
     */
    public QuestTemplate getTemplate(String id) {
        return templates.get(id);
    }
    
    /**
     * Get all templates
     */
    public Collection<QuestTemplate> getAllTemplates() {
        return templates.values();
    }
    
    /**
     * Get templates by category
     */
    public List<QuestTemplate> getTemplatesByCategory(QuestTemplate.QuestCategory category) {
        return templates.values().stream()
            .filter(t -> t.getCategory() == category)
            .collect(Collectors.toList());
    }
    
    /**
     * Get templates appropriate for player level
     */
    public List<QuestTemplate> getTemplatesForLevel(int playerLevel) {
        return templates.values().stream()
            .filter(t -> t.isLevelAppropriate(playerLevel))
            .collect(Collectors.toList());
    }
    
    /**
     * Get random template appropriate for player level
     */
    public QuestTemplate getRandomTemplateForLevel(int playerLevel) {
        List<QuestTemplate> appropriate = getTemplatesForLevel(playerLevel);
        if (appropriate.isEmpty()) {
            return null;
        }
        return appropriate.get(random.nextInt(appropriate.size()));
    }
    
    /**
     * Get random template from category appropriate for player level
     */
    public QuestTemplate getRandomTemplateForLevelAndCategory(int playerLevel, QuestTemplate.QuestCategory category) {
        List<QuestTemplate> appropriate = templates.values().stream()
            .filter(t -> t.isLevelAppropriate(playerLevel))
            .filter(t -> t.getCategory() == category)
            .collect(Collectors.toList());
        
        if (appropriate.isEmpty()) {
            return getRandomTemplateForLevel(playerLevel);
        }
        
        return appropriate.get(random.nextInt(appropriate.size()));
    }
    
    /**
     * Get total template count
     */
    public int getTemplateCount() {
        return templates.size();
    }
    
    /**
     * Get template IDs
     */
    public Set<String> getTemplateIds() {
        return templates.keySet();
    }
}
