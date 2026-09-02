package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.StuckAction;
import net.citizensnpcs.api.ai.tree.BehaviorStatus;
import net.citizensnpcs.api.astar.pathfinder.SwimmingExaminer;
import net.citizensnpcs.api.npc.BlockBreaker;
import com.gadgetman.jarvis.npc.provider.CitizensNPCProvider;
import com.gadgetman.jarvis.npc.provider.INPCProvider;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * JarvisNPC - Manages NPC spawning, combat, and mining.
 * Version: 0.8.0
 *
 * v0.8.0: Field-test fixes
 * - FIXED: sky-stare after summon (idle glances now use a level gaze)
 * - FIXED: follow mode wedging (stall watchdog with a catch-up hop)
 * - FIXED: ore tunnels now dug 2-high on stairs so the player can follow
 * - FIXED: partial item pickup destroying the remainder of a stack
 * - FIXED: kit-tool filter no longer traps tools the player owns (slots 1+)
 * - FIXED: block-breaker task now stops with /jarvis stop
 *
 * v0.1.1: Field-test fixes
 * - FIXED: pickaxe in hand (inventory slot 0 IS the held item for player
 *   NPCs — the old dirt stack was overwriting the pickaxe, making every dig
 *   glacial and dropping nothing)
 * - NEW: tunnel executor — buried ores are unreachable by pathfinding alone,
 *   so Jarvis now digs a proper 1x2 tunnel toward them, cell by cell, with
 *   staircase descents/ascents and lava/water safety checks
 * - FIXED: block break speed modifier direction (higher = faster)
 *
 * v0.1.0: Butler mining rework — Citizens A* pathfinding (no teleporting),
 * real block breaking via Citizens BlockBreaker, async ore scanning.
 */
public class JarvisNPC implements Listener {

    private final Jarvis plugin;
    /**
     * The NPC registry. Owned by the provider, not by this class -- see
     * CitizensNPCProvider#registry(). Held here as a field so the sixteen
     * sites that touch it directly are unchanged; it is the same map object,
     * so provider and host can never disagree about who is spawned.
     */
    private final Map<UUID, NPC> playerNPCs;

    /**
     * The NPC backend. Declared concrete because this is the one class that
     * still reaches past the interface -- it configures Citizens pathfinding
     * and holds Citizens NPC objects directly. Everything else goes through
     * {@link #getProvider()} and never names Citizens at all.
     */
    private final CitizensNPCProvider provider;
    private DepositManager depositManager;
    private RecoveryService recoveryService;
    private EscortService escortService;
    private final Map<UUID, Defender> activeDefenders = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> activeTasks = new ConcurrentHashMap<>();
    private final Map<UUID, MiningState> miningStates = new ConcurrentHashMap<>();

    // Configuration (loaded from config.yml, sensible defaults)
    private final int searchRadius;
    private final double navRange;
    private final boolean useAsyncPathfinder;
    private final boolean timedBreaking;
    private final double breakSpeedModifier;
    private boolean debugMode;

    private static final int PICKUP_RADIUS = 6;
    private static final double REACH_DISTANCE = 3.5;
    private static final int MINING_TICK_RATE = 10;      // Decision loop: every 0.5s
    private static final int MOVE_TIMEOUT_TICKS = 40;    // 20s of A* walking before we tunnel instead
    private static final int MAX_TUNNEL_STEPS = 64;      // Tunnel cells per target before giving up
    private static final int ADVANCE_NUDGE_TICKS = 5;    // 2.5s to walk one dug cell before nudging
    private static final double LAST_RESORT_TELEPORT_DISTANCE = 40.0;

    // Ore priority (highest value first)
    private static final List<Material> ORE_PRIORITY = Arrays.asList(
        Material.ANCIENT_DEBRIS,
        Material.DEEPSLATE_EMERALD_ORE, Material.EMERALD_ORE,
        Material.DEEPSLATE_DIAMOND_ORE, Material.DIAMOND_ORE,
        Material.DEEPSLATE_GOLD_ORE, Material.GOLD_ORE,
        Material.DEEPSLATE_LAPIS_ORE, Material.LAPIS_ORE,
        Material.DEEPSLATE_REDSTONE_ORE, Material.REDSTONE_ORE,
        Material.DEEPSLATE_IRON_ORE, Material.IRON_ORE,
        Material.DEEPSLATE_COPPER_ORE, Material.COPPER_ORE,
        Material.DEEPSLATE_COAL_ORE, Material.COAL_ORE,
        Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE
    );

    // Keyword → ore materials mapping for targeted mining
    private static final Map<String, Set<Material>> ORE_KEYWORDS = new LinkedHashMap<>();
    static {
        ORE_KEYWORDS.put("ancient debris", Set.of(Material.ANCIENT_DEBRIS));
        ORE_KEYWORDS.put("debris",         Set.of(Material.ANCIENT_DEBRIS));
        ORE_KEYWORDS.put("netherite",      Set.of(Material.ANCIENT_DEBRIS));
        ORE_KEYWORDS.put("emerald",        Set.of(Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE));
        ORE_KEYWORDS.put("diamond",        Set.of(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE));
        ORE_KEYWORDS.put("gold",           Set.of(Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE));
        ORE_KEYWORDS.put("lapis",          Set.of(Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE));
        ORE_KEYWORDS.put("redstone",       Set.of(Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE));
        ORE_KEYWORDS.put("iron",           Set.of(Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE));
        ORE_KEYWORDS.put("copper",         Set.of(Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE));
        ORE_KEYWORDS.put("quartz",         Set.of(Material.NETHER_QUARTZ_ORE));
        ORE_KEYWORDS.put("coal",           Set.of(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE));
    }

    // Items to ignore during pickup (blocks broken while navigating)
    private static final Set<Material> JUNK_DROPS = Set.of(
        Material.COBBLESTONE, Material.COBBLED_DEEPSLATE, Material.STONE,
        Material.DIRT, Material.GRAVEL, Material.SAND, Material.FLINT,
        Material.GRANITE, Material.DIORITE, Material.ANDESITE,
        Material.DEEPSLATE, Material.TUFF, Material.CALCITE,
        Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE
    );

    // Blocks Jarvis will tunnel through (never valuables, never containers)
    private static final Set<Material> DIGGABLE = Set.of(
        Material.STONE, Material.DEEPSLATE, Material.COBBLESTONE, Material.COBBLED_DEEPSLATE,
        Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT,
        Material.PODZOL, Material.CLAY, Material.MUD, Material.PACKED_MUD,
        Material.GRAVEL, Material.SAND, Material.SANDSTONE, Material.RED_SAND, Material.RED_SANDSTONE,
        Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.TUFF, Material.CALCITE,
        Material.MOSSY_COBBLESTONE, Material.DRIPSTONE_BLOCK, Material.SNOW_BLOCK,
        Material.NETHERRACK, Material.BASALT, Material.SMOOTH_BASALT, Material.BLACKSTONE,
        Material.SOUL_SAND, Material.SOUL_SOIL, Material.MAGMA_BLOCK
    );

    // ==================== MINING STATE ====================

    private enum MiningPhase {
        SEARCHING,    // Kicking off / waiting on the async ore scan
        MOVING,       // Walking to the ore (Citizens A* — works for exposed ores)
        TUNNELING,    // Digging a 1x2 tunnel toward a buried ore, cell by cell
        MINING,       // Breaking the ore with the BlockBreaker
        COLLECTING    // Picking up the drops
    }

    private static class MiningState {
        MiningPhase phase = MiningPhase.SEARCHING;
        int ticksInPhase = 0;

        Location targetOre = null;              // Block location of the current target
        Material targetOreType = null;
        Set<Material> requestedOreTypes = null; // null = any ore
        Location collectLocation = null;

        int oresMined = 0;
        boolean scanInFlight = false;
        boolean navStuck = false;               // Set by the stuck action
        boolean breaking = false;               // A BlockBreaker task is running

        // Tunnel executor state
        final ArrayDeque<Location> digQueue = new ArrayDeque<>(); // Blocks to dig for the current step
        Location stepCell = null;               // The cell we're walking into
        int tunnelSteps = 0;                    // Steps used for the current target
        int advanceTicks = 0;                   // Ticks spent walking into stepCell

        final Set<Long> unreachable = new HashSet<>(); // Ores we've given up on

        void transitionTo(MiningPhase newPhase) {
            phase = newPhase;
            ticksInPhase = 0;
        }

        void clearTarget() {
            targetOre = null;
            targetOreType = null;
            navStuck = false;
            digQueue.clear();
            stepCell = null;
            tunnelSteps = 0;
            advanceTicks = 0;
        }

