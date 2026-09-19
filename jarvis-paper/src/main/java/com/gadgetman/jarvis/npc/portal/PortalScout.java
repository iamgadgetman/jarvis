package com.gadgetman.jarvis.npc.portal;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.npc.DepositManager;
import com.gadgetman.jarvis.npc.JarvisNPC;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
public class PortalScout implements Listener {

    private final Jarvis plugin;

    /** Where the last sweep was centred, so standing still costs nothing. */
    private final Map<UUID, Location> lastSweep = new ConcurrentHashMap<>();

    private BukkitTask task;

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

    public PortalScout(Jarvis plugin) {
        this.plugin = plugin;
        readConfig();
    }

    private void readConfig() {
        var config = plugin.getConfig();
        this.scout = config.getBoolean("portals.scout", true);
        this.scanRadius = Math.max(8, config.getInt("portals.scan-radius", 48));
        this.yBand = Math.max(8, config.getInt("portals.scan-height", 40));
        this.intervalTicks = Math.max(100L, config.getLong("portals.scan-interval-seconds", 20L) * 20L);
        this.limit = Math.max(1, config.getInt("portals.remember", PortalSighting.DEFAULT_LIMIT));
        this.announce = config.getBoolean("portals.announce", true);
    }

    public void start() {
        if (!scout || plugin.getJarvisNPC() == null) return;
        task = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId()) == null) continue;
                    sweep(player);
                }
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);
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

    public void forget(Player player) {
        lastSweep.remove(player.getUniqueId());
    }

    // ==================== NOTICING ====================

    /**
     * The strongest sighting there is: you just walked into one. No scan can
     * beat being told, and this costs a single map write.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        Location from = event.getFrom();
        if (from == null || from.getWorld() == null) return;
        record(event.getPlayer(), from, false);
    }

    /** One async sweep of the loaded chunks around a player. */
    private void sweep(Player player) {
        Location centre = player.getLocation();
        World world = centre.getWorld();
        if (world == null) return;

        Location last = lastSweep.get(player.getUniqueId());
        if (last != null && last.getWorld() == world
                && last.distance(centre) < RESWEEP_DISTANCE) {
            return;   // he has already looked from here
        }
        lastSweep.put(player.getUniqueId(), centre.clone());

        scanAsync(centre, sightings -> {
            for (Location found : sightings) {
                record(player, found, true);
            }
        });
    }

    /**
     * Portal blocks in the loaded chunks around a point, collapsed to one
     * location per structure.
     *
     * <p>Snapshots are taken on the main thread and read off it, the way the ore
     * search does — a chunk cannot be touched asynchronously, but a snapshot of
     * one can. This scans a wide, shallow box rather than the ore search's cube:
     * a portal is a landmark you walk past, not a vein you dig down to.
     */
    private void scanAsync(Location centre, java.util.function.Consumer<List<Location>> callback) {
        World world = centre.getWorld();
        int cx = centre.getBlockX(), cy = centre.getBlockY(), cz = centre.getBlockZ();
        int r = scanRadius;
        int minY = Math.max(world.getMinHeight(), cy - yBand);
        int maxY = Math.min(world.getMaxHeight() - 1, cy + yBand);

        Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        for (int chunkX = (cx - r) >> 4; chunkX <= (cx + r) >> 4; chunkX++) {
            for (int chunkZ = (cz - r) >> 4; chunkZ <= (cz + r) >> 4; chunkZ++) {
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    snapshots.put((((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL),
                            world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false));
                }
            }
        }
        if (snapshots.isEmpty()) {
            callback.accept(List.of());
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<int[]> hits = new ArrayList<>();
            outer:
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    ChunkSnapshot snap = snapshots.get((((long) (x >> 4)) << 32) | ((z >> 4) & 0xFFFFFFFFL));
                    if (snap == null) continue;
                    int lx = x & 15, lz = z & 15;
                    for (int y = minY; y <= maxY; y++) {
                        if (snap.getBlockType(lx, y, lz) != Material.NETHER_PORTAL) continue;
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
                PortalSighting sighting = new PortalSighting(world.getName(), hit[0], hit[1], hit[2], now);
                if (PortalSighting.isNew(distinct, sighting, PortalSighting.MERGE_RADIUS)) {
                    distinct.add(sighting);
                }
            }

            List<Location> found = new ArrayList<>();
            for (PortalSighting sighting : distinct) {
                found.add(new Location(world, sighting.x(), sighting.y(), sighting.z()));
            }
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(found));
        });
    }

    /** Write a sighting down, and mention it if it is news. */
    private void record(Player player, Location where, boolean fromSweep) {
        DepositManager data = data();
        if (data == null || where.getWorld() == null) return;

        PortalSighting sighting = new PortalSighting(where.getWorld().getName(),
                where.getBlockX(), where.getBlockY(), where.getBlockZ(),
                System.currentTimeMillis());
        boolean isNew = data.rememberPortal(player.getUniqueId(), sighting, limit);

        if (isNew && fromSweep && this.announce && player.isOnline()) {
            double distance = player.getLocation().distance(where);
            plugin.getJarvisNPC().speakTo(player, "A portal, sir — " + (int) distance
                    + " metres " + bearing(player.getLocation(), where) + ". Noted.");
        }
    }

    // ==================== ANSWERING ====================

    /** Everything he knows in the player's current world, nearest first. */
    public List<PortalSighting> known(Player player) {
        DepositManager data = data();
        if (data == null) return List.of();
        String world = player.getWorld().getName();
        List<PortalSighting> here = new ArrayList<>();
        for (PortalSighting sighting : data.getPortals(player.getUniqueId())) {
            if (sighting.world().equals(world)) here.add(sighting);
        }
        Location at = player.getLocation();
        here.sort((a, b) -> Double.compare(
                a.distanceTo(at.getBlockX(), at.getBlockY(), at.getBlockZ()),
                b.distanceTo(at.getBlockX(), at.getBlockY(), at.getBlockZ())));
        return here;
    }

    /** The nearest portal he knows of in this world, or null. */
    public PortalSighting nearest(Player player) {
        Location at = player.getLocation();
        return PortalSighting.nearest(known(player), player.getWorld().getName(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    /** Mark the spot the player is standing on, whether or not a scan found it. */
    public boolean mark(Player player) {
        DepositManager data = data();
        if (data == null) return false;
        Location at = player.getLocation();
        return data.rememberPortal(player.getUniqueId(),
                new PortalSighting(at.getWorld().getName(), at.getBlockX(), at.getBlockY(),
                        at.getBlockZ(), System.currentTimeMillis()),
                limit);
    }

    public int forgetAll(Player player) {
        DepositManager data = data();
        return data == null ? 0 : data.forgetPortals(player.getUniqueId());
    }

    /** Eight-point bearing from one place to another, as a word. */
    public static String bearing(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) return "right here";
        // Minecraft axes: +X east, +Z south.
        double angle = Math.toDegrees(Math.atan2(dx, -dz));   // 0 = north, clockwise
        String[] points = {"north", "north-east", "east", "south-east",
                           "south", "south-west", "west", "north-west"};
        int index = (int) Math.floor(((angle + 360) % 360) / 45.0 + 0.5) % 8;
        return points[index];
    }

    private DepositManager data() {
        JarvisNPC npc = plugin.getJarvisNPC();
        return npc == null ? null : npc.getDepositManager();
    }
}
