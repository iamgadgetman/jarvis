package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lumberjack (v0.7.0) - "Chop some trees, Jarvis."
 *
 * Finds trees (log columns rooted on soil with leaves above), walks to the
 * base, chops the trunk base with the timed BlockBreaker — then the rest of
 * the tree comes down in a top-to-bottom cascade (timber!), drops collected,
 * and a sapling of the right species goes back in the ground. Keeps going
 * until the requested number of trees (default 5) or none remain in range.
 */
class Lumberjack {

    private static final Set<Material> LOGS = Set.of(
            Material.OAK_LOG, Material.BIRCH_LOG, Material.SPRUCE_LOG, Material.JUNGLE_LOG,
            Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG);

    private static final Map<Material, Material> SAPLINGS = Map.of(
            Material.OAK_LOG, Material.OAK_SAPLING,
            Material.BIRCH_LOG, Material.BIRCH_SAPLING,
            Material.SPRUCE_LOG, Material.SPRUCE_SAPLING,
            Material.JUNGLE_LOG, Material.JUNGLE_SAPLING,
            Material.ACACIA_LOG, Material.ACACIA_SAPLING,
            Material.DARK_OAK_LOG, Material.DARK_OAK_SAPLING,
            Material.MANGROVE_LOG, Material.MANGROVE_PROPAGULE,
            Material.CHERRY_LOG, Material.CHERRY_SAPLING);

    private final Jarvis plugin;
    private final JarvisNPC host;
    private final Player player;
    private final NPC npc;
    private final DepositManager deposits;
    private final int treeQuota;
    private final boolean replant;

    private int treesFelled = 0;
    private int logsCollected = 0;
    private boolean busy = false;
    private Block currentBase = null;
    private int stalled = 0;
    private Location lastPos = null;
    private BukkitRunnable mainTask = null;   // v0.8.0: lets callbacks notice /jarvis stop

    private static final int SEARCH_RADIUS = 24;
    private static final int MAX_TREE_LOGS = 80;
    private static final int STALL_HOP_TICKS = 8;

    Lumberjack(JarvisNPC host, Player player, NPC npc, DepositManager deposits, int treeQuota) {
        this.host = host;
        this.plugin = host.getPlugin();
        this.player = player;
        this.npc = npc;
        this.deposits = deposits;
        this.treeQuota = Math.max(1, Math.min(treeQuota, 32));
        this.replant = plugin.getConfig().getBoolean("farming.replant-saplings", true);
    }

