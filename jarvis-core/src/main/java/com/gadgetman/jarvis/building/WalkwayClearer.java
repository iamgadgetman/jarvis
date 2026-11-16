package com.gadgetman.jarvis.building;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Makes sure every room in a planned build can be walked into.
 *
 * <p>A live "cozy fish market" ran its fish counter wall to wall across the
 * open shop front, and a chest and a bookshelf closed the two side gaps. The
 * office, the smoking station and the loft ladder were behind it, and the only
 * way in was to climb over the counter. The prompt asks for a clear path to
 * every room; the model did not keep one. This checks it instead.
 *
 * <p>The check walks the plan as a player would, from outside: two blocks of
 * headroom, steps up only onto stairs and bottom slabs (walking, not jumping
 * onto counters), drops of up to three blocks, ladders climbed both ways, and
 * doors, gates and trapdoors treated as open. Any sizeable stretch of covered
 * floor it cannot reach is joined to the rest by the cheapest path, and the
 * furnishings on that path are removed. Building blocks are never removed --
 * the same line {@link DoorwayClearer} draws -- so a wall is never cut open to
 * make a path; the path goes around through the furniture instead.
 */
public final class WalkwayClearer {

    /** Unreached floor smaller than this is a nook, not a room. */
    static final int MIN_REGION = 4;
    /** Never spend more than this to open one path (see {@link #weight}). */
    static final int MAX_PATH_COST = 8;
    /** Never remove more than this many blocks from one build in total. */
    static final int MAX_TOTAL = 12;
    /** How far a player may drop and walk on. */
    private static final int MAX_DROP = 3;
    /** Floor counts as indoors if something is planned this far above it. */
    private static final int COVER_SCAN = 10;

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final Map<String, String> blocks;
    private final int minX, maxX, minY, maxY, minZ, maxZ;
    private final int fMinX, fMaxX, fMinZ, fMaxZ;

