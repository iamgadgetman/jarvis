package com.gadgetman.jarvis.nav;

import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Facing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* over block positions, for a butler on foot.
 *
 * <p>A node is a block a body can stand in: feet and head clear, something
 * under the feet (a floor, a ladder, water). Moves between nodes are the
 * things a player can do without tools: walk, step up one, drop a few, jump a
 * one-block gap, swim, climb, walk through a door. The cost table favours
 * flat ground; hazards are never entered or stood on, and edges over long
 * drops cost a little extra so he keeps away from cliffs when he can.
 *
 * <p>A search has a node budget. When it runs out the result is a path to
 * the node nearest the goal, marked partial, so a long trip is chained from
 * partial paths rather than failed outright.
 */
public final class AStar {

    /** Tuning. The defaults suit a player-sized body. */
    public record Options(int nodeBudget, int maxDrop, int maxDropIntoWater, boolean allowSwim,
                          boolean allowJumpGap, boolean allowDoors) {
        public static Options defaults() {
            return new Options(4000, 3, 12, true, true, true);
        }

        public Options withBudget(int budget) {
            return new Options(budget, maxDrop, maxDropIntoWater, allowSwim, allowJumpGap, allowDoors);
        }
    }

    private static final Facing[] SIDES = { Facing.NORTH, Facing.EAST, Facing.SOUTH, Facing.WEST };

    private static final double COST_WALK = 1.0;
    private static final double COST_STEP_UP = 1.5;
    private static final double COST_DROP_BASE = 1.0;
    private static final double COST_DROP_PER_BLOCK = 0.5;
    private static final double COST_JUMP_GAP = 3.0;
    private static final double COST_SWIM = 3.0;
    private static final double COST_SWIM_VERTICAL = 3.5;
    private static final double COST_CLIMB = 1.5;
    private static final double COST_DOOR = 2.0;
    private static final double COST_EDGE = 0.4;

    private final Terrain terrain;
    private final Options options;

    public AStar(Terrain terrain) {
        this(terrain, Options.defaults());
    }

    public AStar(Terrain terrain, Options options) {
        this.terrain = terrain;
        this.options = options;
    }

    // ==================== THE SEARCH ====================

    /**
     * Find a path. The goal need not be standable: the nearest standable
     * block within a couple of blocks of it, vertically, is used.
     */
    public Path find(BlockPos from, BlockPos to) {
        BlockPos start = nearestStandable(from, 2);
        BlockPos goal = nearestStandable(to, 3);
        if (start == null || goal == null) return Path.EMPTY;
        if (start.equals(goal)) return new Path(List.of(), false, 0);

        record Open(long key, double f) { }
        PriorityQueue<Open> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> g = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, MoveType> via = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        long startKey = start.packed();
        long goalKey = goal.packed();
        g.put(startKey, 0.0);
        open.add(new Open(startKey, heuristic(start, goal)));

        long bestKey = startKey;
        double bestH = heuristic(start, goal);
        int expanded = 0;

        while (!open.isEmpty()) {
            Open current = open.poll();
            if (closed.contains(current.key)) continue;
            if (current.key == goalKey) {
                return build(start, goal, parent, via, false, expanded);
            }
            closed.add(current.key);
            expanded++;
            if (expanded > options.nodeBudget()) break;

            BlockPos pos = BlockPos.unpack(current.key);
            double gHere = g.get(current.key);

            for (Move move : movesFrom(pos)) {
                long key = move.to.packed();
                if (closed.contains(key)) continue;
                double tentative = gHere + move.cost;
                Double known = g.get(key);
                if (known != null && tentative >= known) continue;
                g.put(key, tentative);
                parent.put(key, current.key);
                via.put(key, move.type);
                double h = heuristic(move.to, goal);
                open.add(new Open(key, tentative + h));
                if (h < bestH) {
                    bestH = h;
                    bestKey = key;
                }
            }
        }

        // Out of budget or boxed in: the nearest we got.
        if (bestKey == startKey) return new Path(List.of(), true, expanded);
        return build(start, BlockPos.unpack(bestKey), parent, via, true, expanded);
    }

    private Path build(BlockPos start, BlockPos end, Map<Long, Long> parent, Map<Long, MoveType> via,
                       boolean partial, int expanded) {
        List<Path.Step> steps = new ArrayList<>();
        long key = end.packed();
        long startKey = start.packed();
        while (key != startKey) {
            steps.add(new Path.Step(BlockPos.unpack(key), via.get(key)));
            Long p = parent.get(key);
            if (p == null) break;
            key = p;
        }
        Collections.reverse(steps);
        return new Path(steps, partial, expanded);
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.5;
    }

    // ==================== WHAT A BODY CAN DO ====================

    private record Move(BlockPos to, MoveType type, double cost) { }

    /** Feet and head clear of anything but air, water, plants or a door, and nothing nasty. */
    boolean occupiable(BlockPos feet) {
        if (feet.y() < terrain.minY() || feet.y() + 1 > terrain.maxY()) return false;
        BlockPos head = feet.above();
        return roomy(feet) && roomy(head)
                && !terrain.isHazard(feet) && !terrain.isHazard(head) && !terrain.isHazard(feet.below());
    }

    private boolean roomy(BlockPos pos) {
        return terrain.isPassable(pos) || (options.allowDoors() && terrain.isDoor(pos));
    }

