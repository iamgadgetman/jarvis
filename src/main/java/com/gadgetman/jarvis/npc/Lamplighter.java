package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Lamplighter (v0.8.0) - "/jarvis light [radius] [type] [spacing]"
 *
 * Spawn-proofs the area around the player with a grid of lights. The
 * spawn-prevention math: hostile mobs spawn at block-light 0 (since 1.18),
 * and a torch (light 14) loses 1 light per block of taxicab distance — so a
 * grid with spacing of 13 or less keeps every block above light 0. The
 * default spacing of 12 leaves a small margin.
 *
 * He walks to each grid point and places the light properly — no scattering
 * blocks from the sky. Types: torch (default), end rod, lantern. Placement
 * on the ground, or on walls when configured and a wall is handy.
 *
 * v0.8.1: water-aware. Torches don't survive underwater — for grid points
 * that land in shallow water (ponds, shorelines, up to 4 deep) he places a
 * SEA LANTERN on the floor instead (light 15 — also keeps drowned from
 * spawning). Deep water is skipped. Config: lighting.underwater.
 */
class Lamplighter {

    /** Above this spacing, gaps of block-light 0 appear between lights. */
    static final int MAX_SPAWNPROOF_SPACING = 13;

    private final Jarvis plugin;
    private final JarvisNPC host;
    private final Player player;
    private final NPC npc;

    private final int radius;
    private final int spacing;
    private final Material lightType;
    private final boolean wallPlacement;
    private final int skipLightLevel;
    private final boolean underwaterLanterns;   // v0.8.1: sea lanterns in shallow water

    private static final int MAX_WATER_DEPTH = 4;   // deeper than this: skip, no diving expeditions

    private final Location center;
    private final ArrayDeque<Location> targets = new ArrayDeque<>();
    private int placed = 0;
    private int skippedLit = 0;
    private int waterSpots = 0;
    private boolean truncated = false;

    private static final double REACH = 3.5;
    private static final int STALL_HOP_TICKS = 6;
    private static final int MAX_TARGETS = 400;
    private int stalled = 0;
    private Location lastPos = null;

    /** radius/spacing <= 0 and typeArg == null mean "use config defaults". */
    Lamplighter(JarvisNPC host, Player player, NPC npc, int radius, String typeArg, int spacing) {
        this.host = host;
        this.plugin = host.getPlugin();
        this.player = player;
        this.npc = npc;

        var cfg = plugin.getConfig();
        int r = radius > 0 ? radius : cfg.getInt("lighting.default-radius", 16);
        int s = spacing > 0 ? spacing : cfg.getInt("lighting.default-spacing", 12);
        this.radius = Math.max(4, Math.min(r, 48));
        this.spacing = Math.max(2, Math.min(s, 32));
        this.lightType = parseType(typeArg != null ? typeArg
                : cfg.getString("lighting.default-type", "torch"));
        this.wallPlacement = "wall".equalsIgnoreCase(cfg.getString("lighting.placement", "ground"));
        this.skipLightLevel = Math.max(1, Math.min(cfg.getInt("lighting.skip-light-level", 8), 16));
        this.underwaterLanterns = !"skip".equalsIgnoreCase(
                cfg.getString("lighting.underwater", "sea_lantern"));

        // Centered on the player — "light this place up" means where THEY are
        this.center = player.getLocation().getBlock().getLocation();
    }

    static Material parseType(String s) {
        if (s == null) return Material.TORCH;
        return switch (s.toLowerCase().replace("_", "").replace("-", "")) {
            case "endrod", "rod" -> Material.END_ROD;
            case "lantern" -> Material.LANTERN;
            default -> Material.TORCH;
        };
    }

