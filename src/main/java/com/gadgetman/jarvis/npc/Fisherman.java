package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.npc.provider.INPCProvider;
import com.gadgetman.jarvis.recovery.TaskFailure;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * Fisherman (v0.7.0) - "Do some fishing, Jarvis."
 *
 * Real fishing mechanics aren't available to NPCs (the bobber belongs to
 * real players), so this is a faithful simulation: he finds the water's
 * edge, faces the water, casts with a rod swing and the bobber sound, waits
 * a realistic 10–25 seconds, then a splash — and the catch flies out of the
 * water to him. Loot follows vanilla-ish odds: 85% fish, 10% junk, 5%
 * treasure. Continues until told to stop or his bags fill.
 */
class Fisherman {

    private static final Random RANDOM = new Random();

    private static final Material[] FISH = {
            Material.COD, Material.COD, Material.COD,           // ~60% of fish
            Material.SALMON, Material.SALMON,                    // ~25%
            Material.PUFFERFISH,                                 // ~13%
            Material.TROPICAL_FISH                               // rare
    };
    private static final Material[] JUNK = {
            Material.STICK, Material.BOWL, Material.STRING,
            Material.LEATHER_BOOTS, Material.ROTTEN_FLESH, Material.INK_SAC
    };
    private static final Material[] TREASURE = {
            Material.NAME_TAG, Material.SADDLE, Material.BOW,
            Material.NAUTILUS_SHELL, Material.BOOK
    };

    private final Jarvis plugin;
    private final JarvisNPC host;
    private final Player player;
    private final INPCProvider provider;
    private final DepositManager deposits;

    private Location waterSpot = null;    // The block of water he's fishing in
    private int catches = 0;
    private int waitTicks = 0;            // Countdown to the next bite (20-tick loop units)

    Fisherman(JarvisNPC host, Player player, DepositManager deposits) {
        this.host = host;
        this.plugin = host.getPlugin();
        this.player = player;
        this.provider = host.getProvider();
        this.deposits = deposits;
    }

    void start() {
        Location npcLoc = host.getCurrentLocation(player);
        Location edge = findWaterEdge(npcLoc);
        if (edge == null) {
            // Nothing to recover to — but "no water here" is worth saying with
            // the surroundings in it rather than as a stock line.
            host.reportFailure(TaskFailure.of(player, "fish")
                    .step("looking for somewhere to cast from")
                    .reason("no water edge found within the search radius")
                    .say("No fishable water nearby, sir. A pond would be a start.")
                    .where(npcLoc)
                    .build());
            return;
        }

        host.applyNavigatorDefaults(player, null);
        host.equipTool(player, Material.FISHING_ROD);
        host.say(player, "A spot of fishing, sir. Excellent choice — I find it centres one.");

        BukkitRunnable task = new BukkitRunnable() {
            boolean inPosition = false;
            int stalled = 0;
            Location lastPos = null;

            @Override
            public void run() {
                if (!provider.isSpawned(player) || !player.isOnline()) {
                    cancel();
                    host.taskDone(player, this);
                    return;
                }
                Location loc = host.getCurrentLocation(player);
                host.pickupNearbyItems(player, loc);

                // Bags full — deliver and stop (fishing is leisure, not a shift)
                if (host.lootSlotsUsed(player) >= JarvisNPC.LOOT_CAPACITY - 2) {
                    cancel();
                    host.taskDone(player, this);
                    host.say(player, "The bags are full of fish, sir — " + catches
                            + " catches. A fine session.");
                    if (deposits.hasChest(player)) {
                        deposits.startDepositRun(player, deposits.getChest(player), () -> {});
                    }
                    return;
                }

                // Get to the water's edge
                if (!inPosition) {
                    double dist = loc.distance(edge.clone().add(0.5, 0, 0.5));
                    if (dist <= 1.5) {
                        inPosition = true;
                        provider.cancelNavigation(player);
                        cast(loc);
                        return;
                    }
                    if (!provider.isNavigating(player)) {
                        provider.navigateTo(player, edge.clone().add(0.5, 1, 0.5));
                    }
                    if (lastPos != null && loc.distance(lastPos) < 0.15) stalled++;
                    else stalled = 0;
                    lastPos = loc.clone();
                    if (stalled > 6) {
                        provider.cancelNavigation(player);
                        provider.teleport(player, host.findSafeNear(edge.clone().add(0.5, 1, 0.5)));
                        stalled = 0;
                    }
                    return;
                }

                // Waiting on a bite
                if (waitTicks > 0) {
                    waitTicks--;
                    // Idle ripples so it looks alive
                    if (waterSpot != null && RANDOM.nextInt(3) == 0) {
                        waterSpot.getWorld().spawnParticle(Particle.SPLASH,
                                waterSpot.clone().add(0.5, 1.0, 0.5), 2, 0.2, 0.05, 0.2);
                    }
                    if (waitTicks == 0) {
                        reelIn();
                        cast(loc);
                    }
                }
            }
        };
        task.runTaskTimer(plugin, 10L, 20L);
        host.registerTask(player, task);
    }

