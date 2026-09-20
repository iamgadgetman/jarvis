package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.config.YamlConfig;
import com.gadgetman.jarvis.core.config.YamlFiles;
import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Container;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;
import com.gadgetman.jarvis.npc.portal.PortalSighting;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * DepositManager - Jarvis's pack-mule service (v0.2.0).
 *
 * Players register a deposit chest ("/jarvis chest" while looking at one);
 * Jarvis can then carry his loot to it on command ("/jarvis deposit") or
 * automatically when his bags fill up during mining. Chest locations are
 * persisted to data.yml so they survive restarts, along with the home point,
 * patrol routes and portal sightings.
 */
public class DepositManager {

    /** A stored place: the world by the name the server calls it, and a point. */
    public record Place(String world, double x, double y, double z) {

        public static Place of(Site site) {
            return new Place(site.world().name(), site.pos().x(), site.pos().y(), site.pos().z());
        }

        public static Place block(Site site) {
            BlockPos b = site.block();
            return new Place(site.world().name(), b.x(), b.y(), b.z());
        }

        public Vec3 pos() {
            return new Vec3(x, y, z);
        }

        public BlockPos blockPos() {
            return pos().block();
        }
    }

    private final Platform platform;
    private final ButlerHost host;
    private final Map<UUID, Place> chests = new ConcurrentHashMap<>();
    private final Map<UUID, Place> homes = new ConcurrentHashMap<>();
    private final Map<UUID, List<Place>> patrols = new ConcurrentHashMap<>();
    private final Map<UUID, List<PortalSighting>> portals = new ConcurrentHashMap<>();
    private final Path dataFile;

    private static final double CHEST_REACH = 2.8;
    private static final int WALK_NUDGE_TICKS = 10;   // 10s of stalled walking before nudging
    private static final double MAX_DEPOSIT_DISTANCE = 64.0;

    public DepositManager(Platform platform, ButlerHost host) {
        this.platform = platform;
        this.host = host;
        this.dataFile = platform.dataDir().resolve("data.yml");
        load();
    }

    /** The world a stored place refers to, if it is loaded. */
    public Optional<World> worldOf(Place place) {
        if (place == null) return Optional.empty();
        for (World w : platform.worlds()) {
            if (w.name().equals(place.world())) return Optional.of(w);
        }
        return platform.world(new WorldId(place.world()));
    }

    /** A stored place as a site in a loaded world. */
    public Optional<Site> siteOf(Place place) {
        return worldOf(place).map(w -> new Site(w, place.pos()));
    }

    // ==================== CHEST REGISTRY ====================

    /** Register the container the player is looking at as their deposit chest. */
    public void setChest(Owner player) {
        Optional<World> world = platform.world(player.world());
        Optional<BlockPos> target = player.targetBlock(6);
        if (world.isEmpty() || target.isEmpty() || world.get().container(target.get()).isEmpty()) {
            host.say(player, "Do look at a chest or barrel when you say that, sir.");
            return;
        }
        chests.put(player.id(), new Place(world.get().name(), target.get().x(), target.get().y(), target.get().z()));
        save();
        host.say(player, "Noted, sir. That " + Blocks.pretty(world.get().block(target.get()).id())
                + " is now where I'll deposit your spoils.");
    }

    /** The registered chest, if it is still a container in a loaded world. */
    public Optional<Site> getChest(Owner player) {
        Place place = chests.get(player.id());
        if (place == null) return Optional.empty();
        Optional<Site> site = siteOf(place);
        if (site.isEmpty()) return Optional.empty();
        if (site.get().world().container(site.get().block()).isEmpty()) return Optional.empty(); // chest was broken
        return site;
    }

    public boolean hasChest(Owner player) {
        return getChest(player).isPresent();
    }

    // ==================== HOME REGISTRY (v0.6.0) ====================

    public void setHome(Owner player, Site where) {
        homes.put(player.id(), Place.of(where));
        save();
    }

    public Optional<Site> getHome(Owner player) {
        return siteOf(homes.get(player.id()));
    }

