package com.gadgetman.jarvis.npc.provider;

import com.gadgetman.jarvis.Jarvis;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.tree.BehaviorStatus;
import net.citizensnpcs.api.npc.BlockBreaker;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Inventory;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The Citizens registry: which NPC is whose, and the two mechanisms a
 * different backend would have to solve its own way (spawning a player NPC,
 * and vanilla-speed block breaking).
 *
 * <p>Everything else the butler does goes through {@link CitizensButler},
 * which is core's {@code Butler} over one entry here.
 */
public class CitizensNPCProvider {

    /** Reach used when configuring the block breaker. */
    private static final double REACH_DISTANCE = 4.5;

    /** In-flight block breaks, keyed by NPC, so a new dig supersedes the old one. */
    private final Map<UUID, BukkitRunnable> activeBreakers = new ConcurrentHashMap<>();

    private final Jarvis plugin;
    private final Map<UUID, NPC> playerNPCs = new ConcurrentHashMap<>();

    public CitizensNPCProvider(Jarvis plugin) {
        this.plugin = plugin;
    }

    // ==================== LIFECYCLE ====================

    /** Create and spawn a player NPC for this owner. No-op while one is already spawned. */
    public void spawn(UUID ownerId, Location at, String name) {
        NPC existing = playerNPCs.get(ownerId);
        if (existing != null && existing.isSpawned()) return;

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name);
        npc.spawn(at);
        npc.getOrAddTrait(Inventory.class);
        playerNPCs.put(ownerId, npc);
    }

    /** Destroy this owner's NPC and forget it. Drops nothing. */
    public void despawn(UUID ownerId) {
        NPC npc = playerNPCs.remove(ownerId);
        if (npc == null) return;
        cancelBreaking(npc.getUniqueId());
        npc.destroy();
    }

    public boolean exists(UUID ownerId) {
        return playerNPCs.containsKey(ownerId);
    }

    /** The owner of a Citizens NPC entity, when it is one of ours. */
    public UUID ownerOf(org.bukkit.entity.Entity entity) {
        if (entity == null) return null;
        for (Map.Entry<UUID, NPC> e : playerNPCs.entrySet()) {
            if (entity.equals(e.getValue().getEntity())) return e.getKey();
        }
        return null;
    }

    // ==================== BLOCK BREAKING ====================

    /**
     * Vanilla-speed block breaking, with arm swing and crack overlay.
     *
     * <p>This is the one place a Citizens BlockBreaker is reached for
     * directly, and exactly the kind of mechanism a different backend would
     * have to solve its own way.
     */
    public void breakBlock(UUID ownerId, Block block, ItemStack toolItem,
                           double speedModifier, Consumer<Boolean> onDone) {
        NPC npc = getCitizensNPC(ownerId);
        if (npc == null || !npc.isSpawned()) {
            onDone.accept(false);
            return;
        }
        npc.faceLocation(block.getLocation().add(0.5, 0.5, 0.5));

        BlockBreaker.BlockBreakerConfiguration cfg = new BlockBreaker.BlockBreakerConfiguration();
        cfg.item(toolItem);
        cfg.radius(REACH_DISTANCE + 1.5);
        // Citizens: damage-per-tick is MULTIPLIED by this — higher = faster.
        cfg.blockStrengthModifier((float) speedModifier);

        BlockBreaker breaker = npc.getBlockBreaker(block, cfg);
        Material expected = block.getType();

        if (breaker == null || !breaker.shouldExecute()) {
            // Can't run the breaker (block already gone etc.) — fall back to instant
            boolean ok = block.breakNaturally(toolItem);
            onDone.accept(ok || block.getType() == Material.AIR);
            return;
        }

        UUID npcId = npc.getUniqueId();
        BukkitRunnable breakTask = new BukkitRunnable() {
            int safety = 0;
            @Override
            public void run() {
                // Superseded or stopped externally (/jarvis stop mid-dig)
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
                    block.breakNaturally(toolItem);
                }
                onDone.accept(block.getType() != expected);
            }
        };
        activeBreakers.put(npcId, breakTask);
        breakTask.runTaskTimer(plugin, 1L, 1L);
    }

    /** Cancels an in-flight break for this NPC, if any. */
    public void cancelBreaking(UUID npcId) {
        BukkitRunnable task = activeBreakers.remove(npcId);
        if (task != null) task.cancel();
    }

    // ==================== REGISTRY ====================

    /** The live NPC registry, shared rather than copied: one source of truth. */
    public Map<UUID, NPC> registry() {
        return playerNPCs;
    }

    /** Look up by owner id. */
    public NPC getCitizensNPC(UUID ownerId) {
        return playerNPCs.get(ownerId);
    }
}
