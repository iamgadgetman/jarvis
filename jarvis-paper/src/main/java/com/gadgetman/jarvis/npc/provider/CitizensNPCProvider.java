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
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * Metadata key on every NPC Jarvis makes, holding its owner's UUID (FB-16).
     * Citizens saves NPCs to saves.yml and loads them again after a restart or
     * crash, but Jarvis only knows the ones it made in this run; a tagged NPC
     * that isn't ours at startup is a leftover and is removed.
     */
    static final String OWNER_KEY = "jarvis-owner";

    /** The name core gives every butler ({@code ButlerService} spawns him as "Jarvis"). */
    public static final String BUTLER_NAME = "Jarvis";

    /** Reach used when configuring the block breaker. */
    private static final double REACH_DISTANCE = 4.5;

    /** In-flight block breaks, keyed by NPC, so a new dig supersedes the old one. */
    private final Map<UUID, BukkitRunnable> activeBreakers = new ConcurrentHashMap<>();

    private final Jarvis plugin;
    private final Map<UUID, NPC> playerNPCs = new ConcurrentHashMap<>();
    /** His inventory as the owner sees it; registered with Bukkit by the plugin. */
    private final ButlerInventoryView inventoryView = new ButlerInventoryView();

    public CitizensNPCProvider(Jarvis plugin) {
        this.plugin = plugin;
    }

    public ButlerInventoryView inventoryView() {
        return inventoryView;
    }

    // ==================== LIFECYCLE ====================

    /** Create and spawn a player NPC for this owner. No-op while one is already spawned. */
    public void spawn(UUID ownerId, Location at, String name) {
        NPC existing = playerNPCs.get(ownerId);
        if (existing != null && existing.isSpawned()) return;

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name);
        npc.data().setPersistent(OWNER_KEY, ownerId.toString());
        playerNPCs.put(ownerId, npc);   // before spawn, so onNpcSpawned knows it is ours
        npc.spawn(at);
        npc.getOrAddTrait(Inventory.class);
    }

    /**
     * Every time one of our NPCs comes into the world (the first spawn, and
     * each respawn Citizens does on a world change or chunk reload), mark
     * the entity as not needing to sleep. A Citizens player NPC sits in the
     * level's player list, which is what vanilla counts for skipping the
     * night; a sleep-ignored player is counted as already asleep, so the
     * owner alone in bed is enough. Run a tick later, once Citizens has the
     * entity in place.
     */
    public void onNpcSpawned(NPC npc) {
        if (!playerNPCs.containsValue(npc)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (npc.isSpawned() && npc.getEntity() instanceof Player p) {
                p.setSleepingIgnored(true);
            }
        });
    }

    /** Destroy this owner's NPC and forget it. Drops nothing. */
    public void despawn(UUID ownerId) {
        NPC npc = playerNPCs.remove(ownerId);
        if (npc == null) return;
        cancelBreaking(npc.getUniqueId());
        npc.destroy();
    }

    /**
     * Remove Jarvis NPCs left over from an earlier run (FB-16): Citizens loaded
     * them from saves.yml, but no owner is attached to them now, so they would
     * stand in the world for good. Only NPCs carrying {@link #OWNER_KEY} are
     * touched; an untagged NPC that shares the butler's name (one made before
     * FB-16, or by an admin) is only reported, with its id, in the log.
     *
     * @param butlerName the configured NPC name, for the report
     * @return how many were removed
     */
    public int sweepOrphans(String butlerName) {
        List<NPC> leftovers = new ArrayList<>();
        List<Integer> sameName = new ArrayList<>();
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (playerNPCs.containsValue(npc)) continue;
            if (npc.data().has(OWNER_KEY)) leftovers.add(npc);
            else if (butlerName != null && org.bukkit.ChatColor.stripColor(npc.getName()).equalsIgnoreCase(
                    org.bukkit.ChatColor.stripColor(butlerName))) sameName.add(npc.getId());
        }
        for (NPC npc : leftovers) npc.destroy();
        if (!leftovers.isEmpty()) {
            plugin.getLogger().info("Removed " + leftovers.size() + " leftover Jarvis NPC(s) from an earlier run.");
        }
        if (!sameName.isEmpty()) {
            plugin.getLogger().warning("Citizens NPC(s) " + sameName + " are named \"" + butlerName
                    + "\" but Jarvis doesn't own them. If they are old butlers, remove them with /npc remove <id>.");
        }
        return leftovers.size();
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