    // ==================== PATROL ROUTES (v0.7.0) ====================

    public int addPatrolPoint(Owner player, Site where) {
        List<Place> route = patrols.computeIfAbsent(player.id(), k -> new CopyOnWriteArrayList<>());
        BlockPos b = where.block();
        route.add(new Place(where.world().name(), b.x() + 0.5, b.y(), b.z() + 0.5));
        save();
        return route.size();
    }

    public void clearPatrol(Owner player) {
        patrols.remove(player.id());
        save();
    }

    /** The route, with any point whose world is not loaded left out. */
    public List<Site> getPatrol(Owner player) {
        List<Place> route = patrols.get(player.id());
        if (route == null) return List.of();
        List<Site> valid = new ArrayList<>();
        for (Place p : route) siteOf(p).ifPresent(valid::add);
        return valid;
    }

    // ==================== DEPOSITING ====================

    /**
     * Walk to the deposit chest (or the nearest container if none is
     * registered) and empty the loot slots into it. Standalone task —
     * cancels whatever else Jarvis was doing.
     */
    public void deposit(Owner player) {
        Butler butler = host.butler(player);
        if (!butler.isSpawned()) {
            host.say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }

        Site chest = getChest(player).orElse(null);
        if (chest == null) {
            World world = butler.world().orElse(null);
            if (world != null) chest = findNearbyContainer(world, butler.pos(), 8);
        }
        if (chest == null) {
            host.say(player, "I have no chest on record, sir. Look at one and say '/jarvis chest'.");
            return;
        }
        if (host.lootSlotsUsed(player) == 0) {
            host.say(player, "My bags are already empty, sir.");
            return;
        }

        host.stopTask(player);
        host.say(player, "Delivering the goods, sir.");
        startDepositRun(player, chest, () -> {});
    }

    /**
     * The walking + dumping routine. Calls onComplete afterwards (used by
     * the branch miner to resume digging after an auto-deposit).
     */
    public void startDepositRun(Owner player, Site chest, Runnable onComplete) {
        Butler butler = host.butler(player);
        Vec3 chestCentre = chest.block().center();
        Vec3 standAt = chest.block().standing().add(0, 1, 0);
        butler.applyNavigationDefaults(null);
        butler.navigateTo(standAt, null);

        Task task = platform.scheduler().every(20L, 20L, new java.util.function.Consumer<Task>() {
            int stalled = 0;
            Vec3 lastPos = null;

            @Override
            public void accept(Task self) {
                if (!butler.isSpawned() || !player.isOnline()) {
                    self.cancel();
                    host.taskDone(player, self);
                    return;
                }

                Vec3 npcPos = butler.pos();
                double dist = npcPos.distance(chestCentre);

                if (dist <= CHEST_REACH) {
                    self.cancel();
                    host.taskDone(player, self);
                    dumpInto(player, chest);
                    onComplete.run();
                    return;
                }

                if (dist > MAX_DEPOSIT_DISTANCE) {
                    self.cancel();
                    host.taskDone(player, self);
                    host.say(player, "The chest is rather far from here, sir. I'll hold onto things for now.");
                    onComplete.run();
                    return;
                }

                // Progress watchdog
                if (lastPos != null && npcPos.distance(lastPos) < 0.2) {
                    stalled++;
                } else {
                    stalled = 0;
                }
                lastPos = npcPos;

                if (!butler.isNavigating()) {
                    butler.navigateTo(standAt, null);
                }

                if (stalled > WALK_NUDGE_TICKS) {
                    // Last resort within butler rules: short-range teleport
                    butler.cancelNavigation();
                    butler.teleport(standAt, new Look(butler.look().yaw(), 0));
                    stalled = 0;
                }
            }
        });
        host.registerTask(player, task);
    }

