package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.npc.provider.INPCProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
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
public class RecoveryService implements Listener {

    private final Jarvis plugin;
    private final JarvisNPC host;
    private final INPCProvider provider;

    private static class DeathRecord {
        Location location;
        long timestamp;
    }

    private final Map<UUID, DeathRecord> deathPoints = new ConcurrentHashMap<>();

    private final boolean autoRecover;
    private final double maxDistance;

    private static final long DEATH_MEMORY_MS = 8 * 60_000;   // Longer than despawn, for the message
    private static final double SITE_RADIUS = 7.0;
    private static final int COLLECT_TIMEOUT_TICKS = 60;      // 60s at 20-tick loop
    private static final int STALL_HOP_TICKS = 8;             // 8s stalled -> 8-block hop
    private static final double HOP_DISTANCE = 8.0;

    public RecoveryService(Jarvis plugin, JarvisNPC host) {
        this.plugin = plugin;
        this.host = host;
        this.provider = host.getProvider();
        this.autoRecover = plugin.getConfig().getBoolean("steward.recovery.auto", false);
        this.maxDistance = plugin.getConfig().getDouble("steward.recovery.max-distance", 192.0);
    }

    // ==================== DEATH TRACKING ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!provider.isSpawned(player)) return;
        if (event.getDrops().isEmpty() && event.getKeepInventory()) return;

        DeathRecord record = new DeathRecord();
        record.location = player.getLocation().clone();
        record.timestamp = System.currentTimeMillis();
        deathPoints.put(player.getUniqueId(), record);

