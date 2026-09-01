package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * EscortService (v0.6.0) - "Take me home, Jarvis."
 *
 * The player sets a home point (/jarvis home set); "take me home" has Jarvis
 * lead the way there at walking pace — pausing when the player falls behind,
 * torch-lighting dark stretches as he goes. Pairs naturally with defensive
 * guarding: he leads, you follow, nothing sneaks up in the dark.
 */
public class EscortService {

    private final Jarvis plugin;
    private final JarvisNPC host;
    private final DepositManager data;

    private static final double ARRIVE_DISTANCE = 4.0;
    private static final double WAIT_FOR_PLAYER_DISTANCE = 10.0;
    private static final double LEAD_DISTANCE = 6.0;          // How far ahead he walks
    private static final int STALL_HOP_TICKS = 8;
    private static final int TORCH_LIGHT_THRESHOLD = 7;

    public EscortService(Jarvis plugin, JarvisNPC host, DepositManager data) {
        this.plugin = plugin;
        this.host = host;
        this.data = data;
    }

    public void setHome(Player player) {
        data.setHome(player, player.getLocation());
        host.say(player, "Home noted, sir. Say the word and I shall lead you back.");
    }

    public void takeHome(Player player) {
        NPC npc = host.getNPC(player);
        if (npc == null) {
            host.say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }
        Location home = data.getHome(player);
        if (home == null) {
            host.say(player, "No home on record, sir. Stand where you'd like it and say '/jarvis home set'.");
            return;
        }
        Location npcLoc = host.getCurrentLocation(npc);
        if (home.getWorld() != npcLoc.getWorld()) {
            host.say(player, "Home is in another world, sir — a portal is required first.");
            return;
        }

        host.stopTask(player);
        host.applyNavigatorDefaults(npc, null);
        host.say(player, "This way, sir. Stay close — I'll light the road.");

        BukkitRunnable task = new BukkitRunnable() {
            int stalled = 0;
            boolean waiting = false;
            boolean nagged = false;
            Location lastPos = null;

            @Override
            public void run() {
                if (!npc.isSpawned() || !player.isOnline()) {
                    cancel();
                    host.taskDone(player, this);
                    return;
                }

                Location loc = host.getCurrentLocation(npc);
                Location playerLoc = player.getLocation();
                host.pickupNearbyItems(npc, loc);

                if (playerLoc.getWorld() != loc.getWorld()) {
                    cancel();
                    host.taskDone(player, this);
                    return;
                }

                // Arrived? (Both of us, ideally)
                if (playerLoc.distance(home) <= ARRIVE_DISTANCE + 2) {
                    cancel();
                    host.taskDone(player, this);
                    npc.getNavigator().cancelNavigation();
                    host.say(player, "Home, sir. No casualties — I do like a quiet walk.");
                    return;
                }

                // Wait for a straggling employer
                double playerGap = loc.distance(playerLoc);
                if (playerGap > WAIT_FOR_PLAYER_DISTANCE) {
                    if (!waiting) {
                        waiting = true;
                        npc.getNavigator().cancelNavigation();
                        if (!nagged) {
                            nagged = true;
                            host.say(player, "Do keep up, sir.");
                        }
                    }
                    return;
                }
                waiting = false;

                // Light the road
                lightHere(loc);

                // Lead: aim for a point toward home, at most LEAD_DISTANCE ahead of the player
                if (!npc.getNavigator().isNavigating() && loc.distance(home) > ARRIVE_DISTANCE) {
                    npc.getNavigator().setTarget(home);
                }
                if (loc.distance(playerLoc) > LEAD_DISTANCE && npc.getNavigator().isNavigating()) {
                    npc.getNavigator().setPaused(true);
                } else if (npc.getNavigator().isPaused()) {
                    npc.getNavigator().setPaused(false);
                }

                // Stall watchdog
                if (lastPos != null && loc.distance(lastPos) < 0.2 && !waiting
                        && !npc.getNavigator().isPaused()) {
                    stalled++;
                } else {
                    stalled = 0;
                }
                lastPos = loc.clone();

                if (stalled > STALL_HOP_TICKS) {
                    npc.getNavigator().cancelNavigation();
                    Location near = host.findSafeNear(playerLoc);
                    npc.teleport(near, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                    stalled = 0;
                }
            }
        };

        task.runTaskTimer(plugin, 10L, 20L);
        host.registerTask(player, task);
    }

    /** Place a torch at the NPC's feet when the road is spawn-dark. */
    private void lightHere(Location loc) {
        Block block = loc.getBlock();
        if (block.getType() != Material.AIR) return;
        if (block.getLightFromBlocks() > TORCH_LIGHT_THRESHOLD) return;
        Block below = block.getRelative(BlockFace.DOWN);
        if (!below.getType().isSolid()) return;
        block.setType(Material.TORCH);
    }
}