    /** Move everything in the loot slots (1..35) into the container. */
    private void dumpInto(Owner player, Site chest) {
        Optional<Container> container = chest.world().container(chest.block());
        if (container.isEmpty()) {
            host.say(player, "The chest appears to have vanished, sir.");
            return;
        }

        Butler butler = host.butler(player);
        List<Item> contents = new ArrayList<>(butler.inventory());
        int moved = 0, leftBehind = 0;

        // Slots 1+ all go in the chest — even diamond tools; only slot 0
        // (his hand) is the kit.
        for (int i = 1; i < Math.min(36, contents.size()); i++) {
            Item item = contents.get(i);
            if (item.isEmpty()) continue;

            List<Item> overflow = container.get().add(List.of(item));
            if (overflow.isEmpty()) {
                moved += item.count();
                contents.set(i, Item.EMPTY);
            } else {
                Item rest = overflow.get(0);
                moved += item.count() - rest.count();
                contents.set(i, rest);
                leftBehind += rest.count();
            }
        }

        butler.setInventory(contents);

        World world = chest.world();
        world.sound(chestCentre(chest), Ids.SOUND_BLOCK_CHEST_OPEN, 0.7f, 1.0f);
        platform.scheduler().later(15L,
                () -> world.sound(chestCentre(chest), Ids.SOUND_BLOCK_CHEST_CLOSE, 0.7f, 1.0f));

        if (leftBehind > 0) {
            host.say(player, "Deposited " + moved + " items, sir — the chest is full; "
                    + leftBehind + " remain with me.");
        } else {
            host.say(player, "Deposited " + moved + " items, sir. All squared away.");
        }
    }

    private static Vec3 chestCentre(Site chest) {
        return chest.block().center();
    }

