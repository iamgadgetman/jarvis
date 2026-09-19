package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Butlers;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.EntityKind;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.platform.events.ButlerDamagedEvent;
import com.gadgetman.jarvis.core.platform.events.OwnerDamagedEvent;
import com.gadgetman.jarvis.core.platform.events.QuitEvent;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.progression.ProgressionManager;
import com.gadgetman.jarvis.progression.Rank;
import com.gadgetman.jarvis.progression.ServiceRecord;
import com.gadgetman.jarvis.recovery.TaskFailure;
import com.gadgetman.jarvis.recovery.TaskRecoveryHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The butler's keeper: everything Jarvis does that is not one task's business.
 *
 * <p>Summoning and dismissing him, his kit and what rank issues it, the task
 * register and the self-explain hooks, picking up drops, the background
 * services (the wave when you come home, the lifeguard, the valet), and the
 * entry points every command and menu calls. Tasks reach this through
 * {@link ButlerHost}; the adapter reaches it through its own facade.
 *
 * <p>Before the platform split this was JarvisNPC in the Paper plugin, with
 * Citizens calls woven through. Now nothing here names a platform: the body
 * is a {@link Butler}, the world is a {@link World}, and what remains in the
 * adapter is only how those are implemented.
 */
public class ButlerService implements ButlerHost {

    static final int PICKUP_RADIUS = 6;
    static final double LAST_RESORT_TELEPORT_DISTANCE = 40.0;
    static final int LOOT_CAPACITY = 35;

    private final Platform platform;
    private final Butlers butlers;
    private final TaskRecoveryHandler recovery;
    private ProgressionManager progression;

    private final DepositManager depositManager;
    private final RecoveryService recoveryService;
    private final EscortService escortService;

    private final Map<UUID, Defender> activeDefenders = new ConcurrentHashMap<>();
    private final Map<UUID, Task> activeTasks = new ConcurrentHashMap<>();
    private final Map<UUID, OreMiner> miners = new ConcurrentHashMap<>();
    private final List<Task> monitors = new ArrayList<>();

    private final boolean timedBreaking;
    private final double breakSpeedModifier;
    private final boolean debugMode;

    public ButlerService(Platform platform, Butlers butlers, TaskRecoveryHandler recovery) {
        this.platform = platform;
        this.butlers = butlers;
        this.recovery = recovery;
        Config cfg = platform.config();
        this.debugMode = cfg.getBoolean("mining.debug", false);
        this.timedBreaking = cfg.getBoolean("mining.timed-breaking", true);
        this.breakSpeedModifier = Math.max(0.1, cfg.getDouble("mining.break-speed-modifier", 1.0));

        this.depositManager = new DepositManager(platform, this);
        this.recoveryService = new RecoveryService(this);
        this.escortService = new EscortService(this, depositManager);

        platform.events().on(OwnerDamagedEvent.class, e -> {
            Defender defender = activeDefenders.get(e.who().id());
            if (defender != null) defender.recordThreat(e.attacker().orElse(null));
        });
        platform.events().on(ButlerDamagedEvent.class, e -> {
            Defender defender = activeDefenders.get(e.ownerId());
            if (defender != null) defender.recordThreat(e.attacker().orElse(null));
        });
        platform.events().on(QuitEvent.class, e -> handlePlayerDisconnect(e.who()));

        startCleanupTask();
        startSupplyMonitor();
        startCharmMonitor();
        startLifeguard();

        debug("ButlerService initialized on " + butlers.name());
    }

    /** The service ladder, once it exists. */
    public void setProgression(ProgressionManager progression) {
        this.progression = progression;
    }

    public void debug(String message) {
        if (debugMode) {
            platform.log().info("[Jarvis Debug] " + message);
        }
    }

    // ==================== ButlerHost ====================

    @Override public Platform platform() { return platform; }
    @Override public Config config() { return platform.config(); }
    @Override public Butler butler(Owner owner) { return butlers.of(owner); }
    @Override public DepositManager deposits() { return depositManager; }
    public RecoveryService getRecoveryService() { return recoveryService; }
    public EscortService getEscortService() { return escortService; }
    public String backendName() { return butlers.name(); }

    public boolean exists(Owner owner) { return butler(owner).exists(); }
    public boolean isSpawned(Owner owner) { return butler(owner).isSpawned(); }

    // ==================== BUTLER MESSAGING ====================

    /** Entry point for subsystems outside this package that need him to speak. */
    public void speakTo(Owner owner, String text) {
        say(owner, text);
    }

    @Override
    public void say(Owner owner, String text) {
        owner.message(Colors.jarvis(text));
    }

    @Override
    public void sayQuiet(Owner owner, String text) {
        owner.actionBar(Colors.GRAY + "Jarvis: " + text);
    }

    // ==================== NPC LIFECYCLE ====================

    public void summon(Owner owner) {
        Butler b = butler(owner);
        if (b.isSpawned()) {
            say(owner, "I'm already here, sir.");
            return;
        }
        if (b.exists()) {
            // A dead registry entry from a failed spawn — clean it up properly
            b.despawn();
        }
        World world = platform.world(owner.world()).orElse(null);
        if (world == null) return;

        Vec3 spawnAt = findSafeSpawn(world, owner.pos());
        b.spawn(world, spawnAt, "Jarvis");
        b.setProtected(true);
        // A butler who can swim — the adapter floats him to the surface when
        // he's in water instead of letting him sink and wedge.
        b.setSwimming(true);

        // Navigator defaults: A* pathfinding, generous range, NO teleporting
        b.applyNavigationDefaults(null);

        giveStartingEquipment(owner);

        world.sound(spawnAt, Ids.SOUND_BLOCK_BELL_USE, 1.0f, 1.0f);
        say(owner, "At your service.");

        debug("Jarvis spawned for " + owner.name() + " at " + spawnAt);
    }