    private WalkwayClearer(Map<String, String> blocks) {
        this.blocks = blocks;
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        int[] flo = {Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] fhi = {Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Map.Entry<String, String> e : blocks.entrySet()) {
            int[] c = parse(e.getKey());
            for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], c[i]); hi[i] = Math.max(hi[i], c[i]); }
            // The footprint is what stands at or above ground, so a path laid
            // out from the door does not count as part of the building.
            if (c[1] >= 0 && !isAir(e.getValue())) {
                flo[0] = Math.min(flo[0], c[0]); fhi[0] = Math.max(fhi[0], c[0]);
                flo[1] = Math.min(flo[1], c[2]); fhi[1] = Math.max(fhi[1], c[2]);
            }
        }
        minX = lo[0] - 2; maxX = hi[0] + 2;
        minY = Math.min(lo[1], 0) - 1; maxY = hi[1] + 2;
        minZ = lo[2] - 2; maxZ = hi[2] + 2;
        fMinX = flo[0]; fMaxX = fhi[0]; fMinZ = flo[1]; fMaxZ = fhi[1];
    }

    /**
     * Opens a path into every walled-off room, in place.
     *
     * @param blocks planned blocks keyed by {@code "x,y,z"}, relative to an
     *               origin at ground level
     * @return one line per block removed, e.g. {@code "bookshelf at 11,0,5"}
     */
    public static List<String> clear(Map<String, String> blocks) {
        List<String> removed = new ArrayList<>();
        if (blocks.isEmpty()) return removed;
        WalkwayClearer w = new WalkwayClearer(blocks);
        if (w.fMinX == Integer.MAX_VALUE) return removed; // nothing above ground
        Set<Long> skipped = new HashSet<>();
        while (removed.size() < MAX_TOTAL) {
            Set<Long> reached = w.reachable();
            if (reached.isEmpty()) break;
            List<Set<Long>> regions = w.unreachedRooms(reached);
            regions.removeIf(r -> r.stream().anyMatch(skipped::contains));
            if (regions.isEmpty()) break;
            List<int[]> path = w.cheapestPath(reached, regions);
            if (path == null) {
                // Nothing affordable reaches any of them; stop rather than loop.
                break;
            }
            if (removed.size() + path.size() > MAX_TOTAL) break;
            for (int[] c : path) w.remove(c, removed);
        }
        return removed;
    }

    /**
     * True if a player arriving from outside can stand at (x,y,z) without
     * anything being removed. For checking a plan, not for changing one.
     */
    static boolean canReach(Map<String, String> blocks, int x, int y, int z) {
        if (blocks.isEmpty()) return false;
        WalkwayClearer w = new WalkwayClearer(blocks);
        if (w.fMinX == Integer.MAX_VALUE) return false;
        return w.reachable().contains(pack(x, y, z));
    }

    // ---- walking -------------------------------------------------------

    /** Every position reachable from outside without removing anything. */
    private Set<Long> reachable() {
        Set<Long> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        for (int[] s : starts()) {
            if (seen.add(pack(s[0], s[1], s[2]))) queue.add(s);
        }
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            for (int[] n : moves(p, false)) {
                if (seen.add(pack(n[0], n[1], n[2]))) queue.add(n);
            }
        }
        return seen;
    }

    /** Standable positions outside the footprint: where a player arrives from. */
    private List<int[]> starts() {
        List<int[]> out = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (x >= fMinX && x <= fMaxX && z >= fMinZ && z <= fMaxZ) continue;
                for (int y = minY + 1; y <= maxY; y++) {
                    if (standable(x, y, z, false)) out.add(new int[]{x, y, z});
                }
            }
        }
        return out;
    }

    /**
     * Positions one move from p. With {@code paying}, furnishings in the feet
     * and head cells of the destination count as passable (at a cost the
     * caller adds up); without it they block.
     */
    private List<int[]> moves(int[] p, boolean paying) {
        int x = p[0], y = p[1], z = p[2];
        List<int[]> out = new ArrayList<>(6);
        boolean onLadder = isLadder(at(x, y, z));
        if (onLadder) {
            if (inBox(x, y + 1, z) && (isLadder(at(x, y + 1, z)) || standable(x, y + 1, z, paying))
                    && open(at(x, y + 2, z), false)) {
                out.add(new int[]{x, y + 1, z});
            }
            if (inBox(x, y - 1, z) && (isLadder(at(x, y - 1, z)) || standable(x, y - 1, z, paying))) {
                out.add(new int[]{x, y - 1, z});
            }
        }
        for (int[] d : DIRS) {
            int nx = x + d[0], nz = z + d[1];
            if (!inBox(nx, y, nz)) continue;
            if (standable(nx, y, nz, paying)) {
                out.add(new int[]{nx, y, nz});
                continue;
            }
            // Step up: onto a stair or bottom slab, or off the top of a ladder.
            if (inBox(nx, y + 1, nz) && standable(nx, y + 1, nz, paying) && open(at(x, y + 2, z), false)
                    && (onLadder || isStep(at(nx, y, nz)))) {
                out.add(new int[]{nx, y + 1, nz});
                continue;
            }
            // Walk off an edge and land, or step down into a ladder.
            if (open(at(nx, y, nz), paying) && open(at(nx, y + 1, nz), paying)) {
                for (int dy = 1; dy <= MAX_DROP; dy++) {
                    int ly = y - dy;
                    if (!inBox(nx, ly, nz)) break;
                    if (standable(nx, ly, nz, paying)) { out.add(new int[]{nx, ly, nz}); break; }
                    if (!open(at(nx, ly, nz), false)) break;
                }
            }
        }
        return out;
    }

    /** Feet at (x,y,z): room for a body there and something to stand on (or a ladder to hold). */
    private boolean standable(int x, int y, int z, boolean paying) {
        String feet = at(x, y, z), head = at(x, y + 1, z);
        if (isLadder(feet)) return open(head, paying) || isLadder(head);
        if (!open(feet, paying) || !open(head, paying)) return false;
        return isSupport(at(x, y - 1, z));
    }

    /** Can a body occupy this cell? Paying lets removable furnishings count. */
    private boolean open(String spec, boolean paying) {
        if (spec == null) return true;
        String b = DoorwayClearer.base(spec);
        if (walkThrough(b)) return true;
        return paying && removable(spec);
    }

    private static boolean walkThrough(String b) {
        return DoorwayClearer.passable(b) || b.equals("ladder") || b.endsWith("_trapdoor")
                || b.endsWith("_fence_gate") || b.equals("hanging_roots") || b.equals("pale_hanging_moss")
                || b.startsWith("weeping_vines") || b.startsWith("cave_vines") || b.equals("water")
                || b.equals("light") || b.equals("structure_void") || b.equals("tall_grass");
    }

    /** Something a player can stand on top of. */
    private static boolean isSupport(String spec) {
        if (spec == null) return false;
        String b = DoorwayClearer.base(spec);
        if (walkThrough(b)) return false;
        // Fences and walls are a block and a half tall: nobody walks along them.
        return !(b.endsWith("_fence") || b.endsWith("_wall"));
    }

    /** A half-height block a player walks up without jumping. */
    private static boolean isStep(String spec) {
        if (spec == null) return false;
        String s = spec.toLowerCase(Locale.ROOT);
        String b = DoorwayClearer.base(s);
        if (b.endsWith("_stairs")) return true;
        return b.endsWith("_slab") && !s.contains("type=top") && !s.contains("type=double");
    }

    /** A furnishing that may be taken out to open a path. */
    private static boolean removable(String spec) {
        String b = DoorwayClearer.base(spec);
        if (b.endsWith("_door") || isLadder(spec)) return false;
        return DoorwayClearer.obstructs(spec, 1);
    }

    private static boolean isLadder(String spec) {
        return spec != null && DoorwayClearer.base(spec).equals("ladder");
    }

    // ---- what is unreached ---------------------------------------------

    /** Groups of covered floor inside the footprint that no walk reaches. */
    private List<Set<Long>> unreachedRooms(Set<Long> reached) {
        Set<Long> unreached = new HashSet<>();
        for (int x = fMinX; x <= fMaxX; x++) {
            for (int z = fMinZ; z <= fMaxZ; z++) {
                for (int y = minY + 1; y <= maxY; y++) {
                    long k = pack(x, y, z);
                    if (reached.contains(k)) continue;
                    if (!standable(x, y, z, false) || !covered(x, y, z)) continue;
                    unreached.add(k);
                }
            }
        }
        List<Set<Long>> rooms = new ArrayList<>();
        Set<Long> done = new HashSet<>();
        for (long start : unreached) {
            if (done.contains(start)) continue;
            Set<Long> room = new HashSet<>();
            Deque<int[]> q = new ArrayDeque<>();
            q.add(unpack(start));
            done.add(start);
            while (!q.isEmpty()) {
                int[] p = q.poll();
                room.add(pack(p[0], p[1], p[2]));
                for (int[] n : moves(p, false)) {
                    long k = pack(n[0], n[1], n[2]);
                    if (unreached.contains(k) && done.add(k)) q.add(n);
                }
            }
            // Count only real floor. The tops of a bed, a stair and a counter
            // join up into an "unreached room" too, and climbing furniture is
            // not what this is for.
            int floor = 0;
            for (long k : room) {
                int[] p = unpack(k);
                String below = at(p[0], p[1] - 1, p[2]);
                if (below != null && DoorwayClearer.structural(DoorwayClearer.base(below))) floor++;
            }
            if (floor >= MIN_REGION) rooms.add(room);
        }
        return rooms;
    }

    /** True when the plan puts something over this spot: indoors, not on the roof. */
    private boolean covered(int x, int y, int z) {
        for (int yy = y + 2; yy <= y + COVER_SCAN; yy++) {
            String s = blocks.get(x + "," + yy + "," + z);
            if (s != null && !isAir(s)) return true;
        }
        return false;
    }

    // ---- cheapest way in -----------------------------------------------

    /**
     * Furnishings to remove on the cheapest path from the reached area into
     * any of the rooms, or null when every room costs more than allowed.
     */
    private List<int[]> cheapestPath(Set<Long> reached, List<Set<Long>> rooms) {
        Set<Long> goal = new HashSet<>();
        for (Set<Long> r : rooms) goal.addAll(r);
        Map<Long, Integer> dist = new HashMap<>();
        Map<Long, Long> prev = new HashMap<>();
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
        for (long k : reached) {
            dist.put(k, 0);
            pq.add(new long[]{k, 0});
        }
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            long k = top[0];
            int d = (int) top[1];
            if (d > dist.getOrDefault(k, Integer.MAX_VALUE)) continue;
            if (goal.contains(k)) return d == 0 ? null : obstaclesOn(k, prev);
            int[] p = unpack(k);
            for (int[] n : moves(p, true)) {
                int nd = d + cost(n);
                if (nd > MAX_PATH_COST) continue;
                long nk = pack(n[0], n[1], n[2]);
                if (nd < dist.getOrDefault(nk, Integer.MAX_VALUE)) {
                    dist.put(nk, nd);
                    prev.put(nk, k);
                    pq.add(new long[]{nk, nd});
                }
            }
        }
        return null;
    }

    private int cost(int[] p) {
        int c = 0;
        String feet = at(p[0], p[1], p[2]), head = at(p[0], p[1] + 1, p[2]);
        if (!isLadder(feet) && !open(feet, false)) c += weight(feet);
        if (!open(head, false)) c += weight(head);
        return c;
    }

    /**
     * What removing a furnishing costs. The first fish-market run tied a
     * bookshelf against the office bed and took the bed; something a player
     * uses -- a bed, a chest, a workstation -- should lose to decor every time.
     */
    static int weight(String spec) {
        String b = DoorwayClearer.base(spec);
        if (b.endsWith("_bed")) return 4; // and its other half goes with it
        if (b.equals("chest") || b.equals("trapped_chest") || b.endsWith("shulker_box")
                || b.equals("crafting_table") || b.equals("furnace") || b.equals("smoker")
                || b.equals("blast_furnace") || b.endsWith("anvil") || b.equals("enchanting_table")
                || b.equals("brewing_stand") || b.equals("lectern") || b.endsWith("_table")
                || b.equals("loom") || b.equals("stonecutter") || b.equals("grindstone")
                || b.equals("cauldron") || b.equals("composter") || b.equals("bell")) {
            return 3;
        }
        return 1;
    }

    private List<int[]> obstaclesOn(long end, Map<Long, Long> prev) {
        List<int[]> out = new ArrayList<>();
        Long k = end;
        while (k != null) {
            int[] p = unpack(k);
            for (int dy = 0; dy <= 1; dy++) {
                String s = at(p[0], p[1] + dy, p[2]);
                if (s != null && !open(s, false) && !(dy == 0 && isLadder(s))) {
                    out.add(new int[]{p[0], p[1] + dy, p[2]});
                }
            }
            k = prev.get(k);
        }
        return out;
    }

    private void remove(int[] c, List<String> removed) {
        String key = c[0] + "," + c[1] + "," + c[2];
        String spec = blocks.get(key);
        if (spec == null || isAir(spec)) return;
        blocks.put(key, "air");
        removed.add(DoorwayClearer.base(spec) + " at " + key);
        // Half a bed or half a tall plant left behind is worse than none.
        String b = DoorwayClearer.base(spec);
        if (b.endsWith("_bed")) {
            for (int[] d : DIRS) {
                String nk = (c[0] + d[0]) + "," + c[1] + "," + (c[2] + d[1]);
                String ns = blocks.get(nk);
                if (ns != null && DoorwayClearer.base(ns).equals(b)) {
                    blocks.put(nk, "air");
                    removed.add(b + " at " + nk);
                }
            }
        }
        for (int dy : new int[]{1, -1}) {
            String vk = c[0] + "," + (c[1] + dy) + "," + c[2];
            String vs = blocks.get(vk);
            if (vs != null && DoorwayClearer.base(vs).equals(b)
                    && vs.toLowerCase(Locale.ROOT).contains("half=")) {
                blocks.put(vk, "air");
                removed.add(b + " at " + vk);
            }
        }
    }

    // ---- plumbing ------------------------------------------------------

    /** The planned spec at a cell; below the origin, unplanned cells are ground. */
    private String at(int x, int y, int z) {
        String s = blocks.get(x + "," + y + "," + z);
        if (s == null && y < 0) return "dirt";
        return s;
    }

    private boolean inBox(int x, int y, int z) {
        return x >= minX && x <= maxX && y > minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    private static boolean isAir(String spec) {
        String b = DoorwayClearer.base(spec);
        return b.equals("air") || b.equals("cave_air");
    }

    private static int[] parse(String key) {
        String[] p = key.split(",");
        return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
    }

    private static int[] unpack(long k) {
        return new int[]{signed((int) (k >>> 40) & 0xFFFFF), signed((int) (k >>> 20) & 0xFFFFF), signed((int) k & 0xFFFFF)};
    }

    private static int signed(int v) {
        return (v & 0x80000) != 0 ? v | 0xFFF00000 : v;
    }
}
