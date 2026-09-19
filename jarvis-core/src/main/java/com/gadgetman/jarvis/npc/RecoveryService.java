package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.EntityKind;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.platform.events.DeathEvent;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RecoveryService (v0.6.0) - "Shall I retrieve your effects, sir?"
 *
 * When the player dies while Jarvis is summoned, he remembers the spot and
 * offers to fetch the drops. On "/jarvis recover" (or auto, if configured)
 * he travels to the death point, sweeps up everything on the ground there,
 * comes back, and hands it all over. Death drops despawn after ~5 minutes,
 * so he does not dawdle.
 */
public class RecoveryService {

    private final ButlerHost host;

    private record DeathRecord(Site where, long timestamp) { }

    private final Map<UUID, DeathRecord> deathPoints = new ConcurrentHashMap<>();

    private final boolean autoRecover;
    private final double maxDistance;

    private static final long DEATH_MEMORY_MS = 8 * 60_000;   // Longer than despawn, for the message
    private static final double SITE_RADIUS = 7.0;
    private static final int COLLECT_TIMEOUT_TICKS = 60;      // 60s at 20-tick loop
    private static final int STALL_HOP_TICKS = 8;             // 8s stalled -> 8-block hop
    private static final double HOP_DISTANCE = 8.0;

    public RecoveryService(ButlerHost host) {
        this.host = host;
        this.autoRecover = host.config().getBoolean("steward.recovery.auto", false);
        this.maxDistance = host.config().getDouble("steward.recovery.max-distance", 192.0);
        host.platform().events().on(DeathEvent.class, this::onDeath);
    }

    // ==================== DEATH TRACKING ====================

    private void onDeath(DeathEvent event) {
        Owner player = event.who();
        if (!host.butler(player).isSpawned()) return;
        if (event.drops().isEmpty() && event.keptInventory()) return;

        deathPoints.put(player.id(), new DeathRecord(event.where(), System.currentTimeMillis()));

        // Offer (or act) shortly after respawn
        host.platform().scheduler().later(60L, () -> {
            if (!player.isOnline() || !deathPoints.containsKey(player.id())) return;
            if (autoRecover) {
                recover(player);
            } else {
                host.say(player, "My condolences, sir. Shall I retrieve your effects? "
                        + "Say '/jarvis recover' — the clock is ticking on those drops.");
            }
        });
    }

    // ==================== RECOVERY RUN ====================