    public void dismiss(Owner owner) {
        Butler b = butler(owner);
        if (!b.exists()) {
            say(owner, "I haven't been summoned yet, sir.");
            return;
        }

        stopTask(owner);
        if (b.isSpawned()) dropInventoryItems(b);
        miners.remove(owner.id());
        submergedSeconds.remove(owner.id());

        b.despawn();
        say(owner, "Until next time, sir.");

        debug("Jarvis dismissed for " + owner.name());
    }

    public void handlePlayerDisconnect(Owner owner) {
        if (recovery != null) recovery.forget(owner);
        Butler b = butler(owner);
        if (!b.exists()) return;

        stopTask(owner);
        if (b.isSpawned()) dropInventoryItems(b);
        miners.remove(owner.id());
        submergedSeconds.remove(owner.id());
        b.despawn();

        debug("Cleaned up NPC for disconnected player: " + owner.name());
    }

    public void dismissAll() {
        for (Butler b : butlers.all()) {
            if (b.isSpawned()) dropInventoryItems(b);
            b.despawn();
        }
        activeTasks.values().forEach(Task::cancel);
        activeTasks.clear();
        miners.values().forEach(OreMiner::stop);
        miners.clear();
        activeDefenders.clear();
    }

    /** Stop the background services. Tasks are the owner's to stop; this is for shutdown. */
    public void shutdown() {
        monitors.forEach(Task::cancel);
        monitors.clear();
    }

    // ==================== BLOCK BREAKING ====================

    @Override
    public void breakBlockProperly(Owner owner, World world, BlockPos pos, Consumer<Boolean> onDone) {
        Butler b = butler(owner);
        if (!b.isSpawned()) {
            onDone.accept(false);
            return;
        }
        Item tool = getOrRestoreTool(owner);

        if (!timedBreaking) {
            world.breakNaturally(pos, tool);
            onDone.accept(world.block(pos).isAir());
            return;
        }
        b.breakBlock(pos, tool, breakSpeedModifier, onDone);
    }

    // ==================== MINING ====================

    public void mine(Owner owner, String[] args) {
        Set<String> oreFilter = null;
        if (args != null && args.length > 0) {
            String keyword = String.join(" ", args).toLowerCase().trim();
            for (Map.Entry<String, Set<String>> entry : Blocks.ORE_KEYWORDS.entrySet()) {
                if (keyword.contains(entry.getKey())) {
                    oreFilter = entry.getValue();
                    break;
                }
            }
        }
        mine(owner, oreFilter);
    }

    public void mine(Owner owner) {
        mine(owner, (Set<String>) null);
    }

    private void mine(Owner owner, Set<String> oreFilter) {
        if (!exists(owner)) {
            say(owner, "Summon me first, sir — /jarvis summon.");
            return;
        }
        stopTask(owner);

        OreMiner miner = new OreMiner(this, owner, oreFilter);
        miners.put(owner.id(), miner);
        beginTask(owner, "mine");
        miner.start();
    }

    /** The ore seeker's loop ended on its own. */
    void minerDone(Owner owner, OreMiner miner) {
        miners.remove(owner.id(), miner);
    }

    // ==================== ITEM PICKUP ====================

    /**
     * Sweep up nearby drops into the bags (slots 1+).
     * Partial merges never destroy the remainder of a stack — whatever the
     * bags can't absorb stays on the ground.
     */
    @Override
    public void pickupNearbyItems(Owner owner, Vec3 npcPos, boolean includeJunk) {
        Butler b = butler(owner);
        if (!b.isSpawned()) return;
        World world = b.world().orElse(null);
        if (world == null) return;

        for (Entity entity : b.nearbyEntities(PICKUP_RADIUS, PICKUP_RADIUS, PICKUP_RADIUS)) {
            if (entity.kind() != EntityKind.ITEM) continue;
            Item stack = entity.asItem().orElse(Item.EMPTY);
            if (stack.isEmpty()) continue;
            if (!includeJunk && Blocks.JUNK_DROPS.contains(stack.id())) continue;

            Item rest = b.addToInventory(stack);
            if (rest.count() < stack.count()) {
                if (rest.isEmpty()) {
                    entity.remove();                 // fully absorbed
                } else {
                    entity.setItem(rest);            // bags full mid-stack: leave the rest
                }
                world.sound(npcPos, Ids.SOUND_ENTITY_ITEM_PICKUP, 0.3f, 1.2f);
            }
        }
    }

    // ==================== COMBAT / DEFENDER ====================

    /** Legacy alias: /jarvis attack = aggressive bodyguard. */
    public void attack(Owner owner) {
        guard(owner, "aggressive");
    }