    private void cast(Location npcLoc) {
        waterSpot = pickWaterSpot(npcLoc);
        if (waterSpot == null) {
            // v0.8.0: no castable water in view — retry shortly instead of
            // standing there forever with the rod raised (the old wedge)
            waitTicks = 3;
            return;
        }

        provider.lookAt(player, waterSpot.clone().add(0.5, 1, 0.5));
        if (provider.getEntity(player) instanceof LivingEntity le) le.swingMainHand();
        npcLoc.getWorld().playSound(npcLoc, Sound.ENTITY_FISHING_BOBBER_THROW, 0.8f, 1.0f);

        waitTicks = 10 + RANDOM.nextInt(16); // 10–25 seconds at the 20-tick loop
    }

    private void reelIn() {
        if (waterSpot == null || !provider.isSpawned(player)) return;
        World world = waterSpot.getWorld();
        Location splash = waterSpot.clone().add(0.5, 1.0, 0.5);

        world.playSound(splash, Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.0f, 1.0f);
        world.spawnParticle(Particle.SPLASH, splash, 12, 0.3, 0.2, 0.3);

        // Roll the catch
        Material caught;
        int roll = RANDOM.nextInt(100);
        boolean treasure = false;
        if (roll < 85) caught = FISH[RANDOM.nextInt(FISH.length)];
        else if (roll < 95) caught = JUNK[RANDOM.nextInt(JUNK.length)];
        else { caught = TREASURE[RANDOM.nextInt(TREASURE.length)]; treasure = true; }

        // The catch arcs out of the water toward him
        Item item = world.dropItem(splash, new ItemStack(caught, 1));
        Vector toNpc = host.getCurrentLocation(player).toVector().subtract(splash.toVector());
        item.setVelocity(toNpc.normalize().multiply(0.3).setY(0.35));

        catches++;
        if (provider.getEntity(player) instanceof LivingEntity le) le.swingMainHand();

        if (treasure) {
            host.say(player, "Well now — a " + caught.name().toLowerCase().replace('_', ' ')
                    + " from the depths, sir. The lake provides.");
            Entertainer.celebrate(host, player);
        } else if (catches % 10 == 0) {
            host.sayQuiet(player, catches + " catches and counting.");
        }
    }

    /**
     * A water block 2–4 blocks out, with air above. v0.8.0: probes the facing
     * direction first, then all four compass directions — he no longer wedges
     * when he arrives at the edge facing the wrong way.
     */
    private Location pickWaterSpot(Location npcLoc) {
        World world = npcLoc.getWorld();
        Vector facing = npcLoc.getDirection().setY(0);
        if (facing.lengthSquared() < 0.01) facing = new Vector(1, 0, 0);
        facing.normalize();

        Vector[] probes = {
                facing,
                new Vector(1, 0, 0), new Vector(-1, 0, 0),
                new Vector(0, 0, 1), new Vector(0, 0, -1)
        };
        for (Vector dir : probes) {
            for (int out = 2; out <= 4; out++) {
                Location probe = npcLoc.clone().add(dir.clone().multiply(out));
                for (int dy = 0; dy >= -3; dy--) {
                    Block b = world.getBlockAt(probe.getBlockX(), probe.getBlockY() + dy, probe.getBlockZ());
                    if (b.getType() == Material.WATER
                            && b.getRelative(BlockFace.UP).getType() == Material.AIR) {
                        return b.getLocation();
                    }
                }
            }
        }
        return null;
    }

    /** A standable block adjacent to water, within 10 blocks. */
    private Location findWaterEdge(Location center) {
        World world = center.getWorld();
        if (world == null) return null;
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();

        Location best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int x = -10; x <= 10; x++) {
            for (int y = -4; y <= 3; y++) {
                for (int z = -10; z <= 10; z++) {
                    Block stand = world.getBlockAt(cx + x, cy + y, cz + z);
                    if (!stand.getType().isSolid()) continue;
                    Block above = stand.getRelative(BlockFace.UP);
                    if (above.getType() != Material.AIR
                            || above.getRelative(BlockFace.UP).getType() != Material.AIR) continue;

                    boolean nearWater = false;
                    for (BlockFace face : new BlockFace[]{
                            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                        if (stand.getRelative(face).getType() == Material.WATER
                                || above.getRelative(face).getType() == Material.WATER) {
                            nearWater = true;
                            break;
                        }
                    }
                    if (!nearWater) continue;

                    double d = x * x + y * y + z * z;
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = above.getLocation();
                    }
                }
            }
        }
        return best;
    }
}
