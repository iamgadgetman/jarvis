package com.gadgetman.jarvis.npc.portal;

import com.gadgetman.jarvis.core.platform.BlockScan;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.platform.events.PortalEvent;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.npc.ButlerHost;
import com.gadgetman.jarvis.npc.DepositManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Finding nether portals (v0.16.0).
 *
 * <p>Three things, in the order they are worth having:
 *
 * <ul>
 *   <li><b>He notices them.</b> While he is out with you, he sweeps for portal
 *       blocks and writes down what he finds. This is the only part that costs
 *       anything, and it is bounded: one async pass over loaded chunks, and
 *       only when you have actually moved since the last one.</li>
 *   <li><b>He remembers them.</b> Sightings persist beside your home and patrol
 *       routes in {@code data.yml}, merged so a frame is one portal rather than
 *       six, and capped so the file cannot grow forever.</li>
 *   <li><b>He can do the arithmetic.</b> {@link PortalLink} answers where a
 *       portal comes out without looking at anything at all — which is just as
 *       well, because that is the one question a scan can never answer.</li>
 * </ul>
 *
 * <p><b>What he cannot do:</b> see into unloaded chunks. A plugin reads the
 * world the server has in memory, so a sweep reaches about as far as you can
 * see and no further; the honest phrasing to a player is "none nearby", never
 * "there are none". Nor can he walk you through a portal — Citizens NPCs do not
 * change dimension with you, so he leads you <i>to</i> one and no further.
 */
public class PortalScout {

    private final Platform platform;
    private final ButlerHost host;

    /** Where the last sweep was centred, so standing still costs nothing. */
    private final Map<UUID, Site> lastSweep = new ConcurrentHashMap<>();

    private Task task;

    private boolean scout;
    private int scanRadius;
    private int yBand;
    private long intervalTicks;
    private int limit;
    private boolean announce;

    /** Far enough that a fresh sweep might see something the last one did not. */
    private static final double RESWEEP_DISTANCE = 24.0;
    /** Stop collecting raw hits well before a large frame fills memory. */
    private static final int MAX_RAW_HITS = 512;

    public PortalScout(Platform platform, ButlerHost host) {
        this.platform = platform;
        this.host = host;
        readConfig();
        platform.events().on(PortalEvent.class, this::onPortal);
    }

    private void readConfig() {
        Config config = platform.config();
        this.scout = config.getBoolean("portals.scout", true);
        this.scanRadius = Math.max(8, config.getInt("portals.scan-radius", 48));
        this.yBand = Math.max(8, config.getInt("portals.scan-height", 40));
        this.intervalTicks = Math.max(100L, config.getLong("portals.scan-interval-seconds", 20L) * 20L);
        this.limit = Math.max(1, config.getInt("portals.remember", PortalSighting.DEFAULT_LIMIT));
        this.announce = config.getBoolean("portals.announce", true);
    }

