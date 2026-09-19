package com.gadgetman.jarvis.core.world;

/**
 * Namespaced ids for the blocks, items, enchantments, sounds and particles
 * core refers to. Plain strings on purpose: core has no registry, and every
 * platform resolves these ids to its own constants.
 *
 * <p>The block and item list is the set the plugin used as {@code Material}
 * constants when the core/adapter split began. Add to it as needed; the
 * adapters resolve ids by name, so nothing here has to be exhaustive.
 */
public final class Ids {

    private Ids() { }

    public static final String NAMESPACE = "minecraft:";

    /** The bare key of an id: {@code minecraft:oak_log} becomes {@code oak_log}. */
    public static String key(String id) {
        if (id == null) return null;
        int i = id.indexOf(':');
        return i < 0 ? id : id.substring(i + 1);
    }

    /** The id for a bare key, leaving an id that already has a namespace alone. */
    public static String of(String key) {
        if (key == null) return null;
        return key.indexOf(':') >= 0 ? key : NAMESPACE + key.toLowerCase();
    }

    /** {@code minecraft:oak_log} reads as {@code Oak log}. */
    public static String pretty(String id) {
        String k = key(id).replace('_', ' ');
        return k.isEmpty() ? k : Character.toUpperCase(k.charAt(0)) + k.substring(1);
    }

    // ---- blocks and items ----