    void start() {
        host.applyNavigatorDefaults(npc, null);
        host.equipTool(npc, Material.DIAMOND_AXE);
        host.say(player, "Very good, sir. " + treeQuota + " trees, coming down — replanting as I go.");

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!npc.isSpawned() || !player.isOnline()) {
                    cancel();
                    host.taskDone(player, this);
                    return;
                }
                Location loc = host.getCurrentLocation(npc);
                host.pickupNearbyItems(npc, loc);
                tick(loc, this);
            }
        };
        mainTask = task;
        task.runTaskTimer(plugin, 10L, 10L);
        host.registerTask(player, task);
    }

    private void tick(Location loc, BukkitRunnable self) {
        if (busy) return;

        if (treesFelled >= treeQuota) {
            finish(self);
            return;
        }
        if (host.lootSlotsUsed(npc) >= JarvisNPC.LOOT_CAPACITY - 2 && deposits.hasChest(player)) {
            host.say(player, "Bags full of timber, sir — one delivery and I'll resume.");
            self.cancel();
            deposits.startDepositRun(player, deposits.getChest(player), () -> {
                // v0.8.0: if the chest couldn't take it, don't loop forever
                if (host.lootSlotsUsed(npc) >= JarvisNPC.LOOT_CAPACITY - 2) {
                    host.say(player, "The chest is full and so are my bags, sir. "
                            + "The timber work is paused for now.");
                    return;
                }
                host.applyNavigatorDefaults(npc, null);
                host.equipTool(npc, Material.DIAMOND_AXE);
                start();
            });
            return;
        }

        // Find (or continue toward) the next tree base
        if (currentBase == null || !LOGS.contains(currentBase.getType())) {
            currentBase = findNearestTreeBase(loc);
            if (currentBase == null) {
                finish(self);
                return;
            }
        }

        double dist = loc.distance(currentBase.getLocation().add(0.5, 0.5, 0.5));
        if (dist > 2.8) {
            if (!npc.getNavigator().isNavigating()) {
                npc.getNavigator().setTarget(currentBase.getLocation().add(0.5, 1, 0.5));
            }
            if (lastPos != null && loc.distance(lastPos) < 0.15) stalled++;
            else stalled = 0;
            lastPos = loc.clone();
            if (stalled > STALL_HOP_TICKS) {
                npc.getNavigator().cancelNavigation();
                npc.teleport(host.findSafeNear(currentBase.getLocation().add(0.5, 1, 0.5)),
                        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                stalled = 0;
            }
            return;
        }

        // At the tree: chop the base with real timing, then TIMBER the rest
        npc.getNavigator().cancelNavigation();
        busy = true;
        Block base = currentBase;
        Material species = base.getType();
        List<Block> tree = collectTree(base);

        host.breakBlockProperly(npc, base, success -> {
            // v0.8.0: /jarvis stop mid-chop must not fell the rest of the tree
            if (mainTask == null || mainTask.isCancelled()) return;
            if (!success) {
                busy = false;
                currentBase = null;
                host.sayQuiet(player, "That trunk resists me. Choosing another.");
                return;
            }
            logsCollected++;
            timberCascade(tree, base, species, () -> {
                treesFelled++;
                host.sayQuiet(player, "Timber! " + treesFelled + "/" + treeQuota + " down.");
                if (replant) {
                    replantSapling(base, species);
                }
                currentBase = null;
                busy = false;
            });
        });
    }

    /** Fell the remaining logs top-down, a couple per tick — reads as the tree falling. */
    private void timberCascade(List<Block> tree, Block base, Material species, Runnable onDone) {
        // Highest first
        tree.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        ArrayDeque<Block> queue = new ArrayDeque<>(tree);
        queue.remove(base);

        World world = base.getWorld();
        world.playSound(base.getLocation(), Sound.BLOCK_WOOD_BREAK, 1.0f, 0.7f);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (mainTask == null || mainTask.isCancelled() || !npc.isSpawned()) {
                    cancel();   // stopped mid-fell — leave the rest of the tree standing
                    return;
                }
                for (int i = 0; i < 2 && !queue.isEmpty(); i++) {
                    Block log = queue.poll();
                    if (LOGS.contains(log.getType())) {
                        log.breakNaturally(host.getToolInHand(npc));
                        logsCollected++;
                    }
                }
                if (queue.isEmpty()) {
                    cancel();
                    onDone.run();
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private void replantSapling(Block base, Material species) {
        Material sapling = SAPLINGS.get(species);
        if (sapling == null) return;
        Block ground = base.getRelative(BlockFace.DOWN);
        if ((ground.getType() == Material.DIRT || ground.getType() == Material.GRASS_BLOCK
                || ground.getType() == Material.PODZOL || ground.getType() == Material.MUD)
                && base.getType() == Material.AIR) {
            base.setType(sapling);
            base.getWorld().playSound(base.getLocation(), Sound.ITEM_CROP_PLANT, 0.7f, 0.9f);
        }
    }

    /** A tree base: a log with soil below and connected leaves somewhere above. */
    private Block findNearestTreeBase(Location center) {
        World world = center.getWorld();
        if (world == null) return null;
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();

        Block best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -6; y <= 6; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    Block b = world.getBlockAt(cx + x, cy + y, cz + z);
                    if (!LOGS.contains(b.getType())) continue;
                    Material below = b.getRelative(BlockFace.DOWN).getType();
                    if (below != Material.DIRT && below != Material.GRASS_BLOCK
                            && below != Material.PODZOL && below != Material.MUD) continue;
                    if (!hasLeavesAbove(b)) continue;

                    double d = x * x + y * y + z * z;
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = b;
                    }
                }
            }
        }
        return best;
    }

    private boolean hasLeavesAbove(Block base) {
        for (int y = 1; y <= 12; y++) {
            Material t = base.getRelative(0, y, 0).getType();
            if (t.name().endsWith("_LEAVES")) return true;
            if (t == Material.AIR) return false;
        }
        return false;
    }

    /** Flood-fill the connected logs (the trunk and branches), bounded. */
    private List<Block> collectTree(Block base) {
        List<Block> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<Block> frontier = new ArrayDeque<>();
        frontier.add(base);

        while (!frontier.isEmpty() && result.size() < MAX_TREE_LOGS) {
            Block b = frontier.poll();
            long key = ((long) b.getX() << 40) ^ ((long) b.getY() << 20) ^ b.getZ();
            if (!seen.add(key)) continue;
            if (!LOGS.contains(b.getType())) continue;
            result.add(b);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        frontier.add(b.getRelative(dx, dy, dz));
                    }
                }
            }
        }
        return result;
    }

    private void finish(BukkitRunnable self) {
        self.cancel();
        host.taskDone(player, self);
        npc.getNavigator().cancelNavigation();
        host.say(player, "Timber work complete, sir — " + treesFelled + " trees, "
                + logsCollected + " logs" + (replant ? ", saplings in the ground." : "."));
        if (treesFelled >= 3) {
            Entertainer.celebrate(host, player, npc);
        }
        if (deposits.hasChest(player) && host.lootSlotsUsed(npc) > 0
                && plugin.getConfig().getBoolean("mining.auto-deposit", true)) {
            deposits.startDepositRun(player, deposits.getChest(player), () -> {});
        }
    }
}