    public void start() {
        if (!scout || host == null) return;
        task = platform.scheduler().every(intervalTicks, intervalTicks, t -> {
            for (Owner player : platform.players().online()) {
                if (!host.butler(player).isSpawned()) continue;
                sweep(player);
            }
        });
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void reload() {
        shutdown();
        readConfig();
        start();
    }

    public void forget(Owner player) {
        lastSweep.remove(player.id());
    }

    // ==================== NOTICING ====================

    /**
     * The strongest sighting there is: you just walked into one. No scan can
     * beat being told, and this costs a single map write.
     */
    private void onPortal(PortalEvent event) {
        if (event.from() == null) return;
        record(event.who(), event.from(), false);
    }

    /** One async sweep of the loaded chunks around a player. */
    private void sweep(Owner player) {
        World world = platform.world(player.world()).orElse(null);
        if (world == null) return;
        Site centre = new Site(world, player.pos());

        Site last = lastSweep.get(player.id());
        if (last != null && last.sameWorld(centre) && last.distance(centre) < RESWEEP_DISTANCE) {
            return;   // he has already looked from here
        }
        lastSweep.put(player.id(), centre);

        scanAsync(centre, sightings -> {
            for (Site found : sightings) {
                record(player, found, true);
            }
        });
    }

    /**
     * Portal blocks in the loaded chunks around a point, collapsed to one
     * location per structure.
     *
     * <p>Snapshots are taken on the server thread and read off it, the way the
     * ore search does — a chunk cannot be touched asynchronously, but a copy of
     * one can. This scans a wide, shallow box rather than the ore search's
     * cube: a portal is a landmark you walk past, not a vein you dig down to.
     */
    private void scanAsync(Site centre, Consumer<List<Site>> callback) {
        World world = centre.world();
        BlockPos c = centre.block();
        int cx = c.x(), cy = c.y(), cz = c.z();
        int r = scanRadius;
        int minY = Math.max(world.minY(), cy - yBand);
        int maxY = Math.min(world.maxY(), cy + yBand);

        BlockScan scan = world.snapshot(new BlockPos(cx - r, minY, cz - r), new BlockPos(cx + r, maxY, cz + r));

        platform.scheduler().async(() -> {
            List<int[]> hits = new ArrayList<>();
            outer:
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    if (!scan.covers(new BlockPos(x, minY, z))) continue;
                    for (int y = minY; y <= maxY; y++) {
                        if (!Ids.NETHER_PORTAL.equals(scan.blockId(new BlockPos(x, y, z)))) continue;
                        hits.add(new int[]{x, y, z});
                        if (hits.size() >= MAX_RAW_HITS) break outer;
                    }
                }
            }

            // Collapse the frame's many blocks into one place per portal, using
            // the same merge rule the stored memory uses.
            List<PortalSighting> distinct = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int[] hit : hits) {
                PortalSighting sighting = new PortalSighting(world.name(), hit[0], hit[1], hit[2], now);
                if (PortalSighting.isNew(distinct, sighting, PortalSighting.MERGE_RADIUS)) {
                    distinct.add(sighting);
                }
            }

            List<Site> found = new ArrayList<>();
            for (PortalSighting sighting : distinct) {
                found.add(new Site(world, new Vec3(sighting.x(), sighting.y(), sighting.z())));
            }
            platform.scheduler().sync(() -> callback.accept(found));
        });
    }

    /** Write a sighting down, and mention it if it is news. */
    private void record(Owner player, Site where, boolean fromSweep) {
        DepositManager data = data();
        if (data == null || where == null) return;

        BlockPos at = where.block();
        PortalSighting sighting = new PortalSighting(where.world().name(),
                at.x(), at.y(), at.z(), System.currentTimeMillis());
        boolean isNew = data.rememberPortal(player.id(), sighting, limit);

        if (isNew && fromSweep && this.announce && player.isOnline()) {
            double distance = player.pos().distance(where.pos());
            host.say(player, "A portal, sir — " + (int) distance
                    + " metres " + bearing(player.pos(), where.pos()) + ". Noted.");
        }
    }

    // ==================== ANSWERING ====================

    /** Everything he knows in the player's current world, nearest first. */
    public List<PortalSighting> known(Owner player) {
        DepositManager data = data();
        if (data == null) return List.of();
        String world = worldName(player);
        List<PortalSighting> here = new ArrayList<>();
        for (PortalSighting sighting : data.getPortals(player.id())) {
            if (sighting.world().equals(world)) here.add(sighting);
        }
        BlockPos at = player.pos().block();
        here.sort((a, b) -> Double.compare(
                a.distanceTo(at.x(), at.y(), at.z()),
                b.distanceTo(at.x(), at.y(), at.z())));
        return here;
    }

    /** The nearest portal he knows of in this world, or null. */
    public PortalSighting nearest(Owner player) {
        BlockPos at = player.pos().block();
        return PortalSighting.nearest(known(player), worldName(player), at.x(), at.y(), at.z());
    }

    /** Mark the spot the player is standing on, whether or not a scan found it. */
    public boolean mark(Owner player) {
        DepositManager data = data();
        if (data == null) return false;
        BlockPos at = player.pos().block();
        return data.rememberPortal(player.id(),
                new PortalSighting(worldName(player), at.x(), at.y(), at.z(), System.currentTimeMillis()),
                limit);
    }

    public int forgetAll(Owner player) {
        DepositManager data = data();
        return data == null ? 0 : data.forgetPortals(player.id());
    }

    /** Eight-point bearing from one place to another, as a word. */
    public static String bearing(Vec3 from, Vec3 to) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        if (dx == 0 && dz == 0) return "right here";
        // Minecraft axes: +X east, +Z south.
        double angle = Math.toDegrees(Math.atan2(dx, -dz));   // 0 = north, clockwise
        String[] points = {"north", "north-east", "east", "south-east",
                           "south", "south-west", "west", "north-west"};
        int index = (int) Math.floor(((angle + 360) % 360) / 45.0 + 0.5) % 8;
        return points[index];
    }

    /** The name the server calls the player's world, which is what sightings store. */
    private String worldName(Owner player) {
        return platform.world(player.world()).map(World::name).orElse(player.world().id());
    }

    private DepositManager data() {
        return host == null ? null : host.deposits();
    }
}