    public static final String ACACIA_LOG = "minecraft:acacia_log";
    public static final String ACACIA_SAPLING = "minecraft:acacia_sapling";
    public static final String AIR = "minecraft:air";
    public static final String CAVE_AIR = "minecraft:cave_air";
    public static final String VOID_AIR = "minecraft:void_air";
    public static final String AMETHYST_SHARD = "minecraft:amethyst_shard";
    public static final String ANCIENT_DEBRIS = "minecraft:ancient_debris";
    public static final String ANDESITE = "minecraft:andesite";
    public static final String ARMOR_STAND = "minecraft:armor_stand";
    public static final String ARROW = "minecraft:arrow";
    public static final String BARREL = "minecraft:barrel";
    public static final String BARRIER = "minecraft:barrier";
    public static final String BASALT = "minecraft:basalt";
    public static final String BEETROOTS = "minecraft:beetroots";
    public static final String BEETROOT_SEEDS = "minecraft:beetroot_seeds";
    public static final String BELL = "minecraft:bell";
    public static final String BIRCH_LOG = "minecraft:birch_log";
    public static final String BIRCH_SAPLING = "minecraft:birch_sapling";
    public static final String BLACKSTONE = "minecraft:blackstone";
    public static final String BOOK = "minecraft:book";
    public static final String BOOKSHELF = "minecraft:bookshelf";
    public static final String BOW = "minecraft:bow";
    public static final String BOWL = "minecraft:bowl";
    public static final String BRICK = "minecraft:brick";
    public static final String BRICKS = "minecraft:bricks";
    public static final String CACTUS = "minecraft:cactus";
    public static final String CALCITE = "minecraft:calcite";
    public static final String CAMPFIRE = "minecraft:campfire";
    public static final String CARROT = "minecraft:carrot";
    public static final String CARROTS = "minecraft:carrots";
    public static final String CHERRY_LOG = "minecraft:cherry_log";
    public static final String CHERRY_SAPLING = "minecraft:cherry_sapling";
    public static final String CHEST = "minecraft:chest";
    public static final String CHEST_MINECART = "minecraft:chest_minecart";
    public static final String CLAY = "minecraft:clay";
    public static final String CLOCK = "minecraft:clock";
    public static final String COAL = "minecraft:coal";
    public static final String COAL_ORE = "minecraft:coal_ore";
    public static final String COARSE_DIRT = "minecraft:coarse_dirt";
    public static final String COBBLED_DEEPSLATE = "minecraft:cobbled_deepslate";
    public static final String COBBLESTONE = "minecraft:cobblestone";
    public static final String COD = "minecraft:cod";
    public static final String COMMAND_BLOCK = "minecraft:command_block";
    public static final String COMPASS = "minecraft:compass";
    public static final String COPPER_ORE = "minecraft:copper_ore";
    public static final String DARK_OAK_LOG = "minecraft:dark_oak_log";
    public static final String DARK_OAK_SAPLING = "minecraft:dark_oak_sapling";
    public static final String DEEPSLATE = "minecraft:deepslate";
    public static final String DEEPSLATE_COAL_ORE = "minecraft:deepslate_coal_ore";
    public static final String DEEPSLATE_COPPER_ORE = "minecraft:deepslate_copper_ore";
    public static final String DEEPSLATE_DIAMOND_ORE = "minecraft:deepslate_diamond_ore";
    public static final String DEEPSLATE_EMERALD_ORE = "minecraft:deepslate_emerald_ore";
    public static final String DEEPSLATE_GOLD_ORE = "minecraft:deepslate_gold_ore";
    public static final String DEEPSLATE_IRON_ORE = "minecraft:deepslate_iron_ore";
    public static final String DEEPSLATE_LAPIS_ORE = "minecraft:deepslate_lapis_ore";
    public static final String DEEPSLATE_REDSTONE_ORE = "minecraft:deepslate_redstone_ore";
    public static final String DIAMOND_AXE = "minecraft:diamond_axe";
    public static final String DIAMOND_HOE = "minecraft:diamond_hoe";
    public static final String DIAMOND_ORE = "minecraft:diamond_ore";
    public static final String DIAMOND_PICKAXE = "minecraft:diamond_pickaxe";
    public static final String DIAMOND_SWORD = "minecraft:diamond_sword";
    public static final String DIORITE = "minecraft:diorite";
    public static final String DIRT = "minecraft:dirt";
    public static final String DRIPSTONE_BLOCK = "minecraft:dripstone_block";
    public static final String EMERALD_ORE = "minecraft:emerald_ore";
    public static final String ENDER_CHEST = "minecraft:ender_chest";
    public static final String ENDER_EYE = "minecraft:ender_eye";
    public static final String ENDER_PEARL = "minecraft:ender_pearl";
    public static final String END_ROD = "minecraft:end_rod";
    public static final String EXPERIENCE_BOTTLE = "minecraft:experience_bottle";
    public static final String FARMLAND = "minecraft:farmland";
    public static final String FIRE = "minecraft:fire";
    public static final String FISHING_ROD = "minecraft:fishing_rod";
    public static final String FLINT = "minecraft:flint";
    public static final String GOLDEN_HELMET = "minecraft:golden_helmet";
    public static final String GOLD_ORE = "minecraft:gold_ore";
    public static final String GRANITE = "minecraft:granite";
    public static final String GRASS_BLOCK = "minecraft:grass_block";
    public static final String GRAVEL = "minecraft:gravel";
    public static final String GRAY_DYE = "minecraft:gray_dye";
    public static final String GRAY_STAINED_GLASS_PANE = "minecraft:gray_stained_glass_pane";
    public static final String GREEN_WOOL = "minecraft:green_wool";
    public static final String HOPPER = "minecraft:hopper";
    public static final String INK_SAC = "minecraft:ink_sac";
    public static final String IRON_AXE = "minecraft:iron_axe";
    public static final String IRON_HOE = "minecraft:iron_hoe";
    public static final String IRON_ORE = "minecraft:iron_ore";
    public static final String IRON_PICKAXE = "minecraft:iron_pickaxe";
    public static final String IRON_SWORD = "minecraft:iron_sword";
    public static final String JUKEBOX = "minecraft:jukebox";
    public static final String JUNGLE_LOG = "minecraft:jungle_log";
    public static final String JUNGLE_SAPLING = "minecraft:jungle_sapling";
    public static final String LADDER = "minecraft:ladder";
    public static final String LANTERN = "minecraft:lantern";
    public static final String LAPIS_ORE = "minecraft:lapis_ore";
    public static final String LAVA = "minecraft:lava";
    public static final String LAVA_BUCKET = "minecraft:lava_bucket";
    public static final String LEAD = "minecraft:lead";
    public static final String LEATHER_BOOTS = "minecraft:leather_boots";
    public static final String LIME_DYE = "minecraft:lime_dye";
    public static final String MAGMA_BLOCK = "minecraft:magma_block";
    public static final String MANGROVE_LOG = "minecraft:mangrove_log";
    public static final String MANGROVE_PROPAGULE = "minecraft:mangrove_propagule";
    public static final String MAP = "minecraft:map";
    public static final String MELON = "minecraft:melon";
    public static final String MOSSY_COBBLESTONE = "minecraft:mossy_cobblestone";
    public static final String MUD = "minecraft:mud";
    public static final String NAME_TAG = "minecraft:name_tag";
    public static final String NAUTILUS_SHELL = "minecraft:nautilus_shell";
    public static final String NETHERITE_AXE = "minecraft:netherite_axe";
    public static final String NETHERITE_HOE = "minecraft:netherite_hoe";
    public static final String NETHERITE_INGOT = "minecraft:netherite_ingot";
    public static final String NETHERITE_PICKAXE = "minecraft:netherite_pickaxe";
    public static final String NETHERITE_SWORD = "minecraft:netherite_sword";
    public static final String NETHERRACK = "minecraft:netherrack";
    public static final String NETHER_GOLD_ORE = "minecraft:nether_gold_ore";
    public static final String NETHER_PORTAL = "minecraft:nether_portal";
    public static final String NETHER_QUARTZ_ORE = "minecraft:nether_quartz_ore";
    public static final String NETHER_WART = "minecraft:nether_wart";
    public static final String NOTE_BLOCK = "minecraft:note_block";
    public static final String OAK_DOOR = "minecraft:oak_door";
    public static final String OAK_LOG = "minecraft:oak_log";
    public static final String OAK_PLANKS = "minecraft:oak_planks";
    public static final String OAK_SAPLING = "minecraft:oak_sapling";
    public static final String OAK_SIGN = "minecraft:oak_sign";
    public static final String PACKED_MUD = "minecraft:packed_mud";
    public static final String PAPER = "minecraft:paper";
    public static final String PODZOL = "minecraft:podzol";
    public static final String POTATO = "minecraft:potato";
    public static final String POTATOES = "minecraft:potatoes";
    public static final String PUFFERFISH = "minecraft:pufferfish";
    public static final String PUMPKIN = "minecraft:pumpkin";
    public static final String RAIL = "minecraft:rail";
    public static final String REDSTONE = "minecraft:redstone";
    public static final String REDSTONE_BLOCK = "minecraft:redstone_block";
    public static final String REDSTONE_ORE = "minecraft:redstone_ore";
    public static final String RED_BED = "minecraft:red_bed";
    public static final String RED_SAND = "minecraft:red_sand";
    public static final String RED_SANDSTONE = "minecraft:red_sandstone";
    public static final String RED_WOOL = "minecraft:red_wool";
    public static final String REPEATER = "minecraft:repeater";
    public static final String ROOTED_DIRT = "minecraft:rooted_dirt";
    public static final String ROTTEN_FLESH = "minecraft:rotten_flesh";
    public static final String SADDLE = "minecraft:saddle";
    public static final String SALMON = "minecraft:salmon";
    public static final String SAND = "minecraft:sand";
    public static final String SANDSTONE = "minecraft:sandstone";
    public static final String SEA_LANTERN = "minecraft:sea_lantern";
    public static final String SHIELD = "minecraft:shield";
    public static final String SMOOTH_BASALT = "minecraft:smooth_basalt";
    public static final String SNOW_BLOCK = "minecraft:snow_block";
    public static final String SOUL_LANTERN = "minecraft:soul_lantern";
    public static final String SOUL_SAND = "minecraft:soul_sand";
    public static final String SOUL_SOIL = "minecraft:soul_soil";
    public static final String SPRUCE_LOG = "minecraft:spruce_log";
    public static final String SPRUCE_SAPLING = "minecraft:spruce_sapling";
    public static final String SPYGLASS = "minecraft:spyglass";
    public static final String STICK = "minecraft:stick";
    public static final String STONE = "minecraft:stone";
    public static final String STONE_BRICKS = "minecraft:stone_bricks";
    public static final String STRING = "minecraft:string";
    public static final String TORCH = "minecraft:torch";
    public static final String TOTEM_OF_UNDYING = "minecraft:totem_of_undying";
    public static final String TRAPPED_CHEST = "minecraft:trapped_chest";
    public static final String TRIDENT = "minecraft:trident";
    public static final String TROPICAL_FISH = "minecraft:tropical_fish";
    public static final String TUFF = "minecraft:tuff";
    public static final String WALL_TORCH = "minecraft:wall_torch";
    public static final String WATER = "minecraft:water";
    public static final String WHEAT = "minecraft:wheat";
    public static final String WHEAT_SEEDS = "minecraft:wheat_seeds";
    public static final String WRITABLE_BOOK = "minecraft:writable_book";
    public static final String WRITTEN_BOOK = "minecraft:written_book";