        // Offer (or act) shortly after respawn
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !deathPoints.containsKey(player.getUniqueId())) return;
            if (autoRecover) {
                recover(player);
            } else {
                host.say(player, "My condolences, sir. Shall I retrieve your effects? "
                        + "Say '/jarvis recover' — the clock is ticking on those drops.");
            }
        }, 60L);
    }

    // ==================== RECOVERY RUN ====================

    public void recover(Player player) {
        if (!provider.isSpawned(player)) {
            host.say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }

        DeathRecord record = deathPoints.get(player.getUniqueId());
        if (record == null || System.currentTimeMillis() - record.timestamp > DEATH_MEMORY_MS) {
            host.say(player, "I have no death site on record, sir. Long may that continue.");
            return;
        }

        Location site = record.location;
        Location npcLoc = host.getCurrentLocation(player);
        if (site.getWorld() != npcLoc.getWorld()) {
            host.say(player, "Your effects are in another world, sir — beyond even my reach.");
            return;
        }
        if (npcLoc.distance(site) > maxDistance) {
            host.say(player, "That's " + (int) npcLoc.distance(site)
                    + " blocks away, sir — beyond my configured range. My apologies.");
            return;
        }

        deathPoints.remove(player.getUniqueId());
        host.stopTask(player);
        host.applyNavigatorDefaults(player, null);
        host.say(player, "On my way, sir. Guard duty and salvage in one trip.");

        BukkitRunnable task = new BukkitRunnable() {
            int phase = 0;              // 0 = travel out, 1 = collect, 2 = return
            int collectTicks = 0;
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
                // v0.8.0: on a recovery run EVERYTHING is the player's stuff —
                // including cobblestone and dirt the junk filter normally skips.
                // (The old filter left junk on the ground and then stood there
                // 60 seconds waiting for it to be "collected".)
                host.pickupNearbyItems(player, loc, true);

                // Stall watchdog (shared by travel phases)
                if (lastPos != null && loc.distance(lastPos) < 0.2) stalled++;
                else stalled = 0;
                lastPos = loc.clone();

                switch (phase) {
                    case 0 -> { // Travel to the death site
                        if (loc.distance(site) <= SITE_RADIUS - 2) {
                            provider.cancelNavigation(player);
                            phase = 1;
                            host.sayQuiet(player, "At the site. Collecting your effects.");
                            return;
                        }
                        travelToward(loc, site);
                    }
                    case 1 -> { // Collect everything on the ground
                        collectTicks++;
                        boolean itemsLeft = false;
                        if (provider.getEntity(player) != null) {
                            for (Entity e : provider.getEntity(player).getNearbyEntities(SITE_RADIUS, 5, SITE_RADIUS)) {
                                if (e instanceof Item) { itemsLeft = true; break; }
                            }
                        }
                        if (!itemsLeft || collectTicks > COLLECT_TIMEOUT_TICKS) {
                            phase = 2;
                            host.sayQuiet(player, "Site cleared. Returning to you.");
                        } else if (!provider.isNavigating(player)) {
                            // Wander to the nearest item entity
                            Item nearest = null;
                            double best = Double.MAX_VALUE;
                            for (Entity e : provider.getEntity(player).getNearbyEntities(SITE_RADIUS, 5, SITE_RADIUS)) {
                                if (e instanceof Item item) {
                                    double d = loc.distance(item.getLocation());
                                    if (d < best) { best = d; nearest = item; }
                                }
                            }
                            if (nearest != null && best > 2.0) {
                                provider.navigateTo(player, nearest.getLocation());
                            }
                        }
                    }
                    case 2 -> { // Return to the player and hand everything over
                        Location playerLoc = player.getLocation();
                        if (loc.getWorld() != playerLoc.getWorld()) {
                            // Player moved worlds — hold the goods
                            cancel();
                            host.taskDone(player, this);
                            host.say(player, "You've changed worlds, sir. I'll hold your effects — "
                                    + "/jarvis loot when you want them.");
                            return;
                        }
                        if (loc.distance(playerLoc) <= 3.0) {
                            cancel();
                            host.taskDone(player, this);
                            handOver(player);
                            return;
                        }
                        travelToward(loc, playerLoc);
                    }
                }
            }

            private void travelToward(Location from, Location to) {
                if (!provider.isNavigating(player)) {
                    provider.navigateTo(player, to);
                }
                if (stalled > STALL_HOP_TICKS) {
                    // Butler-rules hop: a short, visible bound toward the target
                    provider.cancelNavigation(player);
                    Vector dir = to.toVector().subtract(from.toVector());
                    double dist = dir.length();
                    Location hop;
                    if (dist <= HOP_DISTANCE) {
                        hop = to.clone();
                    } else {
                        hop = from.clone().add(dir.normalize().multiply(HOP_DISTANCE));
                        hop.setY(hop.getWorld().getHighestBlockYAt(hop) + 1);
                        // Underground target? keep current Y band instead of surfacing
                        if (to.getY() < from.getY() - 4 || from.getY() - hop.getY() > 8) {
                            hop.setY(from.getY());
                        }
                        hop = host.findSafeNear(hop);
                    }
                    provider.teleport(player, hop);
                    stalled = 0;
                }
            }
        };

        task.runTaskTimer(plugin, 10L, 20L);
        host.registerTask(player, task);
    }

    /** Give the player everything in Jarvis's bags (drops overflow at their feet). */
    private void handOver(Player player) {
        ItemStack[] contents = provider.getInventoryContents(player);
        int returned = 0;

        // v0.8.0: slots 1+ are ALL the player's — including their own diamond
        // tools (the old kit filter quietly confiscated recovered tools).
        for (int i = 1; i < Math.min(36, contents.length); i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() == Material.AIR) continue;

            returned += item.getAmount();
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item.clone());
            for (ItemStack rest : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            contents[i] = null;
        }
        provider.setInventoryContents(player, contents);

        if (returned > 0) {
            host.say(player, "Your effects, sir — " + returned
                    + " items recovered, along with everything else I was carrying.");
            Entertainer.celebrate(host, player);
        } else {
            host.say(player, "I'm afraid there was nothing left to recover, sir. The clock won.");
        }
    }

    public boolean hasDeathPoint(Player player) {
        DeathRecord r = deathPoints.get(player.getUniqueId());
        return r != null && System.currentTimeMillis() - r.timestamp <= DEATH_MEMORY_MS;
    }
}