    /** Find the nearest chest/barrel within radius of a point. */
    private Site findNearbyContainer(World world, Vec3 center, int radius) {
        BlockPos c = center.block();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos at = c.offset(x, y, z);
                    String t = world.block(at).id();
                    if (Ids.CHEST.equals(t) || Ids.TRAPPED_CHEST.equals(t) || Ids.BARREL.equals(t)) {
                        double d = x * x + y * y + z * z;
                        if (d < bestDistSq) {
                            bestDistSq = d;
                            best = at;
                        }
                    }
                }
            }
        }
        return best == null ? null : new Site(world, new Vec3(best.x(), best.y(), best.z()));
    }

    // ==================== PORTAL REGISTRY (v0.16.0) ====================

    /** Everything he has seen for this player, newest last. Never null. */
    public List<PortalSighting> getPortals(UUID playerId) {
        return portals.getOrDefault(playerId, List.of());
    }

    /**
     * Record a sighting. Merging and the size cap live in
     * {@link PortalSighting#remember}; this only decides whether the result was
     * worth writing to disk.
     *
     * @return true if this was a portal he did not already know
     */
    public boolean rememberPortal(UUID playerId, PortalSighting sighting, int limit) {
        List<PortalSighting> known = getPortals(playerId);
        boolean isNew = PortalSighting.isNew(known, sighting, PortalSighting.MERGE_RADIUS);
        portals.put(playerId, PortalSighting.remember(known, sighting, limit,
                PortalSighting.MERGE_RADIUS));
        // A merge only refreshes a timestamp, which is not worth a disk write on
        // every scan; a genuinely new portal is.
        if (isNew) save();
        return isNew;
    }

    public int forgetPortals(UUID playerId) {
        List<PortalSighting> gone = portals.remove(playerId);
        if (gone == null || gone.isEmpty()) return 0;
        save();
        return gone.size();
    }

    // ==================== PERSISTENCE ====================

    private void load() {
        Config yaml;
        try {
            yaml = YamlConfig.load(dataFile);
        } catch (IOException e) {
            platform.log().warn("Could not read data.yml: " + e.getMessage());
            return;
        }
        loadSection(yaml, "deposit-chests", chests, false);
        loadSection(yaml, "homes", homes, true);

        if (yaml.isSection("portals")) {
            Config portalSection = yaml.section("portals");
            for (String key : portalSection.keys()) {
                try {
                    UUID id = UUID.fromString(key);
                    List<PortalSighting> seen = new ArrayList<>();
                    Config entries = portalSection.section(key);
                    for (String idx : entries.keys()) {
                        String worldName = entries.getString(idx + ".world");
                        if (worldName == null) continue;
                        seen.add(new PortalSighting(worldName,
                                entries.getInt(idx + ".x", 0),
                                entries.getInt(idx + ".y", 0),
                                entries.getInt(idx + ".z", 0),
                                entries.getLong(idx + ".seen", 0)));
                    }
                    if (!seen.isEmpty()) portals.put(id, seen);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        if (yaml.isSection("patrols")) {
            Config patrolSection = yaml.section("patrols");
            for (String key : patrolSection.keys()) {
                try {
                    UUID id = UUID.fromString(key);
                    List<Place> route = new CopyOnWriteArrayList<>();
                    Config pts = patrolSection.section(key);
                    for (String idx : pts.keys()) {
                        String worldName = pts.getString(idx + ".world");
                        if (worldName == null) continue;
                        route.add(new Place(worldName,
                                pts.getDouble(idx + ".x", 0),
                                pts.getDouble(idx + ".y", 0),
                                pts.getDouble(idx + ".z", 0)));
                    }
                    if (!route.isEmpty()) patrols.put(id, route);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void loadSection(Config yaml, String name, Map<UUID, Place> into, boolean precise) {
        if (!yaml.isSection(name)) return;
        Config section = yaml.section(name);
        for (String key : section.keys()) {
            try {
                UUID id = UUID.fromString(key);
                String worldName = section.getString(key + ".world");
                if (worldName == null) continue;
                Place place = precise
                        ? new Place(worldName,
                            section.getDouble(key + ".x", 0),
                            section.getDouble(key + ".y", 0),
                            section.getDouble(key + ".z", 0))
                        : new Place(worldName,
                            section.getInt(key + ".x", 0),
                            section.getInt(key + ".y", 0),
                            section.getInt(key + ".z", 0));
                into.put(id, place);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void save() {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> chestMap = new LinkedHashMap<>();
        for (Map.Entry<UUID, Place> e : new HashMap<>(chests).entrySet()) {
            Place l = e.getValue();
            chestMap.put(e.getKey().toString(), place(l.world(), (int) l.x(), (int) l.y(), (int) l.z()));
        }
        if (!chestMap.isEmpty()) root.put("deposit-chests", chestMap);

        Map<String, Object> homeMap = new LinkedHashMap<>();
        for (Map.Entry<UUID, Place> e : new HashMap<>(homes).entrySet()) {
            Place l = e.getValue();
            homeMap.put(e.getKey().toString(), place(l.world(), l.x(), l.y(), l.z()));
        }
        if (!homeMap.isEmpty()) root.put("homes", homeMap);

        Map<String, Object> patrolMap = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<Place>> e : new HashMap<>(patrols).entrySet()) {
            Map<String, Object> route = new LinkedHashMap<>();
            int i = 0;
            for (Place l : e.getValue()) {
                route.put(String.valueOf(i++), place(l.world(), l.x(), l.y(), l.z()));
            }
            if (!route.isEmpty()) patrolMap.put(e.getKey().toString(), route);
        }
        if (!patrolMap.isEmpty()) root.put("patrols", patrolMap);

        Map<String, Object> portalMap = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<PortalSighting>> e : new HashMap<>(portals).entrySet()) {
            Map<String, Object> seen = new LinkedHashMap<>();
            int i = 0;
            for (PortalSighting sighting : e.getValue()) {
                Map<String, Object> one = place(sighting.world(), sighting.x(), sighting.y(), sighting.z());
                one.put("seen", sighting.seenAt());
                seen.put(String.valueOf(i++), one);
            }
            if (!seen.isEmpty()) portalMap.put(e.getKey().toString(), seen);
        }
        if (!portalMap.isEmpty()) root.put("portals", portalMap);

        try {
            YamlFiles.write(dataFile, root);
        } catch (IOException e) {
            platform.log().warn("Could not save data.yml: " + e.getMessage());
        }
    }

    private static Map<String, Object> place(String world, Object x, Object y, Object z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", world);
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        return m;
    }
}