    void start() {
        planTargets();

        if (targets.isEmpty()) {
            host.say(player, skippedLit > 0
                    ? "The area is already well lit, sir — nothing for me to add."
                    : "I couldn't find anywhere sensible to place a light here, sir.");
            return;
        }

        host.applyNavigatorDefaults(npc, null);
        host.equipTool(npc, lightType == Material.END_ROD ? Material.END_ROD
                : lightType == Material.LANTERN ? Material.LANTERN : Material.TORCH);

        String typeName = lightType.name().toLowerCase().replace('_', ' ');
        StringBuilder opening = new StringBuilder("Lighting the grounds, sir — ")
                .append(targets.size()).append(" lights (").append(typeName)
                .append(") across ").append(radius).append(" blocks, every ")
                .append(spacing).append(".");
        if (waterSpots > 0) {
            opening.append(" Sea lanterns for the ").append(waterSpots)
                    .append(" spot").append(waterSpots == 1 ? "" : "s").append(" in the water.");
        }
        if (spacing > MAX_SPAWNPROOF_SPACING) {
            opening.append(" Mind you, gaps over ").append(MAX_SPAWNPROOF_SPACING)
                    .append(" leave dark pockets where the rabble can spawn.");
        }
        if (truncated) {
            opening.append(" (I capped the list at ").append(MAX_TARGETS)
                    .append(" — a second pass will finish the job.)");
        }
        host.say(player, opening.toString());

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!npc.isSpawned() || !player.isOnline()) {
                    cancel();
                    host.taskDone(player, this);
                    return;
                }
                tick(host.getCurrentLocation(npc), this);
            }
        };
        task.runTaskTimer(plugin, 10L, 10L);
        host.registerTask(player, task);
    }

    private void tick(Location loc, BukkitRunnable self) {
        if (targets.isEmpty()) {
            finish(self);
            return;
        }

        Location target = targets.peek();
        Block cell = target.getBlock();

        // Spot no longer usable (built over, flooded when we can't handle it)
        Material cur = cell.getType();
        boolean usable = (cur == Material.AIR
                        || (cur == Material.WATER && underwaterLanterns))
                && cell.getRelative(BlockFace.DOWN).getType().isOccluding();
        if (!usable) {
            targets.poll();
            return;
        }

        // v0.8.2: water targets get longer reach — he floats at the surface
        // (the lifeguard keeps him there) and places the lantern below him.
        double reach = cur == Material.WATER ? 5.0 : REACH;
        double dist = loc.distance(target.clone().add(0.5, 0.5, 0.5));
        if (dist > reach) {
            if (!npc.getNavigator().isNavigating()) {
                npc.getNavigator().setTarget(target.clone().add(0.5, 1, 0.5));
            }
            if (lastPos != null && loc.distance(lastPos) < 0.15) stalled++;
            else stalled = 0;
            lastPos = loc.clone();
            if (stalled > STALL_HOP_TICKS) {
                npc.getNavigator().cancelNavigation();
                npc.teleport(host.findSafeNear(target.clone().add(0.5, 1, 0.5)),
                        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                stalled = 0;
            }
            return;
        }

        // Place the light: face, swing, done
        targets.poll();
        npc.faceLocation(target.clone().add(0.5, 0.5, 0.5));
        if (npc.getEntity() instanceof LivingEntity le) le.swingMainHand();
        boolean wasWater = cell.getType() == Material.WATER;
        if (placeLight(cell)) {
            placed++;
            loc.getWorld().playSound(target,
                    wasWater ? Sound.BLOCK_GLASS_PLACE : Sound.BLOCK_WOOD_PLACE, 0.8f, 1.1f);
            if (placed % 20 == 0) {
                host.sayQuiet(player, placed + " lights placed.");
            }
        }
    }

    /**
     * Set the light block, honoring wall placement for torches when possible.
     * v0.8.1: a water cell gets a SEA LANTERN (torches, end rods and lanterns
     * don't survive underwater); anything else that isn't air gets nothing —
     * the hard guard against lighting a block that's occupied or fluid.
     */
    private boolean placeLight(Block cell) {
        Material cur = cell.getType();
        if (cur == Material.WATER) {
            if (!underwaterLanterns) return false;
            cell.setType(Material.SEA_LANTERN);
            return true;
        }
        if (cur != Material.AIR) return false;

        if (wallPlacement && lightType == Material.TORCH) {
            for (BlockFace face : new BlockFace[]{
                    BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block support = cell.getRelative(face);
                if (support.getType().isOccluding()) {
                    cell.setType(Material.WALL_TORCH);
                    if (cell.getBlockData() instanceof Directional dir
                            && dir.getFaces().contains(face.getOppositeFace())) {
                        dir.setFacing(face.getOppositeFace());
                        cell.setBlockData(dir);
                    }
                    return true;
                }
            }
            // No wall nearby — ground it is
        }
        cell.setType(lightType);
        return true;
    }

    // ==================== PLANNING ====================

    /**
     * Grid points every `spacing` blocks within `radius` of the center,
     * walked in serpentine row order (least backtracking). Each point is
     * dropped onto the local surface; spots already bright enough are skipped.
     */
    private void planTargets() {
        World world = center.getWorld();
        if (world == null) return;

        List<int[]> offsets = new ArrayList<>();
        for (int gx = -radius; gx <= radius; gx += spacing) offsetsRow(offsets, gx);

        boolean flip = false;
        List<Location> ordered = new ArrayList<>();
        Integer currentRow = null;
        List<Location> row = new ArrayList<>();
        for (int[] off : offsets) {
            Location spot = surfaceSpot(world, center.getBlockX() + off[0],
                    center.getBlockZ() + off[1]);
            if (spot == null) continue;
            if (currentRow == null || off[0] != currentRow) {
                if (!row.isEmpty()) {
                    if (flip) java.util.Collections.reverse(row);
                    ordered.addAll(row);
                    flip = !flip;
                    row = new ArrayList<>();
                }
                currentRow = off[0];
            }
            row.add(spot);
        }
        if (!row.isEmpty()) {
            if (flip) java.util.Collections.reverse(row);
            ordered.addAll(row);
        }

        for (Location l : ordered) {
            if (targets.size() >= MAX_TARGETS) { truncated = true; break; }
            targets.add(l);
        }
    }

    private void offsetsRow(List<int[]> into, int gx) {
        for (int gz = -radius; gz <= radius; gz += spacing) {
            if (gx * gx + gz * gz <= radius * radius) {
                into.add(new int[]{gx, gz});
            }
        }
    }

    /**
     * Find where a light goes at (x, z): the topmost solid block within
     * ±16 blocks of the player's level, with 2 blocks of air above it.
     * Returns the AIR cell the light occupies, or null (no footing, water,
     * or already bright enough there).
     */
    private Location surfaceSpot(World world, int x, int z) {
        int top = Math.min(world.getMaxHeight() - 2, center.getBlockY() + 16);
        int bottom = Math.max(world.getMinHeight() + 1, center.getBlockY() - 16);

        for (int y = top; y >= bottom; y--) {
            Block ground = world.getBlockAt(x, y, z);
            Material g = ground.getType();
            if (!g.isOccluding()) continue;          // air, leaves, glass, fluids: keep scanning down
            if (g == Material.MAGMA_BLOCK) return null;

            Block cell = ground.getRelative(BlockFace.UP);
            Material cur = cell.getType();

            // v0.8.1: pond/shore floor — sea lantern territory (shallow only)
            if (cur == Material.WATER) {
                if (!underwaterLanterns) return null;
                int depth = 0;
                Block probe = cell;
                while (probe.getType() == Material.WATER && depth <= MAX_WATER_DEPTH) {
                    probe = probe.getRelative(BlockFace.UP);
                    depth++;
                }
                if (depth > MAX_WATER_DEPTH) return null;   // deep water: leave it be
                if (cell.getLightFromBlocks() >= skipLightLevel) {
                    skippedLit++;
                    return null;
                }
                waterSpots++;
                return cell.getLocation();
            }

            Block above = cell.getRelative(BlockFace.UP);
            if (cur != Material.AIR || above.getType().isSolid()) return null;

            if (cell.getLightFromBlocks() >= skipLightLevel) {
                skippedLit++;
                return null;                          // someone already lit this spot
            }
            return cell.getLocation();
        }
        return null;
    }

    private void finish(BukkitRunnable self) {
        self.cancel();
        host.taskDone(player, self);
        npc.getNavigator().cancelNavigation();
        String note = skippedLit > 0
                ? " (" + skippedLit + " spots were already bright enough.)" : "";
        host.say(player, "The grounds are lit, sir — " + placed + " lights placed." + note);
        if (placed >= 20) {
            Entertainer.celebrate(host, player, npc);
        }
    }
}