    /** Occupiable, with something holding him up. */
    boolean standable(BlockPos feet) {
        if (!occupiable(feet)) return false;
        BlockPos below = feet.below();
        return terrain.isSolid(below) || terrain.isClimbable(feet) || terrain.isLiquid(feet)
                || terrain.isLiquid(below) || terrain.isDoor(below);
    }

    /** The standable block at or nearest to a point, searched a few blocks up and down. */
    public BlockPos nearestStandable(BlockPos at, int reach) {
        if (standable(at)) return at;
        for (int d = 1; d <= reach; d++) {
            BlockPos down = at.offset(0, -d, 0);
            if (standable(down)) return down;
            BlockPos up = at.offset(0, d, 0);
            if (standable(up)) return up;
        }
        return null;
    }

    private List<Move> movesFrom(BlockPos n) {
        List<Move> out = new ArrayList<>(8);
        boolean inWater = terrain.isLiquid(n);
        boolean onLadder = terrain.isClimbable(n);

        for (Facing f : SIDES) {
            BlockPos t = n.side(f);

            if (inWater && options.allowSwim()) {
                if (occupiable(t) && (terrain.isLiquid(t) || standable(t))) {
                    out.add(new Move(t, terrain.isLiquid(t) ? MoveType.SWIM : MoveType.WALK,
                            terrain.isLiquid(t) ? COST_SWIM : COST_WALK));
                }
                continue;
            }

            // Level ground, or through a door
            if (standable(t)) {
                boolean door = terrain.isDoor(t) || terrain.isDoor(t.above());
                if (door && !options.allowDoors()) continue;
                double cost = door ? COST_DOOR : COST_WALK + edgePenalty(t);
                out.add(new Move(t, door ? MoveType.DOOR : MoveType.WALK, cost));
                continue;
            }

            // Step up one, with headroom for the hop
            BlockPos up = t.above();
            if (standable(up) && terrain.isSolid(t) && roomy(n.offset(0, 2, 0))) {
                out.add(new Move(up, MoveType.STEP_UP, COST_STEP_UP + edgePenalty(up)));
                continue;
            }

            // Drop off an edge
            if (occupiable(t) && !terrain.isSolid(t.below())) {
                Move drop = dropFrom(t);
                if (drop != null) out.add(drop);

                // Or jump the gap to the block beyond
                if (options.allowJumpGap() && !inWater && !onLadder) {
                    BlockPos beyond = t.side(f);
                    if (standable(beyond) && terrain.isSolid(beyond.below())
                            && roomy(n.offset(0, 2, 0)) && roomy(t.offset(0, 2, 0)) && roomy(beyond.offset(0, 2, 0))) {
                        out.add(new Move(beyond, MoveType.JUMP_GAP, COST_JUMP_GAP));
                    }
                }
            }
        }

        // Up and down a ladder
        if (onLadder) {
            BlockPos up = n.above();
            if (occupiable(up) && (terrain.isClimbable(up) || standable(up))) {
                out.add(new Move(up, MoveType.CLIMB_UP, COST_CLIMB));
            }
            BlockPos down = n.below();
            if (occupiable(down) && (terrain.isClimbable(down) || standable(down))) {
                out.add(new Move(down, MoveType.CLIMB_DOWN, COST_CLIMB));
            }
        }

        // Up and down through water
        if (inWater && options.allowSwim()) {
            BlockPos up = n.above();
            if (occupiable(up) && (terrain.isLiquid(up) || standable(up))) {
                out.add(new Move(up, MoveType.SWIM, COST_SWIM_VERTICAL));
            }
            BlockPos down = n.below();
            if (occupiable(down) && terrain.isLiquid(down)) {
                out.add(new Move(down, MoveType.SWIM, COST_SWIM_VERTICAL));
            }
        }

        return out;
    }

    /** Walk off {@code t} and land where the fall ends, if it ends somewhere safe and soon. */
    private Move dropFrom(BlockPos t) {
        BlockPos cell = t;
        for (int d = 1; d <= options.maxDropIntoWater(); d++) {
            cell = cell.below();
            if (terrain.isHazard(cell) || terrain.isHazard(cell.below())) return null;
            if (!roomy(cell)) return null;               // hit something that is not a floor: a wall, no drop
            boolean water = terrain.isLiquid(cell);
            if (terrain.isSolid(cell.below()) || water) {
                if (!standable(cell)) return null;
                if (!water && d > options.maxDrop()) return null;
                return new Move(cell, water ? MoveType.SWIM : MoveType.DROP,
                        COST_DROP_BASE + COST_DROP_PER_BLOCK * d + (water ? COST_SWIM : 0));
            }
        }
        return null;
    }

    /** A little extra for a block beside a long fall, so he walks a step in from the edge. */
    private double edgePenalty(BlockPos t) {
        for (Facing f : SIDES) {
            BlockPos side = t.side(f);
            if (terrain.isSolid(side) || terrain.isSolid(side.below())) continue;
            boolean floorSoon = false;
            for (int d = 2; d <= 4; d++) {
                if (terrain.isSolid(side.offset(0, -d, 0)) || terrain.isLiquid(side.offset(0, -d, 0))) {
                    floorSoon = true;
                    break;
                }
            }
            if (!floorSoon) return COST_EDGE;
        }
        return 0;
    }
}