    /** Bodyguard mode with stances. */
    public void guard(Owner owner, String stanceArg) {
        if (!exists(owner)) {
            say(owner, "Summon me first, sir — /jarvis summon.");
            return;
        }

        Defender.Stance stance = parseStance(stanceArg, Defender.Stance.DEFENSIVE);

        // Adjust stance in place — but only if already in BODYGUARD mode
        // (a sentry/patrol Defender must be replaced, or he'd hold the old
        // post instead of guarding you)
        Defender existing = activeDefenders.get(owner.id());
        if (existing != null && existing.getMode() == Defender.Mode.BODYGUARD) {
            existing.setStance(stance);
            say(owner, switch (stance) {
                case PASSIVE -> "Standing by, sir. Observing only.";
                case DEFENSIVE -> "Defensive posture, sir. I'll answer any aggression.";
                case AGGRESSIVE -> "Weapons free, sir.";
            });
            return;
        }

        stopTask(owner);

        Defender defender = new Defender(this, owner, stance, Defender.Mode.BODYGUARD);
        activeDefenders.put(owner.id(), defender);
        defender.start();
    }

    /** Night watch — hold the current position as a sentry post. */
    public void watch(Owner owner, String stanceArg) {
        if (!exists(owner)) {
            say(owner, "Summon me first, sir — /jarvis summon.");
            return;
        }

        stopTask(owner);

        Defender.Stance stance = parseStance(stanceArg, Defender.Stance.AGGRESSIVE);
        Defender defender = new Defender(this, owner, stance, Defender.Mode.SENTRY);
        activeDefenders.put(owner.id(), defender);
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

    // ==================== PROGRESSION ====================

    /** Credit work toward the owner's standing; a no-op without the ladder. */
    @Override
    public void credit(Owner owner, ServiceRecord.Discipline d, int amount) {
        if (progression != null && owner != null) progression.record(owner, d, amount);
    }

    @Override
    public boolean hasCapability(Owner owner, Rank.Capability capability) {
        return progression != null && progression.has(owner, capability);
    }

    @Override
    public boolean celebrationsEnabled() {
        return platform.config().getBoolean("steward.celebrations", true);
    }

    /**
     * A tool we issued carries our name on it. Anything else in his hand was
     * put there deliberately — by an operator swapping his kit, say — and must
     * not be silently replaced the next time a task starts.
     */
    static boolean isIssuedKit(Item item) {
        return item != null && item.displayName() != null && item.displayName().contains("Jarvis's");
    }

    private static Rank.ToolKind kindOf(String itemId) {
        String n = Ids.key(itemId);
        if (n.endsWith("_pickaxe")) return Rank.ToolKind.PICKAXE;
        if (n.endsWith("_sword"))   return Rank.ToolKind.SWORD;
        if (n.endsWith("_axe"))     return Rank.ToolKind.AXE;
        if (n.endsWith("_hoe"))     return Rank.ToolKind.HOE;
        if (Ids.BOW.equals(itemId))     return Rank.ToolKind.BOW;
        if (Ids.TRIDENT.equals(itemId)) return Rank.ToolKind.TRIDENT;
        return Rank.ToolKind.ROD;
    }

    /** Is his head underwater? The condition that decides trident vs sword. */
    @Override
    public boolean isSubmerged(Owner owner) {
        Butler b = butler(owner);
        if (!b.isSpawned()) return false;
        World world = b.world().orElse(null);
        return world != null && Ids.WATER.equals(world.block(b.eyePos().block()).id());
    }

    /**
     * Draw the right weapon for where he is standing.
     *
     * <p>Trident in the water, sword on land. Impaling only does anything to
     * things that swim, and a trident is a poor melee weapon everywhere else,
     * so carrying it permanently made the top rank worse at ordinary fighting
     * than the one below it.
     */
    @Override
    public void syncWeaponToSurroundings(Owner owner) {
        if (progression == null) return;
        boolean spear = isSubmerged(owner) && progression.has(owner, Rank.Capability.TRIDENT);
        drawWeapon(owner, spear ? Rank.ToolKind.TRIDENT : Rank.ToolKind.SWORD);
    }

    /**
     * Put a specific weapon in his hand, if that is ours to decide.
     *
     * <p>Two guards matter here and both are load-bearing: a weapon an operator
     * placed by hand is never swapped away, and a weapon he is already holding
     * is never re-issued — this runs inside a combat tick.
     */
    @Override
    public void drawWeapon(Owner owner, Rank.ToolKind kind) {
        if (progression == null) return;
        Butler b = butler(owner);
        if (!b.exists()) return;

        Item held = b.heldItem();
        if (!held.isEmpty() && !isIssuedKit(held)) return;

        String want = progression.rankOf(owner).toolFor(kind);
        if (!held.isEmpty() && held.is(want)) return;

        equipKit(owner, kind);
    }

    /** Re-issue whatever he is holding, at the owner's current standing. */
    public void refreshKit(Owner owner) {
        Butler b = butler(owner);
        if (!b.exists()) return;
        Item held = b.heldItem();
        equipKit(owner, kindOf(held.isEmpty() ? Ids.DIAMOND_PICKAXE : held.id()));
    }

    /**
     * Put the rank-appropriate version of a tool in his hand.
     *
     * <p>If he is already holding that kind of tool and we did not issue it,
     * it stays — an operator's hand-picked netherite axe is not something to
     * overwrite at the start of every chopping run.
     */
    void equipKit(Owner owner, Rank.ToolKind kind) {
        Butler b = butler(owner);
        if (!b.exists()) return;

        Item held = b.heldItem();
        if (!held.isEmpty() && kindOf(held.id()) == kind && !isIssuedKit(held)) {
            return;                                  // player's own choice; leave it
        }

        Item item = progression != null
                ? progression.kitItem(owner, kind)
                : Item.of(kind == Rank.ToolKind.ROD ? Ids.FISHING_ROD : Ids.DIAMOND_PICKAXE);
        b.setHeldItem(item);
    }

    /** Callers name a diamond tool; what he is actually handed depends on the owner's standing. */
    @Override
    public void equipTool(Owner owner, String itemId) {
        equipKit(owner, kindOf(itemId));
    }

    @Override
    public Item toolInHand(Owner owner) {
        return butler(owner).heldItem();
    }

    /** Guard-mode loadout: sword in hand. The pickaxe returns when mining does. */
    @Override
    public void giveGuardEquipment(Owner owner) {
        equipKit(owner, Rank.ToolKind.SWORD);
        syncWeaponToSurroundings(owner);
    }

    /**
     * Give Jarvis his pickaxe — IN HAND. For player-type NPCs, inventory
     * slot 0 is the held hotbar slot, so the pickaxe must live there.
     */
    @Override
    public void giveStartingEquipment(Owner owner) {
        Butler b = butler(owner);
        if (!b.exists()) return;
        Item pickaxe = progression != null
                ? progression.kitItem(owner, Rank.ToolKind.PICKAXE)
                : Item.of(Ids.DIAMOND_PICKAXE).enchant(Ids.ENCHANT_FORTUNE, 3);
        b.setHeldItem(pickaxe);
    }

    /** The pickaxe lives in hand (inventory slot 0). Restore it if anything displaced it. */
    private Item getOrRestoreTool(Owner owner) {
        Butler b = butler(owner);
        Item tool = b.heldItem();
        if (tool.isEmpty() || !Ids.key(tool.id()).endsWith("_pickaxe")) {
            debug("Pickaxe missing from hand — restoring");
            giveStartingEquipment(owner);
            tool = b.heldItem();
        }
        return tool;
    }

    /** The tool in hand, named in plain English for a self-explain prompt. */
    @Override
    public String describeHeldTool(Owner owner) {
        Butler b = butler(owner);
        if (!b.exists()) return "no NPC";
        Item tool = b.heldItem();
        return tool.isEmpty() ? "empty handed" : Blocks.pretty(tool.id());
    }

    // ==================== ACTIVITIES ====================

    private boolean requireSummoned(Owner owner) {
        if (!exists(owner)) {
            say(owner, "Summon me first, sir — /jarvis summon.");
            return false;
        }
        return true;
    }

    /** Farming: one sweep, or a standing tend shift. */
    public void farm(Owner owner, String cropKeyword, boolean tend) {
        if (!requireSummoned(owner)) return;
        stopTask(owner);
        String crop = Farmer.cropFromKeyword(cropKeyword);
        beginTask(owner, tend ? "tend" : "farm");
        new Farmer(this, owner, depositManager, crop, tend).start();
    }

    /** Lumberjack: fell N trees, replant saplings. */
    public void chop(Owner owner, int trees) {
        if (!requireSummoned(owner)) return;
        stopTask(owner);
        beginTask(owner, "chop");
        new Lumberjack(this, owner, depositManager, trees).start();
    }

    /** Fishing at the nearest water's edge. */
    public void fish(Owner owner) {
        if (!requireSummoned(owner)) return;
        stopTask(owner);
        beginTask(owner, "fish");
        new Fisherman(this, owner, depositManager).start();
    }

    /** The dance. */
    public void dance(Owner owner) {
        if (!requireSummoned(owner)) return;
        Entertainer.dance(this, owner);
    }

    /**
     * Lamplighter: spawn-proof the area around the player with a grid of
     * lights. radius/spacing <= 0 and type == null mean "use config".
     */
    public void light(Owner owner, int radius, String type, int spacing) {
        if (!requireSummoned(owner)) return;
        World world = platform.world(owner.world()).orElse(null);
        if (world == null) return;
        stopTask(owner);
        new Lamplighter(this, owner, world, radius, type, spacing).start();
    }

    /** Patrol: walk a persisted waypoint circuit as a sentry. */
    public void patrol(Owner owner, String sub) {
        if (!requireSummoned(owner)) return;
        World world = platform.world(owner.world()).orElse(null);
        switch (sub == null ? "start" : sub.toLowerCase()) {
            case "add" -> {
                if (world == null) return;
                int n = depositManager.addPatrolPoint(owner,
                        new com.gadgetman.jarvis.core.platform.Site(world, owner.pos()));
                say(owner, "Waypoint " + n + " noted, sir.");
            }
            case "clear" -> {
                depositManager.clearPatrol(owner);
                say(owner, "Patrol route cleared, sir.");
            }
            default -> {
                var route = depositManager.getPatrol(owner);
                if (route.size() < 2) {
                    say(owner, "I need at least two waypoints, sir — stand at each and say '/jarvis patrol add'.");
                    return;
                }
                stopTask(owner);
                Defender defender = new Defender(this, owner,
                        Defender.Stance.AGGRESSIVE, Defender.Mode.PATROL);
                defender.setPatrolRoute(route);
                activeDefenders.put(owner.id(), defender);
                defender.start();
            }
        }
    }

    /** The owner as an entity in their world, for following and chasing. */
    private Optional<Entity> ownerEntity(Owner owner) {
        return platform.world(owner.world()).flatMap(w -> w.entity(owner.id()));
    }

    /** Move him to a safe spot beside the owner, wherever the owner is. */
    private void teleportToOwner(Butler b, Owner owner) {
        World world = platform.world(owner.world()).orElse(null);
        if (world == null) return;
        b.teleport(world, findSafeSpawn(world, owner.pos()), owner.look());
    }

    /**
     * Recall him to your side.
     *
     * <p>A recall must always succeed, so this watches him the way
     * {@code follow} does: re-target a moving player, re-path once on a stall,
     * and teleport as a last resort rather than give up.
     */
    public void returnToPlayer(Owner owner) {
        Butler b = butler(owner);
        if (!b.exists()) return;

        stopTask(owner);

        // Another world, or too far to walk: hop straight there.
        if (!b.world().map(w -> w.id().equals(owner.world())).orElse(false)
                || b.pos().distance(owner.pos()) > LAST_RESORT_TELEPORT_DISTANCE) {
            teleportToOwner(b, owner);
            say(owner, "Right behind you, sir.");
            return;
        }

        b.applyNavigationDefaults(null);
        ownerEntity(owner).ifPresent(e -> b.navigateTo(e, false));
        say(owner, "On my way, sir.");

        Task task = platform.scheduler().every(20L, 20L, new Consumer<Task>() {
            Vec3 lastPos = null;
            int stallTicks = 0;
            int elapsed = 0;

            @Override
            public void accept(Task self) {
                if (!b.isSpawned() || !owner.isOnline()) {
                    self.cancel();
                    taskDone(owner, self);
                    return;
                }

                Vec3 here = b.pos();
                Vec3 there = owner.pos();
                boolean sameWorld = b.world().map(w -> w.id().equals(owner.world())).orElse(false);
                elapsed++;

                // Arrived.
                if (sameWorld && here.distance(there) <= 3.0) {
                    b.cancelNavigation();
                    sayQuiet(owner, "At your side, sir.");
                    self.cancel();
                    taskDone(owner, self);
                    return;
                }

                // Followed you through a portal, or you ran off.
                if (!sameWorld || here.distance(there) > LAST_RESORT_TELEPORT_DISTANCE) {
                    b.cancelNavigation();
                    teleportToOwner(b, owner);
                    sayQuiet(owner, "Caught up, sir.");
                    self.cancel();
                    taskDone(owner, self);
                    return;
                }

                // Not moving = stuck. Re-path once, then stop being precious
                // about it and teleport: you asked him to come here.
                if (lastPos != null && here.distanceSq(lastPos) < 0.09) {
                    stallTicks++;
                    if (stallTicks == 2) {
                        b.cancelNavigation();
                        ownerEntity(owner).ifPresent(e -> b.navigateTo(e, false));
                    } else if (stallTicks >= 4) {
                        b.cancelNavigation();
                        teleportToOwner(b, owner);
                        say(owner, "The path was blocked, sir — I let myself through.");
                        self.cancel();
                        taskDone(owner, self);
                        return;
                    }
                } else {
                    stallTicks = 0;
                }
                lastPos = here;

                // Keep chasing a moving player.
                if (!b.isNavigating()) {
                    ownerEntity(owner).ifPresent(e -> b.navigateTo(e, false));
                }

                // Hard ceiling, so a recall can never hang about indefinitely.
                if (elapsed >= 30) {
                    b.cancelNavigation();
                    teleportToOwner(b, owner);
                    say(owner, "That was taking too long, sir. Here I am.");
                    self.cancel();
                    taskDone(owner, self);
                }
            }
        });
        beginTask(owner, "return");
        registerTask(owner, task);
    }

    public void openInventory(Owner owner) {
        Butler b = butler(owner);
        if (!b.exists()) {
            say(owner, "I haven't been summoned yet, sir.");
            return;
        }
        b.openInventory(owner);
    }

    public void clearInventory(Owner owner) {
        Butler b = butler(owner);
        if (!b.exists()) {
            say(owner, "I haven't been summoned yet, sir.");
            return;
        }
        World world = b.world().orElse(null);
        List<Item> contents = new ArrayList<>(b.inventory());
        int dropped = 0;

        // Slot 0 is his hand (the pickaxe) — leave it alone; slots 1+ are all loot
        for (int i = 1; i < contents.size(); i++) {
            Item item = contents.get(i);
            if (!item.isEmpty()) {
                if (world != null) world.dropItem(b.pos(), item);
                contents.set(i, Item.EMPTY);
                dropped += item.count();
            }
        }

        b.setInventory(contents);
        giveStartingEquipment(owner);
        say(owner, "Deposited " + dropped + " items at my feet, sir.");
    }

    /** The deterministic branch mine — staircase, gallery, branches, torches. */
    public void startBranchMining(Owner owner) {
        if (!requireSummoned(owner)) return;
        stopTask(owner);
        beginTask(owner, "branch_mine");
        new BranchMiner(this, owner, depositManager).start();
    }

    public void tunnel(Owner owner, int length) {
        tunnel(owner, length, null);
    }

    /**
     * Drive a straight 3x3 passage where he is facing.
     *
     * <p>Gated on the Peerless rank. A 3x3 is nine times the digging of the
     * corridor he starts out able to cut, so it is the first thing on the
     * ladder that is a new job rather than a faster one.
     */
    public void tunnel(Owner owner, int length, String direction) {
        if (!requireSummoned(owner)) return;
        if (progression != null && !progression.has(owner, Rank.Capability.WIDE_BORE)) {
            Rank need = Rank.PEERLESS;
            say(owner, "I'm not yet equal to a passage that size, sir. "
                    + "Ask me again at " + need.title() + " — "
                    + Math.max(0, need.serviceRequired() - progression.recordOf(owner).service())
                    + " more service.");
            return;
        }

        stopTask(owner);
        Config cfg = platform.config();
        int len = length > 0 ? length : cfg.getInt("mining.tunnel.default-length", 32);
        len = Math.max(2, Math.min(len, cfg.getInt("mining.tunnel.max-length", 128)));

        int[] heading = null;
        if (direction != null && !direction.isBlank()) {
            Compass.Heading parsed = Compass.parse(direction);
            if (parsed == null) {
                say(owner, "I don't know which way \"" + direction + "\" is, sir. "
                        + "North, south, east or west.");
                return;
            }
            if (parsed.rounded()) {
                say(owner, "I only cut square passages on the compass points, sir — "
                        + parsed.name() + " it is.");
            }
            heading = new int[]{ parsed.dx(), parsed.dz() };
        }

        new BranchMiner(this, owner, depositManager, BranchMiner.Layout.TUNNEL, len, heading).start();
    }

    /** Sink a vertical shaft. */
    public void digDown(Owner owner, int depth) {
        if (!requireSummoned(owner)) return;
        stopTask(owner);
        beginTask(owner, "dig_down");
        new ShaftDigger(this, owner, depth).start();
    }

    /** Follow mode — trail the player, carry the loot. */
    public void follow(Owner owner) {
        Butler b = butler(owner);
        if (!requireSummoned(owner)) return;

        stopTask(owner);
        b.applyNavigationDefaults(null);
        say(owner, "Right behind you, sir.");

        Task task = platform.scheduler().every(0L, 20L, new Consumer<Task>() {
            // Stall watchdog: he used to wedge on fences/corners and simply
            // stand there until the 40-block teleport kicked in.
            Vec3 lastPos = null;
            int stallTicks = 0;

            @Override
            public void accept(Task self) {
                if (!b.isSpawned() || !owner.isOnline()) {
                    self.cancel();
                    taskDone(owner, self);
                    return;
                }

                Vec3 npcLoc = b.pos();
                Vec3 playerLoc = owner.pos();
                boolean sameWorld = b.world().map(w -> w.id().equals(owner.world())).orElse(false);

                if (!sameWorld || npcLoc.distance(playerLoc) > LAST_RESORT_TELEPORT_DISTANCE) {
                    // Fell far behind (elytra, portals) — catch up
                    b.cancelNavigation();
                    teleportToOwner(b, owner);
                    lastPos = null;
                    stallTicks = 0;
                    return;
                }

                double dist = npcLoc.distance(playerLoc);

                // Watchdog: behind AND not actually moving for ~4s = stuck.
                // Re-path first; if that fails too, a short catch-up hop.
                if (dist > 6.0 && lastPos != null && npcLoc.distanceSq(lastPos) < 0.09) {
                    stallTicks++;
                    if (stallTicks == 2) {
                        // First remedy: force a fresh path
                        b.cancelNavigation();
                        ownerEntity(owner).ifPresent(e -> b.navigateTo(e, false));
                    } else if (stallTicks >= 4) {
                        b.cancelNavigation();
                        teleportToOwner(b, owner);
                        sayQuiet(owner, "Caught up, sir.");
                        stallTicks = 0;
                    }
                } else {
                    stallTicks = 0;
                }
                lastPos = npcLoc;

                if (dist > 3.0 && !b.isNavigating()) {
                    // Follow the entity — the pathfinder tracks a moving target on its own
                    ownerEntity(owner).ifPresent(e -> b.navigateTo(e, false));
                } else if (dist <= 2.0 && b.isNavigating()) {
                    b.cancelNavigation();
                }

                pickupNearbyItems(owner, npcLoc);
            }
        });
        beginTask(owner, "follow");
        registerTask(owner, task);
    }

    public void stop(Owner owner) {
        stopTask(owner);
        say(owner, "Standing down, sir.");
    }

    // ==================== TASK REGISTER ====================

    /** Register a task as THE active task for this owner (cancels via stopTask). */
    @Override
    public void registerTask(Owner owner, Task task) {
        activeTasks.put(owner.id(), task);
    }

    /**
     * Declare a job by name and hand it a fresh self-explain budget.
     *
     * <p>Called once where the player asks for the work, deliberately not from
     * the tick loop — a recovery move restarts that loop, and resetting the
     * budget there would let a task retry forever.
     */
    @Override
    public void beginTask(Owner owner, String taskType) {
        if (recovery != null) recovery.taskStarted(owner, taskType);
    }

    /**
     * Hand a dead task to self-explain. Falls through to the task's own message
     * whenever diagnosis is off, capped out, or unusable, so a caller can pass
     * its existing failure text and lose nothing.
     */
    @Override
    public void reportFailure(TaskFailure failure) {
        if (recovery == null) {
            say(failure.getOwner(), failure.getDefaultMessage());
            return;
        }
        recovery.handle(failure);
    }

    /** Tasks that finish on their own call this so the register stays accurate. */
    @Override
    public void taskDone(Owner owner, Task task) {
        activeTasks.remove(owner.id(), task);
    }

    @Override
    public void stopTask(Owner owner) {
        // Tells self-explain that anything it is still diagnosing has been
        // superseded, so a recovery move cannot fire into the next job.
        if (recovery != null) recovery.taskSuperseded(owner);
        activeDefenders.remove(owner.id());
        Task task = activeTasks.remove(owner.id());
        if (task != null) task.cancel();
        OreMiner miner = miners.remove(owner.id());
        if (miner != null) miner.stop();

        Butler b = butler(owner);
        if (b.exists()) {
            // Halt any block-break in progress; the breaker resets the crack
            // animation once its entry is gone
            b.cancelBreaking();
            b.cancelNavigation();
        }
    }

    // ==================== UTILITY ====================

    @Override
    public Vec3 findSafeNear(World world, Vec3 near) {
        return findSafeSpawn(world, near);
    }

    private Vec3 findSafeSpawn(World world, Vec3 center) {
        for (int dx = 0; dx <= 3; dx++) {
            for (int dz = 0; dz <= 3; dz++) {
                for (int dir = 0; dir < 4; dir++) {
                    int x = (dir == 0 || dir == 2) ? dx : -dx;
                    int z = (dir == 0 || dir == 1) ? dz : -dz;

                    Vec3 check = center.add(x, 0, z);
                    if (isSafeToStand(world, check.block())) {
                        return check;
                    }
                }
            }
        }
        return center;
    }

    private boolean isSafeToStand(World world, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos ground = feet.below();

        if (!world.isSolid(ground)) return false;
        if (world.isSolid(feet)) return false;
        if (world.isSolid(head)) return false;

        if (Blocks.HAZARDOUS_FOOTING.contains(world.block(ground).id())) return false;

        String feetType = world.block(feet).id();
        String headType = world.block(head).id();
        return !Blocks.isFluid(feetType) && !Blocks.isFluid(headType);
    }

    /** Nearest DRY standable spot, searched in expanding rings. Null if none in range. */
    private Vec3 findLandNear(World world, Vec3 center, int radius) {
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring edge only
                    for (int dy = 3; dy >= -3; dy--) {
                        Vec3 check = center.add(dx, dy, dz);
                        if (isSafeToStand(world, check.block())) {
                            return check;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Look toward a point with a level gaze; the eye-height target caused the sky-stare. */
    private void faceLevel(Butler b, Vec3 target) {
        b.lookAt(new Vec3(target.x(), b.pos().y() + 1.62, target.z()));
    }

    @Override public int lootCapacity() { return LOOT_CAPACITY; }

    /** Loot slots used out of the 35 storage slots (slot 0 is the pickaxe). */
    @Override
    public int lootSlotsUsed(Owner owner) {
        List<Item> contents = butler(owner).inventory();
        int used = 0;
        for (int i = 1; i < Math.min(36, contents.size()); i++) {
            if (!contents.get(i).isEmpty()) used++;
        }
        return used;
    }

    @Override
    public double distanceToOwner(Owner owner) {
        if (owner == null || !owner.isOnline()) return Double.MAX_VALUE;
        Butler b = butler(owner);
        if (!b.isSpawned()) return Double.MAX_VALUE;
        if (!b.world().map(w -> w.id().equals(owner.world())).orElse(false)) return Double.MAX_VALUE;
        return b.pos().distance(owner.pos());
    }

    /** Drop everything but his hand at his feet. */
    private void dropInventoryItems(Butler b) {
        World world = b.world().orElse(null);
        if (world == null) return;
        List<Item> contents = b.inventory();
        // Slot 0 is his pickaxe — everything else gets handed over
        for (int i = 1; i < contents.size(); i++) {
            Item item = contents.get(i);
            if (!item.isEmpty()) world.dropItem(b.pos(), item);
        }
    }

    // ==================== STATUS & INFO ====================

    public int getActiveNpcCount() {
        return butlers.all().size();
    }

    public int getActiveTaskCount() {
        return activeTasks.size();
    }

    /**
     * A short human phrase for what Jarvis is doing for this player right now,
     * or {@code null} when he is idle. Derived from live state rather than a
     * label each task has to remember to set.
     */
    public String describeCurrentTask(UUID playerId) {
        Defender defender = activeDefenders.get(playerId);
        if (defender != null) {
            return switch (defender.getMode()) {
                case BODYGUARD -> "Guarding you";
                case SENTRY    -> "Standing watch";
                case PATROL    -> "Walking the patrol";
            };
        }
        OreMiner mining = miners.get(playerId);
        if (mining != null) {
            return mining.targetOreType() != null
                    ? "Mining " + Blocks.formatOre(mining.targetOreType())
                    : "Mining";
        }
        if (activeTasks.containsKey(playerId)) return "Working";
        return null;
    }

    @Override
    public String describeCurrentTask(Owner owner) {
        return describeCurrentTask(owner.id());
    }

    // ==================== CHARM (v0.6.0) ====================

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
        if (!platform.config().getBoolean("steward.charm", true)) return;
        Random random = new Random();

        monitors.add(platform.scheduler().every(100L, 100L, t -> {   // every 5s
            long now = System.currentTimeMillis();
            for (Butler b : butlers.spawned()) {
                Owner owner = b.owner();
                if (!owner.isOnline()) continue;
                UUID id = owner.id();

                double dist = distanceToOwner(owner);
                if (dist == Double.MAX_VALUE) continue;

                if (dist > 40) {
                    ownerAwaySince.putIfAbsent(id, now);
                    continue;
                }

                Long awaySince = ownerAwaySince.remove(id);
                if (awaySince != null && now - awaySince > 120_000 && dist <= 10) {
                    Long last = lastGreeting.get(id);
                    if (last == null || now - last > 300_000) {
                        lastGreeting.put(id, now);
                        faceLevel(b, owner.pos());
                        b.swing(); // the wave
                        say(owner, GREETINGS[random.nextInt(GREETINGS.length)]);
                    }
                    continue;
                }

                // Idle glance: unoccupied, owner close — look their way
                if (!activeTasks.containsKey(id) && dist <= 10 && random.nextInt(4) == 0) {
                    faceLevel(b, owner.pos());
                }
            }
        }));
    }

    // ==================== LIFEGUARD (v0.8.2) ====================

    private final Map<UUID, Integer> submergedSeconds = new ConcurrentHashMap<>(); // owner id -> consecutive seconds

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
        monitors.add(platform.scheduler().every(100L, 20L, t -> {   // every second
            for (Butler b : butlers.spawned()) {
                World world = b.world().orElse(null);
                if (world == null) continue;
                UUID id = b.owner().id();

                boolean headUnder = Ids.WATER.equals(world.block(b.eyePos().block()).id());
                if (!headUnder) {
                    submergedSeconds.remove(id);
                    continue;
                }

                int seconds = submergedSeconds.merge(id, 1, Integer::sum);

                // Swim stroke: push up, keep his horizontal momentum
                Vec3 v = b.entity().map(Entity::velocity).orElse(Vec3.ZERO);
                b.setVelocity(new Vec3(v.x(), Math.max(v.y(), 0.30), v.z()));

                // Still under after 8 straight seconds — he's wedged. Rescue.
                if (seconds >= 8) {
                    submergedSeconds.remove(id);
                    Vec3 land = findLandNear(world, b.pos(), 12);
                    Owner owner = b.owner();
                    if (land == null && owner.isOnline() && owner.world().equals(world.id())) {
                        land = findSafeSpawn(world, owner.pos());
                    }
                    if (land != null) {
                        b.cancelNavigation();
                        b.teleport(land);
                        if (owner.isOnline()) {
                            sayQuiet(owner, "Out of the drink. Do excuse the dripping, sir.");
                        }
                        debug("Lifeguard rescue -> " + land);
                    }
                }
            }
        }));
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
        if (!platform.config().getBoolean("steward.supply-handoff", true)) return;

        monitors.add(platform.scheduler().every(200L, 100L, t -> {   // every 5s
            long now = System.currentTimeMillis();
            for (Butler b : butlers.spawned()) {
                Owner owner = b.owner();
                if (!owner.isOnline()) continue;
                World world = b.world().orElse(null);
                if (world == null || !owner.world().equals(world.id())) continue;
                if (owner.pos().distance(b.pos()) > 12) continue;

                Long cooldown = supplyCooldowns.get(owner.id());
                if (cooldown != null && now - cooldown < SUPPLY_COOLDOWN_MS) continue;

                // Hungry employer?
                if (owner.foodLevel() <= 8) {
                    if (handOverMatching(b, world, owner, item -> platform.items().isEdible(item.id()), 8,
                            "You look famished, sir. Do eat something.")) {
                        supplyCooldowns.put(owner.id(), now);
                        continue;
                    }
                }

                // Tool about to break?
                Item held = owner.heldItem();
                if (!held.isEmpty() && owner.heldItemWear() > 0.9) {
                    String heldType = held.id();
                    if (handOverMatching(b, world, owner, item -> item.is(heldType), 1,
                            "Your " + Blocks.pretty(heldType) + " is on its last legs, sir. A replacement.")) {
                        supplyCooldowns.put(owner.id(), now);
                    }
                }
            }
        }));
    }

    /** Hand over up to maxAmount of the first matching bag item; true if something was given. */
    private boolean handOverMatching(Butler b, World world, Owner owner, Predicate<Item> matcher,
                                     int maxAmount, String message) {
        List<Item> contents = new ArrayList<>(b.inventory());
        for (int i = 1; i < Math.min(36, contents.size()); i++) {
            Item item = contents.get(i);
            if (item.isEmpty() || !matcher.test(item)) continue;

            int give = Math.min(maxAmount, item.count());
            Item handout = item.withCount(give);

            contents.set(i, item.count() <= give ? Item.EMPTY : item.withCount(item.count() - give));
            b.setInventory(contents);

            world.dropItem(owner.pos(), handout);
            world.sound(owner.pos(), Ids.SOUND_ENTITY_ITEM_PICKUP, 0.5f, 0.9f);
            say(owner, message);
            return true;
        }
        return false;
    }

    // ==================== CLEANUP ====================

    private void startCleanupTask() {
        monitors.add(platform.scheduler().every(6000L, 6000L, t -> {   // every 5 minutes
            for (Butler b : butlers.all()) {
                Owner owner = b.owner();
                if (owner.isOnline()) continue;
                if (b.isSpawned()) dropInventoryItems(b);
                b.despawn();
                Task task = activeTasks.remove(owner.id());
                if (task != null) task.cancel();
                OreMiner miner = miners.remove(owner.id());
                if (miner != null) miner.stop();
                activeDefenders.remove(owner.id());
            }
        }));
    }
}