    public void recover(Owner player) {
        Butler butler = host.butler(player);
        if (!butler.isSpawned()) {
            host.say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }

        DeathRecord record = deathPoints.get(player.id());
        if (record == null || System.currentTimeMillis() - record.timestamp() > DEATH_MEMORY_MS) {
            host.say(player, "I have no death site on record, sir. Long may that continue.");
            return;
        }

        Site site = record.where();
        World world = butler.world().orElse(null);
        Vec3 npcLoc = butler.pos();
        if (world == null || !site.world().id().equals(world.id())) {
            host.say(player, "Your effects are in another world, sir — beyond even my reach.");
            return;
        }
        Vec3 sitePos = site.pos();
        if (npcLoc.distance(sitePos) > maxDistance) {
            host.say(player, "That's " + (int) npcLoc.distance(sitePos)
                    + " blocks away, sir — beyond my configured range. My apologies.");
            return;
        }

        deathPoints.remove(player.id());
        host.stopTask(player);
        butler.applyNavigationDefaults(null);
        host.say(player, "On my way, sir. Guard duty and salvage in one trip.");

        Task task = host.platform().scheduler().every(10L, 20L, new java.util.function.Consumer<Task>() {
            int phase = 0;              // 0 = travel out, 1 = collect, 2 = return
            int collectTicks = 0;
            int stalled = 0;
            Vec3 lastPos = null;

            @Override
            public void accept(Task self) {
                if (!butler.isSpawned() || !player.isOnline()) {
                    self.cancel();
                    host.taskDone(player, self);
                    return;
                }

                Vec3 loc = butler.pos();
                // v0.8.0: on a recovery run EVERYTHING is the player's stuff —
                // including cobblestone and dirt the junk filter normally skips.
                host.pickupNearbyItems(player, loc, true);

                // Stall watchdog (shared by travel phases)
                if (lastPos != null && loc.distance(lastPos) < 0.2) stalled++;
                else stalled = 0;
                lastPos = loc;

                switch (phase) {
                    case 0 -> { // Travel to the death site
                        if (loc.distance(sitePos) <= SITE_RADIUS - 2) {
                            butler.cancelNavigation();
                            phase = 1;
                            host.sayQuiet(player, "At the site. Collecting your effects.");
                            return;
                        }
                        travelToward(loc, sitePos);
                    }
                    case 1 -> { // Collect everything on the ground
                        collectTicks++;
                        Entity nearest = null;
                        double best = Double.MAX_VALUE;
                        for (Entity e : butler.nearbyEntities(SITE_RADIUS, 5, SITE_RADIUS)) {
                            if (e.kind() != EntityKind.ITEM) continue;
                            double d = loc.distance(e.pos());
                            if (d < best) { best = d; nearest = e; }
                        }
                        if (nearest == null || collectTicks > COLLECT_TIMEOUT_TICKS) {
                            phase = 2;
                            host.sayQuiet(player, "Site cleared. Returning to you.");
                        } else if (!butler.isNavigating() && best > 2.0) {
                            // Wander to the nearest item entity
                            butler.navigateTo(nearest.pos());
                        }
                    }
                    case 2 -> { // Return to the player and hand everything over
                        if (!player.world().equals(world.id())) {
                            // Player moved worlds — hold the goods
                            self.cancel();
                            host.taskDone(player, self);
                            host.say(player, "You've changed worlds, sir. I'll hold your effects — "
                                    + "/jarvis loot when you want them.");
                            return;
                        }
                        Vec3 playerLoc = player.pos();
                        if (loc.distance(playerLoc) <= 3.0) {
                            self.cancel();
                            host.taskDone(player, self);
                            handOver(player);
                            return;
                        }
                        travelToward(loc, playerLoc);
                    }
                }
            }

            private void travelToward(Vec3 from, Vec3 to) {
                if (!butler.isNavigating()) {
                    butler.navigateTo(to);
                }
                if (stalled > STALL_HOP_TICKS) {
                    // Butler-rules hop: a short, visible bound toward the target
                    butler.cancelNavigation();
                    Vec3 dir = to.subtract(from);
                    double dist = dir.length();
                    Vec3 hop;
                    if (dist <= HOP_DISTANCE) {
                        hop = to;
                    } else {
                        Vec3 p = from.add(dir.normalize().scale(HOP_DISTANCE));
                        double y = world.highestY(p.block().x(), p.block().z()) + 1;
                        // Underground target? keep current Y band instead of surfacing
                        if (to.y() < from.y() - 4 || from.y() - y > 8) {
                            y = from.y();
                        }
                        hop = host.findSafeNear(world, new Vec3(p.x(), y, p.z()));
                    }
                    butler.teleport(hop);
                    stalled = 0;
                }
            }
        });
        host.registerTask(player, task);
    }

    /** Give the player everything in Jarvis's bags (drops overflow at their feet). */
    private void handOver(Owner player) {
        Butler butler = host.butler(player);
        List<Item> contents = new ArrayList<>(butler.inventory());
        int returned = 0;

        // v0.8.0: slots 1+ are ALL the player's — including their own diamond
        // tools (the old kit filter quietly confiscated recovered tools).
        for (int i = 1; i < Math.min(36, contents.size()); i++) {
            Item item = contents.get(i);
            if (item.isEmpty()) continue;

            returned += item.count();
            player.give(item);
            contents.set(i, Item.EMPTY);
        }
        butler.setInventory(contents);

        if (returned > 0) {
            host.say(player, "Your effects, sir — " + returned
                    + " items recovered, along with everything else I was carrying.");
            Entertainer.celebrate(host, player);
        } else {
            host.say(player, "I'm afraid there was nothing left to recover, sir. The clock won.");
        }
    }

    public boolean hasDeathPoint(Owner player) {
        DeathRecord r = deathPoints.get(player.id());
        return r != null && System.currentTimeMillis() - r.timestamp() <= DEATH_MEMORY_MS;
    }
}
