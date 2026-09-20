package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Facing;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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

    private final ButlerHost host;
    private final Owner player;
    private final Butler butler;
    private final World world;

    private final int radius;
    private final int spacing;
    private final String lightType;
    private final boolean wallPlacement;
    private final int skipLightLevel;
    private final boolean underwaterLanterns;   // v0.8.1: sea lanterns in shallow water

    private static final int MAX_WATER_DEPTH = 4;   // deeper than this: skip, no diving expeditions

    private final BlockPos center;
    private final ArrayDeque<BlockPos> targets = new ArrayDeque<>();
    private int placed = 0;
    private int skippedLit = 0;
    private int waterSpots = 0;
    private boolean truncated = false;

    private static final double REACH = 3.5;
    private static final int STALL_HOP_TICKS = 6;
    private static final int MAX_TARGETS = 400;
    private int stalled = 0;
    private Vec3 lastPos = null;

    /** radius/spacing <= 0 and typeArg == null mean "use config defaults". */
    Lamplighter(ButlerHost host, Owner player, World world, int radius, String typeArg, int spacing) {
        this.host = host;
        this.player = player;
        this.butler = host.butler(player);
        this.world = world;

        Config cfg = host.config();
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
        this.center = player.pos().block();
    }

    static String parseType(String s) {
        if (s == null) return Ids.TORCH;
        return switch (s.toLowerCase().replace("_", "").replace("-", "")) {
            case "endrod", "rod" -> Ids.END_ROD;
            case "lantern" -> Ids.LANTERN;
            default -> Ids.TORCH;
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

        butler.applyNavigationDefaults(null);
        host.equipTool(player, lightType);

        String typeName = Blocks.pretty(lightType);
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

        Task task = host.platform().scheduler().every(10L, 10L, self -> {
            if (!butler.isSpawned() || !player.isOnline()) {
                self.cancel();
                host.taskDone(player, self);
                return;
            }
            tick(butler.pos(), self);
        });
        host.registerTask(player, task);
    }

    private void tick(Vec3 loc, Task self) {
        if (targets.isEmpty()) {
            finish(self);
            return;
        }

        BlockPos cell = targets.peek();

        // Spot no longer usable (built over, flooded when we can't handle it)
        String cur = world.block(cell).id();
        boolean usable = (Ids.AIR.equals(cur)
                        || (Ids.WATER.equals(cur) && underwaterLanterns))
                && world.isOccluding(cell.below());
        if (!usable) {
            targets.poll();
            return;
        }

        // v0.8.2: water targets get longer reach — he floats at the surface
        // (the lifeguard keeps him there) and places the lantern below him.
        double reach = Ids.WATER.equals(cur) ? 5.0 : REACH;
        double dist = loc.distance(cell.center());
        if (dist > reach) {
            if (!butler.isNavigating()) {
                butler.navigateTo(cell.above().standing());
            }
            if (lastPos != null && loc.distance(lastPos) < 0.15) stalled++;
            else stalled = 0;
            lastPos = loc;
            if (stalled > STALL_HOP_TICKS) {
                butler.cancelNavigation();
                butler.teleport(host.findSafeNear(world, cell.above().standing()));
                stalled = 0;
            }
            return;
        }

        // Place the light: face, swing, done
        targets.poll();
        butler.lookAt(cell.center());
        butler.swing();
        boolean wasWater = Ids.WATER.equals(world.block(cell).id());
        if (placeLight(cell)) {
            placed++;
            world.sound(cell.center(),
                    wasWater ? Ids.SOUND_BLOCK_GLASS_PLACE : Ids.SOUND_BLOCK_WOOD_PLACE, 0.8f, 1.1f);
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
    private boolean placeLight(BlockPos cell) {
        String cur = world.block(cell).id();
        if (Ids.WATER.equals(cur)) {
            if (!underwaterLanterns) return false;
            world.setBlock(cell, BlockState.of(Ids.SEA_LANTERN));
            return true;
        }
        if (!Ids.AIR.equals(cur)) return false;

        if (wallPlacement && Ids.TORCH.equals(lightType)) {
            for (Facing face : new Facing[]{Facing.NORTH, Facing.SOUTH, Facing.EAST, Facing.WEST}) {
                if (world.isOccluding(cell.side(face))) {
                    // A wall torch faces away from the block it hangs on.
                    world.setBlock(cell, BlockState.of(Ids.WALL_TORCH).with("facing", face.opposite().key()));
                    return true;
                }
            }
            // No wall nearby — ground it is
        }
        world.setBlock(cell, BlockState.of(lightType));
        return true;
    }

    // ==================== PLANNING ====================

    /**
     * Grid points every `spacing` blocks within `radius` of the center,
     * walked in serpentine row order (least backtracking). Each point is
     * dropped onto the local surface; spots already bright enough are skipped.
     */
    private void planTargets() {
        List<int[]> offsets = new ArrayList<>();
        for (int gx = -radius; gx <= radius; gx += spacing) offsetsRow(offsets, gx);

        boolean flip = false;
        List<BlockPos> ordered = new ArrayList<>();
        Integer currentRow = null;
        List<BlockPos> row = new ArrayList<>();
        for (int[] off : offsets) {
            BlockPos spot = surfaceSpot(center.x() + off[0], center.z() + off[1]);
            if (spot == null) continue;
            if (currentRow == null || off[0] != currentRow) {
                if (!row.isEmpty()) {
                    if (flip) Collections.reverse(row);
                    ordered.addAll(row);
                    flip = !flip;
                    row = new ArrayList<>();
                }
                currentRow = off[0];
            }
            row.add(spot);
        }
        if (!row.isEmpty()) {
            if (flip) Collections.reverse(row);
            ordered.addAll(row);
        }

        for (BlockPos l : ordered) {
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
    private BlockPos surfaceSpot(int x, int z) {
        int top = Math.min(world.maxY() - 1, center.y() + 16);
        int bottom = Math.max(world.minY() + 1, center.y() - 16);

        for (int y = top; y >= bottom; y--) {
            BlockPos ground = new BlockPos(x, y, z);
            String g = world.block(ground).id();
            if (!world.isOccluding(ground)) continue;   // air, leaves, glass, fluids: keep scanning down
            if (Ids.MAGMA_BLOCK.equals(g)) return null;

            BlockPos cell = ground.above();
            String cur = world.block(cell).id();

            // v0.8.1: pond/shore floor — sea lantern territory (shallow only)
            if (Ids.WATER.equals(cur)) {
                if (!underwaterLanterns) return null;
                int depth = 0;
                BlockPos probe = cell;
                while (Ids.WATER.equals(world.block(probe).id()) && depth <= MAX_WATER_DEPTH) {
                    probe = probe.above();
                    depth++;
                }
                if (depth > MAX_WATER_DEPTH) return null;   // deep water: leave it be
                if (world.blockLight(cell) >= skipLightLevel) {
                    skippedLit++;
                    return null;
                }
                waterSpots++;
                return cell;
            }

            if (!Ids.AIR.equals(cur) || world.isSolid(cell.above())) return null;

            if (world.blockLight(cell) >= skipLightLevel) {
                skippedLit++;
                return null;                          // someone already lit this spot
            }
            return cell;
        }
        return null;
    }

    private void finish(Task self) {
        self.cancel();
        host.taskDone(player, self);
        butler.cancelNavigation();
        String note = skippedLit > 0
                ? " (" + skippedLit + " spots were already bright enough.)" : "";
        host.say(player, "The grounds are lit, sir — " + placed + " lights placed." + note);
        if (placed >= 20) {
            Entertainer.celebrate(host, player);
        }
    }
}