        static long key(Location l) {
            return ((long) l.getBlockX() & 0x3FFFFFF) << 38
                 | ((long) l.getBlockZ() & 0x3FFFFFF) << 12
                 | ((long) (l.getBlockY() + 2048) & 0xFFF);
        }
    }

    /**
     * The anti-teleport stuck action. Citizens' default is TeleportStuckAction
     * (warp to the goal when pathing fails) — the source of the old hopping.
     * Ours just flags the state machine, which switches to tunneling.
     */
    static class ButlerStuckAction implements StuckAction {
        private final Runnable onStuck;
        ButlerStuckAction(Runnable onStuck) { this.onStuck = onStuck; }
        @Override
        public boolean run(NPC npc, Navigator navigator) {
            if (onStuck != null) onStuck.run();
            return false; // cancel navigation; the caller handles recovery
        }
    }

    private static final StuckAction NO_TELEPORT = (npc, navigator) -> false;

    // ==================== CONSTRUCTOR ====================

    public JarvisNPC(Jarvis plugin) {
        this.plugin = plugin;
        this.provider = new CitizensNPCProvider(plugin);
        this.playerNPCs = provider.registry();
        this.debugMode = plugin.getConfig().getBoolean("mining.debug", false);
        this.searchRadius = plugin.getConfig().getInt("mining.search-radius", 24);
        this.navRange = plugin.getConfig().getDouble("mining.navigator-range", 64.0);
        this.useAsyncPathfinder = plugin.getConfig().getBoolean("mining.use-async-pathfinder", true);
        this.timedBreaking = plugin.getConfig().getBoolean("mining.timed-breaking", true);
        this.breakSpeedModifier = Math.max(0.1, plugin.getConfig().getDouble("mining.break-speed-modifier", 1.0));

        this.depositManager = new DepositManager(plugin, this);
        this.recoveryService = new RecoveryService(plugin, this);
        this.escortService = new EscortService(plugin, this, depositManager);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(recoveryService, plugin);
        startCleanupTask();
        startSupplyMonitor();
        startCharmMonitor();
        startLifeguard();

        debug("JarvisNPC initialized (v0.2.0 butler mining)");
    }

    void debug(String message) {
        if (debugMode) {
            plugin.getLogger().info("[Jarvis Debug] " + message);
        }
    }

    // ==================== BUTLER MESSAGING ====================

    void say(Player player, String text) {
        player.sendMessage(Component.text("Jarvis: ", NamedTextColor.GOLD)
                .append(Component.text(text, NamedTextColor.WHITE)));
    }

    void sayQuiet(Player player, String text) {
        player.sendActionBar(Component.text("Jarvis: " + text, NamedTextColor.GRAY));
    }

    // ==================== NPC LIFECYCLE ====================

    public void summon(Player player) {
        NPC existing = playerNPCs.get(player.getUniqueId());
        if (existing != null && existing.isSpawned()) {
            say(player, "I'm already here, sir.");
            return;
        }
        if (existing != null) {
            // A dead registry entry from a failed spawn — clean it up properly
            playerNPCs.remove(player.getUniqueId());
            existing.destroy();
        }

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "Jarvis");
        Location spawnLoc = findSafeSpawnLocation(player.getLocation());

        npc.spawn(spawnLoc);
        npc.getOrAddTrait(Inventory.class);
        npc.setProtected(true);
        // v0.8.2: a butler who can swim — Citizens floats him to the surface
        // when he's in water instead of letting him sink and wedge.
        npc.data().setPersistent(NPC.Metadata.SWIM, true);
        playerNPCs.put(player.getUniqueId(), npc);

        // Navigator defaults: A* pathfinding, generous range, NO teleporting
        applyNavigatorDefaults(npc, null);

        giveStartingEquipment(npc);

        player.getWorld().playSound(spawnLoc, Sound.BLOCK_BELL_USE, 1.0f, 1.0f);
        say(player, "At your service.");