    // ---- enchantments ----

    public static final String ENCHANT_CHANNELING = "minecraft:channeling";
    public static final String ENCHANT_EFFICIENCY = "minecraft:efficiency";
    public static final String ENCHANT_FIRE_ASPECT = "minecraft:fire_aspect";
    public static final String ENCHANT_FLAME = "minecraft:flame";
    public static final String ENCHANT_FORTUNE = "minecraft:fortune";
    public static final String ENCHANT_IMPALING = "minecraft:impaling";
    public static final String ENCHANT_LOOTING = "minecraft:looting";
    public static final String ENCHANT_LOYALTY = "minecraft:loyalty";
    public static final String ENCHANT_POWER = "minecraft:power";
    public static final String ENCHANT_PUNCH = "minecraft:punch";
    public static final String ENCHANT_SHARPNESS = "minecraft:sharpness";
    public static final String ENCHANT_INFINITY = "minecraft:infinity";

    // ---- sounds ----

    public static final String SOUND_BLOCK_BELL_USE = "minecraft:block.bell.use";
    public static final String SOUND_BLOCK_CHEST_CLOSE = "minecraft:block.chest.close";
    public static final String SOUND_BLOCK_CHEST_OPEN = "minecraft:block.chest.open";
    public static final String SOUND_BLOCK_CROP_BREAK = "minecraft:block.crop.break";
    public static final String SOUND_BLOCK_GLASS_PLACE = "minecraft:block.glass.place";
    public static final String SOUND_BLOCK_NOTE_BLOCK_BIT = "minecraft:block.note_block.bit";
    public static final String SOUND_BLOCK_NOTE_BLOCK_PLING = "minecraft:block.note_block.pling";
    public static final String SOUND_BLOCK_WOOD_BREAK = "minecraft:block.wood.break";
    public static final String SOUND_BLOCK_WOOD_PLACE = "minecraft:block.wood.place";
    public static final String SOUND_ENTITY_ARROW_SHOOT = "minecraft:entity.arrow.shoot";
    public static final String SOUND_ENTITY_FISHING_BOBBER_SPLASH = "minecraft:entity.fishing_bobber.splash";
    public static final String SOUND_ENTITY_FISHING_BOBBER_THROW = "minecraft:entity.fishing_bobber.throw";
    public static final String SOUND_ENTITY_ITEM_PICKUP = "minecraft:entity.item.pickup";
    public static final String SOUND_ENTITY_PLAYER_ATTACK_SWEEP = "minecraft:entity.player.attack.sweep";
    public static final String SOUND_ENTITY_PLAYER_LEVELUP = "minecraft:entity.player.levelup";
    public static final String SOUND_ITEM_CROP_PLANT = "minecraft:item.crop.plant";
    public static final String SOUND_ITEM_TRIDENT_THROW = "minecraft:item.trident.throw";
    public static final String SOUND_UI_BUTTON_CLICK = "minecraft:ui.button.click";
    public static final String SOUND_UI_TOAST_CHALLENGE_COMPLETE = "minecraft:ui.toast.challenge_complete";

    // ---- particles ----

    public static final String PARTICLE_NOTE = "minecraft:note";
    public static final String PARTICLE_SPLASH = "minecraft:splash";
}