        debug("Jarvis spawned for " + player.getName() + " at " + formatLoc(spawnLoc));
    }

    public void dismiss(Player player) {
        UUID playerId = player.getUniqueId();
        NPC npc = playerNPCs.remove(playerId);
        if (npc == null) {
            say(player, "I haven't been summoned yet, sir.");
            return;
        }

        stopTask(player);
        dropInventoryItems(npc);
        miningStates.remove(playerId);
        submergedSeconds.remove(npc.getUniqueId());

        npc.destroy();
        say(player, "Until next time, sir.");

        debug("Jarvis dismissed for " + player.getName());
    }

    public void handlePlayerDisconnect(Player player) {
        UUID playerId = player.getUniqueId();
        NPC npc = playerNPCs.remove(playerId);
        if (npc == null) return;

        stopTask(player);
        if (npc.isSpawned()) {
            dropInventoryItems(npc);
        }
        miningStates.remove(playerId);
        submergedSeconds.remove(npc.getUniqueId());
        npc.destroy();

        debug("Cleaned up NPC for disconnected player: " + player.getName());
    }

    // ==================== NAVIGATION ====================

    /**
     * Apply butler-grade navigator defaults. Replaces Citizens' defaults,
     * most importantly TeleportStuckAction (the teleport-hopping culprit).
     */
    void applyNavigatorDefaults(NPC npc, Runnable onStuck) {
        Player owner = ownerOf(npc);
        if (owner != null) provider.applyNavigationDefaults(owner, onStuck);
    }

    /** Player-keyed form, for callers that never hold a Citizens NPC. */
    public void applyNavigatorDefaults(Player owner, Runnable onStuck) {
        provider.applyNavigationDefaults(owner, onStuck);
    }


    /** Set a navigation target ONCE. The tick loop watches progress; no per-tick re-targeting. */
    void navigateTo(NPC npc, Location dest, Runnable onStuck) {
        Player owner = ownerOf(npc);
        if (owner != null) provider.navigateTo(owner, dest, onStuck);
        debug("Navigating to " + formatLoc(dest));
    }

    /** Player-keyed form, for callers that never hold a Citizens NPC. */
    public void navigateTo(Player owner, Location dest, Runnable onStuck) {
        provider.navigateTo(owner, dest, onStuck);
    }

    /** Reverse lookup from NPC back to the player it belongs to. */
    private Player ownerOf(NPC npc) {
        if (npc == null) return null;
        for (Map.Entry<UUID, NPC> e : playerNPCs.entrySet()) {
            if (e.getValue().equals(npc)) return plugin.getServer().getPlayer(e.getKey());
        }
        return null;
    }


    // ==================== BLOCK BREAKING ====================

    // Active breaker task per NPC (v0.8.0): lets stopTask halt a dig mid-swing.
    private final Map<UUID, BukkitRunnable> activeBreakers = new ConcurrentHashMap<>();

    /**
     * Break a block the way a player would: face it, swing, crack animation,
     * vanilla timing — via Citizens' BlockBreaker. Falls back to instant
     * breaking if timed-breaking is disabled in config.
     */
    void breakBlockProperly(NPC npc, Block block, Consumer<Boolean> onDone) {
        ItemStack tool = getOrRestoreTool(npc);

        if (!timedBreaking) {
            boolean ok = block.breakNaturally(tool);
            onDone.accept(ok || block.getType() == Material.AIR);
            return;
        }

        npc.faceLocation(block.getLocation().add(0.5, 0.5, 0.5));

        BlockBreaker.BlockBreakerConfiguration cfg = new BlockBreaker.BlockBreakerConfiguration();
        cfg.item(tool);
        cfg.radius(REACH_DISTANCE + 1.5);
        // Citizens: damage-per-tick is MULTIPLIED by this — higher = faster.
        cfg.blockStrengthModifier((float) breakSpeedModifier);

        BlockBreaker breaker = npc.getBlockBreaker(block, cfg);
        Material expected = block.getType();

        if (breaker == null || !breaker.shouldExecute()) {
            // Can't run the breaker (block already gone etc.) — fall back to instant
            boolean ok = block.breakNaturally(tool);
            onDone.accept(ok || block.getType() == Material.AIR);
            return;
        }

        UUID npcId = npc.getUniqueId();
        BukkitRunnable breakTask = new BukkitRunnable() {
            int safety = 0;
            @Override
            public void run() {
                // Superseded or stopped externally (v0.8.0: /jarvis stop mid-dig)
                if (activeBreakers.get(npcId) != this) {
                    breaker.reset();
                    cancel();
                    return;
                }
                if (!npc.isSpawned() || ++safety > 600) { // 30s hard cap per block
                    activeBreakers.remove(npcId, this);
                    breaker.reset();
                    cancel();
                    onDone.accept(false);
                    return;
                }
                BehaviorStatus status = breaker.run();
                if (status == BehaviorStatus.RUNNING) return;

                activeBreakers.remove(npcId, this);
                breaker.reset();
                cancel();

                // Belt and braces: if the breaker finished but the block survived,
                // finish the job so the state machine never wedges.
                if (block.getType() == expected && expected != Material.AIR) {
                    block.breakNaturally(tool);
                }
                onDone.accept(block.getType() != expected);
            }
        };
        activeBreakers.put(npcId, breakTask);
        breakTask.runTaskTimer(plugin, 1L, 1L);
    }

    // ==================== MINING (state machine) ====================

    public void mine(Player player, String[] args) {
        Set<Material> oreFilter = null;
        if (args.length > 0) {
            String keyword = String.join(" ", args).toLowerCase().trim();
            for (Map.Entry<String, Set<Material>> entry : ORE_KEYWORDS.entrySet()) {
                if (keyword.contains(entry.getKey())) {
                    oreFilter = entry.getValue();
                    break;
                }
            }
        }
        mine(player, oreFilter);
    }

    public void mine(Player player) {
        mine(player, (Set<Material>) null);
    }

    private void mine(Player player, Set<Material> oreFilter) {
        NPC npc = getNPC(player);
        if (npc == null) {
            say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }

        stopTask(player);

        MiningState state = new MiningState();
        state.requestedOreTypes = oreFilter;
        miningStates.put(player.getUniqueId(), state);

        applyNavigatorDefaults(npc, () -> state.navStuck = true);
        giveStartingEquipment(npc); // Make sure the pickaxe is in hand

        if (oreFilter != null) {
            String oreName = oreFilter.iterator().next().name()
                .replace("DEEPSLATE_", "").replace("_ORE", "").replace("_", " ").toLowerCase();
            say(player, "Very good, sir. Commencing the search for " + oreName + ".");
        } else {
            say(player, "Very good, sir. I shall see to the excavation.");
        }

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!npc.isSpawned() || !player.isOnline()) {
                    cancel();
                    taskDone(player, this);
                    miningStates.remove(player.getUniqueId());
                    return;
                }

                Location npcLoc = getCurrentLocation(npc);
                state.ticksInPhase++;

                // Always sweep up nearby drops
                pickupNearbyItems(npc, npcLoc);

                // Bags full? Wrap up (and deliver, if a chest is registered).
                if (!state.breaking && lootSlotsUsed(npc) >= LOOT_CAPACITY - 2) {
                    cancel();
                    taskDone(player, this);
                    miningStates.remove(player.getUniqueId());
                    say(player, "My bags are full, sir. " + state.oresMined + " ores this trip.");
                    if (depositManager != null && depositManager.hasChest(player)) {
                        depositManager.startDepositRun(player,
                                depositManager.getChest(player), () -> {});
                    }
                    return;
                }

                switch (state.phase) {
                    case SEARCHING  -> tickSearching(npc, player, state, npcLoc);
                    case MOVING     -> tickMoving(npc, player, state, npcLoc);
                    case TUNNELING  -> tickTunneling(npc, player, state, npcLoc);
                    case MINING     -> tickMining(npc, player, state, npcLoc);
                    case COLLECTING -> tickCollecting(npc, player, state, npcLoc);
                }
            }
        };

        task.runTaskTimer(plugin, 0L, MINING_TICK_RATE);
        activeTasks.put(player.getUniqueId(), task);

        debug("Mining task started for " + player.getName());
    }

    /** SEARCHING — run one async ore scan; act on the result. */
    private void tickSearching(NPC npc, Player player, MiningState state, Location npcLoc) {
        if (state.scanInFlight) return;

        state.scanInFlight = true;
        scanForOreAsync(npcLoc, state.requestedOreTypes, state.unreachable, best -> {
            state.scanInFlight = false;
            // The mining task may have been stopped while we scanned
            if (miningStates.get(player.getUniqueId()) != state) return;

            if (best == null) {
                String suffix = state.requestedOreTypes != null
                        ? " None of that variety remain nearby." : "";
                say(player, "The seam appears exhausted, sir." + suffix
                        + " Final tally: " + state.oresMined + " ores.");
                stopTask(player);
                miningStates.remove(player.getUniqueId());
                return;
            }

            state.clearTarget();
            state.targetOre = best;
            state.targetOreType = best.getBlock().getType();
            sayQuiet(player, "Located " + formatOre(state.targetOreType) + ".");
            debug("Found ore: " + state.targetOreType + " at " + formatLoc(best));

            navigateTo(npc, best.clone().add(0.5, 0.5, 0.5), () -> state.navStuck = true);
            state.transitionTo(MiningPhase.MOVING);
        });
    }

    /** MOVING — try walking (works for exposed ores); switch to tunneling when pathing fails. */
    private void tickMoving(NPC npc, Player player, MiningState state, Location npcLoc) {
        if (state.targetOre == null) {
            state.transitionTo(MiningPhase.SEARCHING);
            return;
        }

        // Ore vanished while we walked (another player got it, etc.)
        if (!isOre(state.targetOre.getBlock().getType())) {
            debug("Target ore gone en route");
            npc.getNavigator().cancelNavigation();
            state.clearTarget();
            state.transitionTo(MiningPhase.SEARCHING);
            return;
        }

        double distance = npcLoc.distance(state.targetOre.clone().add(0.5, 0.5, 0.5));

        // Close enough — start mining
        if (distance <= REACH_DISTANCE) {
            npc.getNavigator().cancelNavigation();
            state.transitionTo(MiningPhase.MINING);
            return;
        }

        // Path failed or ended short — the ore is buried. Dig to it.
        if (state.navStuck || !npc.getNavigator().isNavigating()) {
            state.navStuck = false;
            npc.getNavigator().cancelNavigation();
            debug("Walking failed at distance " + String.format("%.1f", distance) + " — tunneling");
            state.transitionTo(MiningPhase.TUNNELING);
            return;
        }

        // Taking too long — tunnel the rest of the way
        if (state.ticksInPhase > MOVE_TIMEOUT_TICKS) {
            npc.getNavigator().cancelNavigation();
            state.transitionTo(MiningPhase.TUNNELING);
        }
    }

    /**
     * TUNNELING — the buried-ore workhorse. Repeat: plan one step cell toward
     * the ore, dig the 1-2 blocks occupying it (vanilla timing), walk into it,
     * plan the next. Descends and ascends as staircases. Never teleports more
     * than the single adjacent cell (and only if the 1-block walk stalls).
     */
    private void tickTunneling(NPC npc, Player player, MiningState state, Location npcLoc) {
        if (state.breaking) return; // A dig is in progress

        if (state.targetOre == null || !isOre(state.targetOre.getBlock().getType())) {
            npc.getNavigator().cancelNavigation();
            state.clearTarget();
            state.transitionTo(MiningPhase.SEARCHING);
            return;
        }

        Location oreCenter = state.targetOre.clone().add(0.5, 0.5, 0.5);

        // Reached the ore?
        if (npcLoc.distance(oreCenter) <= REACH_DISTANCE) {
            npc.getNavigator().cancelNavigation();
            state.transitionTo(MiningPhase.MINING);
            return;
        }

        // Out of patience for this target?
        if (state.tunnelSteps > MAX_TUNNEL_STEPS) {
            abandonTarget(player, state, "That one is buried deeper than it's worth, sir. Moving on.");
            return;
        }

        // 1) Dig any pending blocks for the current step
        if (!state.digQueue.isEmpty()) {
            Block toDig = state.digQueue.peek().getBlock();

            if (isPassable(toDig)) {          // already clear
                state.digQueue.poll();
                return;
            }
            if (toDig.getLocation().getBlockX() == state.targetOre.getBlockX()
                    && toDig.getLocation().getBlockY() == state.targetOre.getBlockY()
                    && toDig.getLocation().getBlockZ() == state.targetOre.getBlockZ()) {
                // We tunneled right into the target — mine it properly
                npc.getNavigator().cancelNavigation();
                state.transitionTo(MiningPhase.MINING);
                return;
            }
            if (!canDig(toDig)) {
                abandonTarget(player, state, "Something rather solid is in the way, sir. Moving on.");
                return;
            }

            state.breaking = true;
            debug("Tunnel dig: " + toDig.getType() + " at " + formatLoc(toDig.getLocation()));
            breakBlockProperly(npc, toDig, success -> {
                state.breaking = false;
                if (miningStates.get(player.getUniqueId()) != state) return;
                if (success) {
                    state.digQueue.poll();
                } else {
                    abandonTarget(player, state, "That block refuses to cooperate, sir. Moving on.");
                }
            });
            return;
        }

        // 2) Walk into the cleared step cell
        if (state.stepCell != null) {
            Location cellCenter = state.stepCell.clone().add(0.5, 0, 0.5);
            double horiz = Math.hypot(npcLoc.getX() - cellCenter.getX(), npcLoc.getZ() - cellCenter.getZ());
            double vert = Math.abs(npcLoc.getY() - state.stepCell.getY());

            if (horiz < 0.7 && vert < 1.3) {  // arrived in the cell
                state.stepCell = null;
                state.advanceTicks = 0;
                return;
            }

            state.advanceTicks++;
            if (!npc.getNavigator().isNavigating() || state.navStuck) {
                state.navStuck = false;
                navigateTo(npc, cellCenter, () -> state.navStuck = true);
            }
            if (state.advanceTicks > ADVANCE_NUDGE_TICKS) {
                // The 1-block walk stalled (Citizens A* dislikes fresh tunnels
                // sometimes) — nudge him the single block. Visually a step.
                npc.getNavigator().cancelNavigation();
                Location nudge = cellCenter.clone();
                nudge.setYaw(npcLoc.getYaw());
                nudge.setPitch(npcLoc.getPitch());
                npc.teleport(nudge, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                debug("Nudged into step cell " + formatLoc(state.stepCell));
                state.stepCell = null;
                state.advanceTicks = 0;
            }
            return;
        }

        // 3) Plan the next step toward the ore
        planTunnelStep(npc, player, state, npcLoc);
    }

    /** Plan one tunnel step: pick the next cell and queue the blocks to dig for it. */
    private void planTunnelStep(NPC npc, Player player, MiningState state, Location npcLoc) {
        World world = npcLoc.getWorld();
        int fx = npcLoc.getBlockX(), fy = npcLoc.getBlockY(), fz = npcLoc.getBlockZ();
        int ox = state.targetOre.getBlockX(), oy = state.targetOre.getBlockY(), oz = state.targetOre.getBlockZ();

        int dxT = ox - fx, dyT = oy - fy, dzT = oz - fz;
        int adx = Math.abs(dxT), ady = Math.abs(dyT), adz = Math.abs(dzT);

        // Horizontal direction: dominant horizontal axis; zigzag if none
        int hx = 0, hz = 0;
        if (adx >= adz && adx > 0) hx = Integer.signum(dxT);
        else if (adz > 0) hz = Integer.signum(dzT);
        else hx = ((fy & 1) == 0) ? 1 : -1; // straight up/down: zigzag staircase

        Location cell;
        List<Location> toDig = new ArrayList<>();

        if (dyT < -1 || (dyT < 0 && ady >= Math.max(adx, adz))) {
            // Staircase DOWN: step forward and one down.
            // v0.8.0: dug 2-high per step (3 blocks in the forward column) so
            // the player can walk the stairs behind him without breaking blocks.
            cell = new Location(world, fx + hx, fy - 1, fz + hz);
            toDig.add(new Location(world, fx + hx, fy + 1, fz + hz));   // player head room on the step
            toDig.add(new Location(world, fx + hx, fy, fz + hz));      // head space of the lower step
            toDig.add(cell.clone());                                    // feet of the lower step
        } else if (dyT > 1 || (dyT > 0 && ady >= Math.max(adx, adz))) {
            // Staircase UP: clear own headroom, step forward and one up.
            // v0.8.0: same 2-high clearance on the way up.
            cell = new Location(world, fx + hx, fy + 1, fz + hz);
            toDig.add(new Location(world, fx, fy + 2, fz));             // room above own head
            toDig.add(new Location(world, fx + hx, fy + 3, fz + hz));   // player head room on the step
            toDig.add(new Location(world, fx + hx, fy + 2, fz + hz));   // head space of the upper step
            toDig.add(cell.clone());                                    // feet of the upper step
        } else {
            // Horizontal 1x2 corridor
            cell = new Location(world, fx + hx, fy, fz + hz);
            toDig.add(cell.clone());                                    // feet
            toDig.add(new Location(world, fx + hx, fy + 1, fz + hz));   // head
        }

        // ---- Safety checks ----
        // Fluids in any dig cell or just beyond it = stop (don't open a lava pocket)
        for (Location dig : toDig) {
            Block b = dig.getBlock();
            if (isFluid(b.getType())) {
                abandonTarget(player, state, "There's liquid that way, sir. I'd rather not. Moving on.");
                return;
            }
            Block beyond = dig.clone().add(hx, 0, hz).getBlock();
            if (beyond.getType() == Material.LAVA) {
                abandonTarget(player, state, "Lava ahead, sir. I'd rather not melt. Moving on.");
                return;
            }
        }
        // Floor of the destination cell: solid, or at most a 1-block drop; never lava
        Block below = cell.clone().add(0, -1, 0).getBlock();
        if (isFluid(below.getType())) {
            abandonTarget(player, state, "The footing that way is treacherous, sir. Moving on.");
            return;
        }
        if (isPassable(below)) {
            Block below2 = cell.clone().add(0, -2, 0).getBlock();
            if (!below2.getType().isSolid()) {
                abandonTarget(player, state, "There's a drop that way I don't fancy, sir. Moving on.");
                return;
            }
        }

        // Queue only blocks that actually need digging
        for (Location dig : toDig) {
            Block b = dig.getBlock();
            if (!isPassable(b)) {
                state.digQueue.add(dig);
            }
        }

        state.stepCell = cell;
        state.advanceTicks = 0;
        state.tunnelSteps++;
        debug("Tunnel step " + state.tunnelSteps + " -> cell " + formatLoc(cell)
                + " (" + state.digQueue.size() + " blocks to dig)");
    }

    boolean isPassable(Block b) {
        Material t = b.getType();
        return !t.isSolid() && !isFluid(t);
    }

    boolean isFluid(Material t) {
        return t == Material.LAVA || t == Material.WATER;
    }

    /** Blocks Jarvis is willing to tunnel through: fillers and ores. Nothing precious. */
    boolean canDig(Block b) {
        Material t = b.getType();
        return DIGGABLE.contains(t) || isOre(t);
    }

    /** MINING — break the ore with vanilla timing and animations. */
    private void tickMining(NPC npc, Player player, MiningState state, Location npcLoc) {
        if (state.breaking) {
            // Safety timeout while the breaker works
            if (state.ticksInPhase > 80) { // 40s
                debug("MINING timeout");
                abandonTarget(player, state, "That block is being unusually stubborn. Moving on.");
            }
            return;
        }

        if (state.targetOre == null) {
            state.transitionTo(MiningPhase.SEARCHING);
            return;
        }

        Block oreBlock = state.targetOre.getBlock();
        if (!isOre(oreBlock.getType())) {
            debug("Ore already gone at mining time");
            state.collectLocation = state.targetOre.clone().add(0.5, 0.5, 0.5);
            state.clearTarget();
            state.transitionTo(MiningPhase.COLLECTING);
            return;
        }

        double distance = npcLoc.distance(state.targetOre.clone().add(0.5, 0.5, 0.5));
        if (distance > REACH_DISTANCE + 1) {
            state.transitionTo(MiningPhase.TUNNELING);
            return;
        }

        Material oreType = oreBlock.getType();
        state.breaking = true;

        breakBlockProperly(npc, oreBlock, success -> {
            state.breaking = false;
            if (miningStates.get(player.getUniqueId()) != state) return;

            if (success) {
                state.oresMined++;
                sayQuiet(player, "Mined " + formatOre(oreType) + " — " + state.oresMined + " so far.");
                if (state.oresMined % 10 == 0) {
                    say(player, state.oresMined + " ores and counting, sir. The collection grows.");
                }
                state.collectLocation = state.targetOre != null
                        ? state.targetOre.clone().add(0.5, 0.5, 0.5) : null;
                state.clearTarget();
                state.transitionTo(MiningPhase.COLLECTING);
            } else {
                abandonTarget(player, state, "I'm unable to break that " + formatOre(oreType) + ", sir. Moving on.");
            }
        });
    }

    /** COLLECTING — walk to the drops and pick them up. */
    private void tickCollecting(NPC npc, Player player, MiningState state, Location npcLoc) {
        if (state.ticksInPhase > 12) { // 6s max
            state.transitionTo(MiningPhase.SEARCHING);
            return;
        }

        // Give item entities a moment to spawn
        if (state.ticksInPhase < 2) return;

        if (state.collectLocation != null) {
            double dist = npcLoc.distance(state.collectLocation);
            if (dist > PICKUP_RADIUS) {
                Navigator nav = npc.getNavigator();
                if (!nav.isNavigating()) {
                    navigateTo(npc, state.collectLocation, () -> state.navStuck = true);
                }
                return;
            }
        }

        pickupNearbyItems(npc, npcLoc);

        boolean itemsNearby = false;
        if (npc.getEntity() != null) {
            for (Entity e : npc.getEntity().getNearbyEntities(PICKUP_RADIUS, PICKUP_RADIUS, PICKUP_RADIUS)) {
                if (e instanceof Item item && !JUNK_DROPS.contains(item.getItemStack().getType())) {
                    itemsNearby = true;
                    break;
                }
            }
        }

        if (!itemsNearby) {
            state.transitionTo(MiningPhase.SEARCHING);
        }
    }

    private void abandonTarget(Player player, MiningState state, String message) {
        if (state.targetOre != null) {
            state.unreachable.add(MiningState.key(state.targetOre));
        }
        sayQuiet(player, message);
        state.clearTarget();
        state.transitionTo(MiningPhase.SEARCHING);
    }

    // ==================== ORE SCANNING (async) ====================

    /**
     * Scan for the best ore off the main thread using chunk snapshots.
     * Snapshots are taken on the main thread (cheap), the O(radius³) sweep
     * happens async, and the winner is delivered back on the main thread.
     */
    private void scanForOreAsync(Location center, Set<Material> filter,
                                 Set<Long> unreachable, Consumer<Location> callback) {
        World world = center.getWorld();
        if (world == null) {
            callback.accept(null);
            return;
        }

        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        int r = searchRadius;
        int minY = Math.max(world.getMinHeight(), cy - r);
        int maxY = Math.min(world.getMaxHeight() - 1, cy + r);

        // Snapshot every chunk the search cube touches (main thread, cheap)
        Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        int minCX = (cx - r) >> 4, maxCX = (cx + r) >> 4;
        int minCZ = (cz - r) >> 4, maxCZ = (cz + r) >> 4;
        for (int chunkX = minCX; chunkX <= maxCX; chunkX++) {
            for (int chunkZ = minCZ; chunkZ <= maxCZ; chunkZ++) {
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    snapshots.put(chunkKey(chunkX, chunkZ),
                            world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false));
                }
            }
        }

        Set<Long> unreachableCopy = new HashSet<>(unreachable);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int bestX = 0, bestY = 0, bestZ = 0;
            double bestDistSq = Double.MAX_VALUE;
            int bestPriority = Integer.MAX_VALUE;
            boolean found = false;

            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    ChunkSnapshot snap = snapshots.get(chunkKey(x >> 4, z >> 4));
                    if (snap == null) continue;
                    int lx = x & 15, lz = z & 15;
                    for (int y = minY; y <= maxY; y++) {
                        Material type = snap.getBlockType(lx, y, lz);

                        if (filter != null) {
                            if (!filter.contains(type)) continue;
                        } else if (!isOre(type)) {
                            continue;
                        }

                        long key = ((long) x & 0x3FFFFFF) << 38
                                 | ((long) z & 0x3FFFFFF) << 12
                                 | ((long) (y + 2048) & 0xFFF);
                        if (unreachableCopy.contains(key)) continue;

                        int priority = ORE_PRIORITY.indexOf(type);
                        if (priority < 0) priority = 999;

                        double distSq = (double) (x - cx) * (x - cx)
                                      + (double) (y - cy) * (y - cy)
                                      + (double) (z - cz) * (z - cz);
                        if (priority < bestPriority
                                || (priority == bestPriority && distSq < bestDistSq)) {
                            bestX = x; bestY = y; bestZ = z;
                            bestDistSq = distSq;
                            bestPriority = priority;
                            found = true;
                        }
                    }
                }
            }

            final boolean f = found;
            final int fx = bestX, fy = bestY, fz = bestZ;
            Bukkit.getScheduler().runTask(plugin, () ->
                    callback.accept(f ? new Location(world, fx, fy, fz) : null));
        });
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    boolean isOre(Material type) {
        String name = type.name();
        return name.contains("_ORE") || type == Material.ANCIENT_DEBRIS;
    }

    // ==================== ITEM PICKUP ====================

    void pickupNearbyItems(NPC npc, Location npcLoc) {
        pickupNearbyItems(npc, npcLoc, false);
    }

    /**
     * Sweep up nearby drops into the bags (slots 1+).
     * v0.8.0: partial merges no longer destroy the remainder of a stack —
     * whatever the bags can't absorb stays on the ground.
     */
    void pickupNearbyItems(NPC npc, Location npcLoc, boolean includeJunk) {
        if (npc.getEntity() == null) return;

        Inventory invTrait = npc.getOrAddTrait(Inventory.class);

        for (Entity entity : npc.getEntity().getNearbyEntities(PICKUP_RADIUS, PICKUP_RADIUS, PICKUP_RADIUS)) {
            if (entity instanceof Item item) {
                ItemStack stack = item.getItemStack();
                if (!includeJunk && JUNK_DROPS.contains(stack.getType())) continue;

                ItemStack[] contents = invTrait.getContents();
                int remaining = stack.getAmount();

                // Slot 0 is the NPC's HAND for player NPCs — never store loot there
                for (int i = 1; i < contents.length && remaining > 0; i++) {
                    if (contents[i] == null || contents[i].getType() == Material.AIR) {
                        ItemStack placed = stack.clone();
                        placed.setAmount(remaining);
                        contents[i] = placed;
                        remaining = 0;
                    } else if (contents[i].isSimilar(stack)
                               && contents[i].getAmount() < contents[i].getMaxStackSize()) {
                        int canAdd = contents[i].getMaxStackSize() - contents[i].getAmount();
                        int toAdd = Math.min(canAdd, remaining);
                        contents[i].setAmount(contents[i].getAmount() + toAdd);
                        remaining -= toAdd;
                    }
                }

                if (remaining < stack.getAmount()) {
                    invTrait.setContents(contents);
                    if (remaining == 0) {
                        item.remove();                 // fully absorbed
                    } else {
                        ItemStack rest = stack.clone(); // bags full mid-stack: leave the rest
                        rest.setAmount(remaining);
                        item.setItemStack(rest);
                    }
                    npcLoc.getWorld().playSound(npcLoc, Sound.ENTITY_ITEM_PICKUP, 0.3f, 1.2f);
                }
            }
        }
    }

    // ==================== COMBAT / DEFENDER (v0.4.0) ====================

    /** Legacy alias: /jarvis attack = aggressive bodyguard. */
    public void attack(Player player) {
        guard(player, "aggressive");
    }

    /** v0.4.0: bodyguard mode with stances. */
    public void guard(Player player, String stanceArg) {
        NPC npc = getNPC(player);
        if (npc == null) {
            say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }

        Defender.Stance stance = parseStance(stanceArg, Defender.Stance.DEFENSIVE);

        // Adjust stance in place — but only if already in BODYGUARD mode
        // (v0.8.0: a sentry/patrol Defender must be replaced, or he'd hold
        // the old post instead of guarding you)
        Defender existing = activeDefenders.get(player.getUniqueId());
        if (existing != null && existing.getMode() == Defender.Mode.BODYGUARD) {
            existing.setStance(stance);
            say(player, switch (stance) {
                case PASSIVE -> "Standing by, sir. Observing only.";
                case DEFENSIVE -> "Defensive posture, sir. I'll answer any aggression.";
                case AGGRESSIVE -> "Weapons free, sir.";
            });
            return;
        }

        stopTask(player);
        miningStates.remove(player.getUniqueId());

        Defender defender = new Defender(this, player, npc, stance, Defender.Mode.BODYGUARD);
        activeDefenders.put(player.getUniqueId(), defender);
        defender.start();
    }

    /** v0.4.0: night watch — hold the current position as a sentry post. */
    public void watch(Player player, String stanceArg) {
        NPC npc = getNPC(player);
        if (npc == null) {
            say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }

        stopTask(player);
        miningStates.remove(player.getUniqueId());

        Defender.Stance stance = parseStance(stanceArg, Defender.Stance.AGGRESSIVE);
        Defender defender = new Defender(this, player, npc, stance, Defender.Mode.SENTRY);
        activeDefenders.put(player.getUniqueId(), defender);
        defender.start();
    }

    private Defender.Stance parseStance(String arg, Defender.Stance fallback) {
        if (arg == null) return fallback;
        return switch (arg.toLowerCase()) {
            case "passive", "hold", "stand-down" -> Defender.Stance.PASSIVE;
            case "defensive", "defend", "guard" -> Defender.Stance.DEFENSIVE;
            case "aggressive", "attack", "free" -> Defender.Stance.AGGRESSIVE;
            default -> fallback;
        };
    }

    /** Forward damage events to the owning player's Defender (retaliation memory). */
    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (activeDefenders.isEmpty()) return;

        Entity victim = event.getEntity();

        // The player got hurt
        if (victim instanceof Player p) {
            Defender defender = activeDefenders.get(p.getUniqueId());
            if (defender != null) {
                defender.recordThreat(event.getDamager());
            }
            return;
        }

        // Jarvis himself got hurt — find his owner
        for (Map.Entry<UUID, NPC> entry : playerNPCs.entrySet()) {
            if (victim.equals(entry.getValue().getEntity())) {
                Defender defender = activeDefenders.get(entry.getKey());
                if (defender != null) {
                    defender.recordThreat(event.getDamager());
                }
                return;
            }
        }
    }

    /**
     * Tools that make up Jarvis's kit. v0.8.0: kit tools live ONLY in slot 0
     * (his hand) — anything in slots 1+ is the player's loot, even if it
     * happens to be a diamond tool, and is dropped/deposited/handed over
     * like everything else. (The old filter silently confiscated player
     * tools that matched the kit.)
     */
    static final Set<Material> KIT_TOOLS = Set.of(
            Material.DIAMOND_PICKAXE, Material.DIAMOND_SWORD, Material.DIAMOND_AXE,
            Material.DIAMOND_HOE, Material.FISHING_ROD);

    /** Put a tool in Jarvis's hand (slot 0 IS the held slot for player NPCs). */
    void equipTool(NPC npc, Material tool) {
        ItemStack item = new ItemStack(tool);
        Inventory inv = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = inv.getContents();
        contents[0] = item;
        inv.setContents(contents);
        npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, item);
    }

    /** The item currently in hand (no restore logic). */
    ItemStack getToolInHand(NPC npc) {
        ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
        return tool != null ? tool : new ItemStack(Material.AIR);
    }

    /** Guard-mode loadout: sword in hand. The pickaxe returns when mining does. */
    void giveGuardEquipment(NPC npc) {
        equipTool(npc, Material.DIAMOND_SWORD);
    }

    // ==================== v0.7.0 ACTIVITIES ====================

    /** Farming: one sweep, or a standing tend shift. */
    public void farm(Player player, String cropKeyword, boolean tend) {
        NPC npc = getNPC(player);
        if (npc == null) {
            say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }
        stopTask(player);
        miningStates.remove(player.getUniqueId());
        Material crop = Farmer.cropFromKeyword(cropKeyword);
        new Farmer(this, player, npc, depositManager, crop, tend).start();
    }

    /** Lumberjack: fell N trees, replant saplings. */
    public void chop(Player player, int trees) {
        NPC npc = getNPC(player);
        if (npc == null) {
            say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }
        stopTask(player);
        miningStates.remove(player.getUniqueId());
        new Lumberjack(this, player, npc, depositManager, trees).start();
    }

    /** Fishing at the nearest water's edge. */
    public void fish(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) {
            say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }
        stopTask(player);
        miningStates.remove(player.getUniqueId());
        new Fisherman(this, player, npc, depositManager).start();
    }

    /** The dance. */
    public void dance(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) {
            say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }
        Entertainer.dance(this, player, npc);
    }

    /**
     * v0.8.0 Lamplighter: spawn-proof the area around the player with a grid
     * of lights. radius/spacing <= 0 and type == null mean "use config".
     */
    public void light(Player player, int radius, String type, int spacing) {
        NPC npc = getNPC(player);
        if (npc == null) {
            say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }
        stopTask(player);
        miningStates.remove(player.getUniqueId());
        new Lamplighter(this, player, npc, radius, type, spacing).start();
    }

    /** Patrol: walk a persisted waypoint circuit as a sentry. */
    public void patrol(Player player, String sub) {
        NPC npc = getNPC(player);
        if (npc == null) {
            say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }
        switch (sub == null ? "start" : sub.toLowerCase()) {
            case "add" -> {
                int n = depositManager.addPatrolPoint(player, player.getLocation());
                say(player, "Waypoint " + n + " noted, sir.");
            }
            case "clear" -> {
                depositManager.clearPatrol(player);
                say(player, "Patrol route cleared, sir.");
            }
            default -> {
                java.util.List<Location> route = depositManager.getPatrol(player);
                if (route.size() < 2) {
                    say(player, "I need at least two waypoints, sir — stand at each and say '/jarvis patrol add'.");
                    return;
                }
                stopTask(player);
                miningStates.remove(player.getUniqueId());
                Defender defender = new Defender(this, player, npc,
                        Defender.Stance.AGGRESSIVE, Defender.Mode.PATROL);
                defender.setPatrolRoute(route);
                activeDefenders.put(player.getUniqueId(), defender);
                defender.start();
            }
        }
    }

    // ==================== OTHER COMMANDS ====================

    public void returnToPlayer(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;

        stopTask(player);

        Location npcLoc = getCurrentLocation(npc);
        Location playerLoc = player.getLocation();

        // Walk when reasonable; teleport only across real distance
        if (npcLoc.getWorld() == playerLoc.getWorld()
                && npcLoc.distance(playerLoc) <= LAST_RESORT_TELEPORT_DISTANCE) {
            applyNavigatorDefaults(npc, null);
            npc.getNavigator().setTarget(playerLoc);
            say(player, "On my way, sir.");
        } else {
            Location safeLoc = findSafeSpawnLocation(playerLoc);
            npc.teleport(safeLoc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            say(player, "Right behind you, sir.");
        }
    }

    public void openInventory(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) {
            say(player, "I haven't been summoned yet, sir.");
            return;
        }
        npc.getOrAddTrait(Inventory.class).openInventory(player);
    }

    public void clearInventory(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) {
            say(player, "I haven't been summoned yet, sir.");
            return;
        }

        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        Location dropLoc = getCurrentLocation(npc);

        ItemStack[] contents = invTrait.getContents();
        int dropped = 0;

        // Slot 0 is his hand (the pickaxe) — leave it alone; slots 1+ are all loot
        for (int i = 1; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() != Material.AIR) {
                dropLoc.getWorld().dropItemNaturally(dropLoc, item.clone());
                contents[i] = null;
                dropped += item.getAmount();
            }
        }

        invTrait.setContents(contents);
        giveStartingEquipment(npc);
        say(player, "Deposited " + dropped + " items at my feet, sir.");
    }

    /** v0.2.0: the deterministic branch mine — staircase, gallery, branches, torches. */
    public void startBranchMining(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) {
            say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }
        stopTask(player);
        miningStates.remove(player.getUniqueId());
        new BranchMiner(this, player, npc, depositManager).start();
    }

    /** v0.2.0: follow mode — trail the player, carry the loot. */
    public void follow(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) {
            say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }

        stopTask(player);
        applyNavigatorDefaults(npc, null);
        say(player, "Right behind you, sir.");

        BukkitRunnable task = new BukkitRunnable() {
            // v0.8.0 stall watchdog: he used to wedge on fences/corners and
            // simply stand there until the 40-block teleport kicked in.
            Location lastPos = null;
            int stallTicks = 0;

            @Override
            public void run() {
                if (!npc.isSpawned() || !player.isOnline()) {
                    cancel();
                    taskDone(player, this);
                    return;
                }

                Location npcLoc = getCurrentLocation(npc);
                Location playerLoc = player.getLocation();

                if (npcLoc.getWorld() != playerLoc.getWorld()
                        || npcLoc.distance(playerLoc) > LAST_RESORT_TELEPORT_DISTANCE) {
                    // Fell far behind (elytra, portals) — catch up
                    npc.getNavigator().cancelNavigation();
                    npc.teleport(findSafeSpawnLocation(playerLoc),
                            org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                    lastPos = null;
                    stallTicks = 0;
                    return;
                }

                double dist = npcLoc.distance(playerLoc);

                // Watchdog: behind AND not actually moving for ~4s = stuck.
                // Re-path first; if that fails too, a short catch-up hop.
                if (dist > 6.0 && lastPos != null
                        && lastPos.getWorld() == npcLoc.getWorld()
                        && npcLoc.distanceSquared(lastPos) < 0.09) {
                    stallTicks++;
                    if (stallTicks == 2) {
                        // First remedy: force a fresh path
                        npc.getNavigator().cancelNavigation();
                        npc.getNavigator().setTarget(player, false);
                    } else if (stallTicks >= 4) {
                        npc.getNavigator().cancelNavigation();
                        npc.teleport(findSafeSpawnLocation(playerLoc),
                                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                        sayQuiet(player, "Caught up, sir.");
                        stallTicks = 0;
                    }
                } else {
                    stallTicks = 0;
                }
                lastPos = npcLoc.clone();

                if (dist > 3.0 && !npc.getNavigator().isNavigating()) {
                    // Follow the entity — Citizens tracks a moving target on its own
                    npc.getNavigator().setTarget(player, false);
                } else if (dist <= 2.0 && npc.getNavigator().isNavigating()) {
                    npc.getNavigator().cancelNavigation();
                }

                pickupNearbyItems(npc, npcLoc);
            }
        };

        task.runTaskTimer(plugin, 0L, 20L);
        activeTasks.put(player.getUniqueId(), task);
    }

    public void stop(Player player) {
        stopTask(player);
        miningStates.remove(player.getUniqueId());
        say(player, "Standing down, sir.");
    }

    /** Register a task as THE active task for this player (cancels via stopTask). */
    void registerTask(Player player, BukkitRunnable task) {
        activeTasks.put(player.getUniqueId(), task);
    }

    Jarvis getPlugin() {
        return plugin;
    }

    public DepositManager getDepositManager() {
        return depositManager;
    }

    public RecoveryService getRecoveryService() {
        return recoveryService;
    }

    public EscortService getEscortService() {
        return escortService;
    }

    /** Find a safe standing spot near a location (public helper for services). */
    Location findSafeNear(Location near) {
        return findSafeSpawnLocation(near);
    }

    /**
     * v0.8.0: face someone with a LEVEL gaze. Citizens computes the pitch
     * from the NPC's position to the target, so aiming at a nearby player's
     * eyes made Jarvis crane his neck at the sky and stay that way (the
     * summon-then-stare bug). Aiming at his own eye height keeps it level.
     */
    void faceLevel(NPC npc, Location target) {
        Location aim = target.clone();
        aim.setY(getCurrentLocation(npc).getY() + 1.62); // own eye height = pitch ~0
        npc.faceLocation(aim);
    }

    /** Loot slots used out of the 35 storage slots (slot 0 is the pickaxe). */
    int lootSlotsUsed(NPC npc) {
        ItemStack[] contents = npc.getOrAddTrait(Inventory.class).getContents();
        int used = 0;
        for (int i = 1; i < Math.min(36, contents.length); i++) {
            if (contents[i] != null && contents[i].getType() != Material.AIR) used++;
        }
        return used;
    }

    static final int LOOT_CAPACITY = 35;

    /** Public wrapper for the steward report. */
    public int lootSlotsUsedPublic(NPC npc) {
        return lootSlotsUsed(npc);
    }

    public void stopTask(Player player) {
        activeDefenders.remove(player.getUniqueId());
        BukkitRunnable task = activeTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }

        NPC npc = getNPC(player);
        if (npc != null) {
            // Halt any block-break in progress (the breaker task self-cancels
            // and resets the crack animation once its map entry is gone)
            activeBreakers.remove(npc.getUniqueId());
            if (npc.getNavigator() != null) {
                npc.getNavigator().cancelNavigation();
            }
        }
    }

    /**
     * v0.8.0: tasks that finish on their own call this so the register stays
     * accurate (otherwise idle detection and task counts go stale).
     */
    void taskDone(Player player, BukkitRunnable task) {
        activeTasks.remove(player.getUniqueId(), task);
    }

    // ==================== UTILITY METHODS ====================

    /** The pickaxe lives in hand (inventory slot 0). Restore it if anything displaced it. */
    ItemStack getOrRestoreTool(NPC npc) {
        ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
        if (tool == null || !tool.getType().name().endsWith("_PICKAXE")) {
            debug("Pickaxe missing from hand — restoring");
            giveStartingEquipment(npc);
            tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
        }
        return tool;
    }

    public NPC getNPC(Player player) {
        return playerNPCs.get(player.getUniqueId());
    }

    Location getCurrentLocation(NPC npc) {
        if (npc.getEntity() != null) {
            return npc.getEntity().getLocation();
        }
        return npc.getStoredLocation();
    }

    private Location findSafeSpawnLocation(Location center) {
        for (int dx = 0; dx <= 3; dx++) {
            for (int dz = 0; dz <= 3; dz++) {
                for (int dir = 0; dir < 4; dir++) {
                    int x = (dir == 0 || dir == 2) ? dx : -dx;
                    int z = (dir == 0 || dir == 1) ? dz : -dz;

                    Location check = center.clone().add(x, 0, z);
                    if (isSafeToStand(check)) {
                        return check;
                    }
                }
            }
        }
        return center;
    }

    private boolean isSafeToStand(Location loc) {
        Block feet = loc.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);

        if (!ground.getType().isSolid()) return false;
        if (feet.getType().isSolid()) return false;
        if (head.getType().isSolid()) return false;

        Material groundType = ground.getType();
        if (groundType == Material.LAVA || groundType == Material.FIRE ||
            groundType == Material.MAGMA_BLOCK || groundType == Material.CACTUS) {
            return false;
        }

        Material feetType = feet.getType();
        Material headType = head.getType();
        if (feetType == Material.WATER || feetType == Material.LAVA ||
            headType == Material.WATER || headType == Material.LAVA) {
            return false;
        }

        return true;
    }

    /**
     * Give Jarvis his pickaxe — IN HAND. For player-type NPCs, inventory
     * slot 0 is the held hotbar slot, so the pickaxe must live there (and
     * nothing else may be written to slot 0, ever).
     */
    void giveStartingEquipment(NPC npc) {
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        pickaxe.addUnsafeEnchantment(Enchantment.FORTUNE, 3);

        Inventory inv = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = inv.getContents();
        contents[0] = pickaxe;
        inv.setContents(contents);

        // Keep the Equipment trait in sync so the held item renders correctly
        npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, pickaxe);
    }

    private void dropInventoryItems(NPC npc) {
        if (!npc.isSpawned()) return;

        Location dropLoc = getCurrentLocation(npc);
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();

        // Slot 0 is his pickaxe — everything else gets handed over
        // (v0.8.0: including player-owned tools; only slot 0 is the kit)
        for (int i = 1; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() != Material.AIR) {
                dropLoc.getWorld().dropItemNaturally(dropLoc, item.clone());
            }
        }
    }

    String formatLoc(Location loc) {
        return String.format("(%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ());
    }

    String formatOre(Material ore) {
        String name = ore.name().replace("DEEPSLATE_", "").replace("_ORE", "").replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
    }

    // ==================== STATUS & INFO ====================

    public int getActiveNpcCount() {
        return playerNPCs.size();
    }

    public int getActiveTaskCount() {
        return activeTasks.size();
    }

    // ==================== PLAYER-KEYED OVERLOADS ====================
    //
    // The helper classes (Farmer, Fisherman, Defender, ...) used to be handed a
    // Citizens NPC and call these directly. These thin forms let them work from
    // the player alone, so nothing outside this class and the provider names a
    // Citizens type. The NPC-taking originals stay for this class's own use.

    void breakBlockProperly(Player player, Block block, Consumer<Boolean> onDone) {
        NPC npc = getNPC(player);
        if (npc == null) { onDone.accept(false); return; }
        breakBlockProperly(npc, block, onDone);
    }

    void pickupNearbyItems(Player player, Location npcLoc) {
        NPC npc = getNPC(player);
        if (npc != null) pickupNearbyItems(npc, npcLoc);
    }

    void pickupNearbyItems(Player player, Location npcLoc, boolean includeJunk) {
        NPC npc = getNPC(player);
        if (npc != null) pickupNearbyItems(npc, npcLoc, includeJunk);
    }

    void equipTool(Player player, Material tool) {
        NPC npc = getNPC(player);
        if (npc != null) equipTool(npc, tool);
    }

    ItemStack getToolInHand(Player player) {
        NPC npc = getNPC(player);
        return npc == null ? null : getToolInHand(npc);
    }

    void giveGuardEquipment(Player player) {
        NPC npc = getNPC(player);
        if (npc != null) giveGuardEquipment(npc);
    }

    void giveStartingEquipment(Player player) {
        NPC npc = getNPC(player);
        if (npc != null) giveStartingEquipment(npc);
    }

    void faceLevel(Player player, Location target) {
        NPC npc = getNPC(player);
        if (npc != null) faceLevel(npc, target);
    }

    int lootSlotsUsed(Player player) {
        NPC npc = getNPC(player);
        return npc == null ? 0 : lootSlotsUsed(npc);
    }

    ItemStack getOrRestoreTool(Player player) {
        NPC npc = getNPC(player);
        return npc == null ? null : getOrRestoreTool(npc);
    }

    Location getCurrentLocation(Player player) {
        NPC npc = getNPC(player);
        return npc == null ? null : getCurrentLocation(npc);
    }

    /** The NPC backend, for everything that drives the NPC without knowing Citizens. */
    public INPCProvider getProvider() {
        return provider;
    }

    public NPC getNPCForPlayer(UUID uuid) {
        return playerNPCs.get(uuid);
    }

    public void dismissAll() {
        for (NPC npc : playerNPCs.values()) {
            if (npc.isSpawned()) {
                dropInventoryItems(npc);
            }
            npc.destroy();
        }
        playerNPCs.clear();
        activeTasks.values().forEach(BukkitRunnable::cancel);
        activeTasks.clear();
        miningStates.clear();
        activeDefenders.clear();
    }

    // ==================== CHARM (v0.7.0) ====================

    private final Map<UUID, Long> ownerAwaySince = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGreeting = new ConcurrentHashMap<>();
    private static final String[] GREETINGS = {
            "Welcome back, sir. The estate stood ready.",
            "Ah — there you are, sir. All quiet in your absence.",
            "Good to see you again, sir. I kept the lights on.",
            "Sir. Punctual as ever, in your own particular way."
    };

    /** Waves and greets when the owner returns after time away; idle glances otherwise. */
    private void startCharmMonitor() {
        if (!plugin.getConfig().getBoolean("steward.charm", true)) return;
        Random random = new Random();

        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Map.Entry<UUID, NPC> entry : playerNPCs.entrySet()) {
                    Player owner = plugin.getServer().getPlayer(entry.getKey());
                    NPC npc = entry.getValue();
                    if (owner == null || !owner.isOnline() || !npc.isSpawned()) continue;

                    Location npcLoc = getCurrentLocation(npc);
                    if (owner.getWorld() != npcLoc.getWorld()) continue;
                    double dist = npcLoc.distance(owner.getLocation());

                    if (dist > 40) {
                        ownerAwaySince.putIfAbsent(entry.getKey(), now);
                        continue;
                    }

                    Long awaySince = ownerAwaySince.remove(entry.getKey());
                    if (awaySince != null && now - awaySince > 120_000 && dist <= 10) {
                        Long last = lastGreeting.get(entry.getKey());
                        if (last == null || now - last > 300_000) {
                            lastGreeting.put(entry.getKey(), now);
                            faceLevel(npc, owner.getLocation());
                            if (npc.getEntity() instanceof LivingEntity le) {
                                le.swingMainHand(); // the wave
                            }
                            say(owner, GREETINGS[random.nextInt(GREETINGS.length)]);
                        }
                        continue;
                    }

                    // Idle glance: unoccupied, owner close — look their way
                    // (level gaze — the old eye-height target caused the sky-stare)
                    if (!activeTasks.containsKey(entry.getKey()) && dist <= 10
                            && random.nextInt(4) == 0) {
                        faceLevel(npc, owner.getLocation());
                    }
                }
            }
        }.runTaskTimer(plugin, 100L, 100L); // every 5s
    }

    // ==================== LIFEGUARD (v0.8.2) ====================

    private final Map<UUID, Integer> submergedSeconds = new ConcurrentHashMap<>(); // NPC id -> consecutive seconds

    /**
     * The self-rescue service. Player NPCs sink, and a tall column of water
     * (a lake, a flooded ravine) used to become a trap: he'd stand on the
     * bottom, pathfinding uselessly at the walls. Every second this monitor:
     * - gives any NPC whose HEAD is underwater an upward push (a swim stroke),
     *   so he bobs to the surface and normal navigation can carry him out;
     * - if he's still fully submerged after ~8 straight seconds, declares him
     *   stuck and lifts him to the nearest dry land (or back to his owner).
     */
    private void startLifeguard() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<UUID, NPC> entry : playerNPCs.entrySet()) {
                    NPC npc = entry.getValue();
                    if (!npc.isSpawned() || !(npc.getEntity() instanceof LivingEntity le)) continue;

                    boolean headUnder = le.getEyeLocation().getBlock().getType() == Material.WATER;
                    UUID npcId = npc.getUniqueId();
                    if (!headUnder) {
                        submergedSeconds.remove(npcId);
                        continue;
                    }

                    int seconds = submergedSeconds.merge(npcId, 1, Integer::sum);

                    // Swim stroke: push up, keep his horizontal momentum
                    org.bukkit.util.Vector v = le.getVelocity();
                    le.setVelocity(new org.bukkit.util.Vector(v.getX(), Math.max(v.getY(), 0.30), v.getZ()));

                    // Still under after 8 straight seconds — he's wedged. Rescue.
                    if (seconds >= 8) {
                        submergedSeconds.remove(npcId);
                        Location land = findLandNear(le.getLocation(), 12);
                        Player owner = plugin.getServer().getPlayer(entry.getKey());
                        if (land == null && owner != null && owner.isOnline()
                                && owner.getWorld() == le.getWorld()) {
                            land = findSafeSpawnLocation(owner.getLocation());
                        }
                        if (land != null) {
                            npc.getNavigator().cancelNavigation();
                            npc.teleport(land, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                            if (owner != null && owner.isOnline()) {
                                sayQuiet(owner, "Out of the drink. Do excuse the dripping, sir.");
                            }
                            debug("Lifeguard rescue -> " + formatLoc(land));
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 100L, 20L); // every second
    }

    /** Nearest DRY standable spot, searched in expanding rings. Null if none in range. */
    private Location findLandNear(Location center, int radius) {
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring edge only
                    for (int dy = 3; dy >= -3; dy--) {
                        Location check = center.clone().add(dx, dy, dz);
                        if (isSafeToStand(check)) {
                            return check;
                        }
                    }
                }
            }
        }
        return null;
    }

    // ==================== SUPPLY HANDOFF (v0.6.0) ====================

    private final Map<UUID, Long> supplyCooldowns = new ConcurrentHashMap<>();
    private static final long SUPPLY_COOLDOWN_MS = 60_000;

    /**
     * The valet service: watch each owner's hunger and tool wear; when Jarvis
     * is carrying something that helps, he offers it — dropped at their feet.
     * He only hands over what's actually in his bags. No conjuring.
     */
    private void startSupplyMonitor() {
        if (!plugin.getConfig().getBoolean("steward.supply-handoff", true)) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Map.Entry<UUID, NPC> entry : playerNPCs.entrySet()) {
                    Player owner = plugin.getServer().getPlayer(entry.getKey());
                    NPC npc = entry.getValue();
                    if (owner == null || !owner.isOnline() || !npc.isSpawned()) continue;
                    if (owner.getWorld() != getCurrentLocation(npc).getWorld()) continue;
                    if (owner.getLocation().distance(getCurrentLocation(npc)) > 12) continue;

                    Long cooldown = supplyCooldowns.get(owner.getUniqueId());
                    if (cooldown != null && now - cooldown < SUPPLY_COOLDOWN_MS) continue;

                    // Hungry employer?
                    if (owner.getFoodLevel() <= 8) {
                        if (handOverMatching(npc, owner, stack -> stack.getType().isEdible(), 8,
                                "You look famished, sir. Do eat something.")) {
                            supplyCooldowns.put(owner.getUniqueId(), now);
                            continue;
                        }
                    }

                    // Tool about to break?
                    ItemStack held = owner.getInventory().getItemInMainHand();
                    if (held.getType() != Material.AIR
                            && held.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg
                            && held.getType().getMaxDurability() > 20) {
                        double wear = (double) dmg.getDamage() / held.getType().getMaxDurability();
                        if (wear > 0.9) {
                            Material heldType = held.getType();
                            if (handOverMatching(npc, owner, stack -> stack.getType() == heldType, 1,
                                    "Your " + heldType.name().toLowerCase().replace('_', ' ')
                                    + " is on its last legs, sir. A replacement.")) {
                                supplyCooldowns.put(owner.getUniqueId(), now);
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 200L, 100L); // every 5s
    }

    /** Hand over up to maxAmount of the first matching bag item; true if something was given. */
    private boolean handOverMatching(NPC npc, Player owner,
                                     java.util.function.Predicate<ItemStack> matcher,
                                     int maxAmount, String message) {
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();
        for (int i = 1; i < Math.min(36, contents.length); i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() == Material.AIR) continue;
            if (!matcher.test(item)) continue;

            int give = Math.min(maxAmount, item.getAmount());
            ItemStack handout = item.clone();
            handout.setAmount(give);

            if (item.getAmount() <= give) contents[i] = null;
            else item.setAmount(item.getAmount() - give);
            invTrait.setContents(contents);

            owner.getWorld().dropItemNaturally(owner.getLocation(), handout);
            owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 0.9f);
            say(owner, message);
            return true;
        }
        return false;
    }

    // ==================== CLEANUP ====================

    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                Iterator<Map.Entry<UUID, NPC>> it = playerNPCs.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, NPC> entry = it.next();
                    if (plugin.getServer().getPlayer(entry.getKey()) == null) {
                        NPC npc = entry.getValue();
                        if (npc.isSpawned()) {
                            dropInventoryItems(npc);
                        }
                        npc.destroy();
                        it.remove();
                        BukkitRunnable task = activeTasks.remove(entry.getKey());
                        if (task != null) task.cancel();
                        miningStates.remove(entry.getKey());
                        activeDefenders.remove(entry.getKey());
                    }
                }
            }
        }.runTaskTimer(plugin, 6000L, 6000L); // Every 5 minutes
    }
}
