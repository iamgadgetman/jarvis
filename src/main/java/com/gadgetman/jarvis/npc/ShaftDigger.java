package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.npc.provider.INPCProvider;
import com.gadgetman.jarvis.recovery.TaskFailure;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Digs a vertical shaft, ladder-lined and torch-lit.
 *
 * <p>"Dig down twenty blocks" had nowhere to go before this. Anything with
 * "mine" or "dig" in it routed to ore-seeking or to the branch miner, and the
 * branch miner's whole purpose is to reach a target depth and tunnel sideways
 * — asked from below that depth it correctly does nothing but start galleries,
 * which reads as it ignoring you.
 *
 * <p>A bare hole is a trap, so the shaft is lined as it goes: ladders on one
 * wall the whole way, torches every few blocks on another. Fluids are sealed
 * before the block beside them is opened, the same order the branch miner
 * uses, because opening first is how you meet lava.
 */
public class ShaftDigger {

    private final Jarvis plugin;
    private final JarvisNPC host;
    private final Player player;
    private final INPCProvider provider;

    private final int requestedDepth;
    private final boolean placeLadders;
    private final boolean placeTorches;
    private final int torchInterval;

    /** Kept clear of bedrock; the branch miner uses the same margin. */
    private static final int FLOOR_MARGIN = 5;

    private Location cursor;
    private int stopY;
    private int dug;
    private int sealed;
    private boolean breaking;

    public ShaftDigger(Jarvis plugin, JarvisNPC host, Player player, int requestedDepth) {
        this.plugin = plugin;
        this.host = host;
        this.player = player;
        this.provider = host.getProvider();
        FileConfiguration cfg = plugin.getConfig();
        this.requestedDepth = requestedDepth > 0
                ? requestedDepth : cfg.getInt("mining.shaft.default-depth", 20);
        this.placeLadders = cfg.getBoolean("mining.shaft.place-ladders", true);
        this.placeTorches = cfg.getBoolean("mining.shaft.place-torches", true);
        this.torchInterval = Math.max(2, cfg.getInt("mining.shaft.torch-interval", 6));
    }

    public void start() {
        Location anchor = host.getCurrentLocation(player);
        if (anchor == null) {
            host.say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }
        World world = anchor.getWorld();
        if (world == null) return;

        this.cursor = anchor.getBlock().getLocation();
        int floor = world.getMinHeight() + FLOOR_MARGIN;
        this.stopY = Math.max(cursor.getBlockY() - requestedDepth, floor);

        int achievable = cursor.getBlockY() - stopY;
        if (achievable <= 0) {
            host.say(player, "We are already as deep as I am willing to go, sir — "
                    + "bedrock is " + (cursor.getBlockY() - world.getMinHeight()) + " below.");
            return;
        }
        if (achievable < requestedDepth) {
            host.say(player, "Bedrock will stop us at " + achievable
                    + " rather than " + requestedDepth + ", sir. Proceeding regardless.");
        } else {
            host.say(player, "Digging down " + achievable + ", sir. Mind the drop.");
        }

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!provider.isSpawned(player) || !player.isOnline()) {
                    cancel();
                    host.taskDone(player, this);
                    return;
                }
                tick(this);
            }
        };
        task.runTaskTimer(plugin, 0L, 5L);
        host.registerTask(player, task);
    }

    private void tick(BukkitRunnable self) {
        if (breaking) return;

        if (cursor.getBlockY() <= stopY) {
            finish(self);
            return;
        }

        Block below = cursor.clone().add(0, -1, 0).getBlock();

        // Sealing before opening, not after: the block being removed may be the
        // only thing holding back a lava pocket beside it.
        if (!sealNeighbours(below)) {
            // Nothing to be done about this one — the shaft is where the player
            // put it. The diagnosis is the whole of the value here.
            host.reportFailure(TaskFailure.of(player, "dig_down")
                    .step("sealing the walls before opening the next block down")
                    .reason("more adjacent lava than the sealer will take on in one step")
                    .say("There is more lava down there than I care for, sir. Stopping here."
                            + progressNote())
                    .where(cursor)
                    .state("depth reached", dug + " blocks, now at y=" + cursor.getBlockY())
                    .state("target depth", "y=" + stopY)
                    .state("pockets sealed so far", sealed)
                    .build());
            stopQuietly(self);
            return;
        }
        if (host.isFluid(below.getType())) {
            below.setType(Material.COBBLESTONE);
            sealed++;
            return;
        }
        if (host.isPassable(below)) {
            descend();
            return;
        }
        if (!host.canDig(below)) {
            host.say(player, "Bedrock, sir. That is as far as anyone digs.");
            finish(self);
            return;
        }

        breaking = true;
        host.breakBlockProperly(player, below, success -> {
            breaking = false;
            if (success) {
                dug++;
                descend();
            } else {
                host.reportFailure(TaskFailure.of(player, "dig_down")
                        .step("breaking the block underfoot at y=" + (cursor.getBlockY() - 1))
                        .reason("the break did not complete — the block is protected, or something "
                                + "changed it mid-swing")
                        .say("That block will not yield, sir. Stopping." + progressNote())
                        .where(cursor)
                        .state("block", below.getType().name().toLowerCase())
                        .state("depth reached", dug + " blocks, now at y=" + cursor.getBlockY())
                        .state("tool", host.describeHeldTool(player))
                        .build());
                stopQuietly(self);
            }
        });
    }

    /** Step into the cleared block and line the walls behind us. */
    private void descend() {
        cursor = cursor.clone().add(0, -1, 0);
        provider.teleport(player, cursor.clone().add(0.5, 0, 0.5));
        host.pickupNearbyItems(player, cursor);
        line(cursor);
    }

    private void line(Location at) {
        if (placeLadders) {
            Block wall = at.clone().add(0, 0, -1).getBlock();   // north face
            Block ladder = at.getBlock();
            if (wall.getType().isSolid() && ladder.getType().isAir()) {
                ladder.setType(Material.LADDER, false);
                BlockData d = ladder.getBlockData();
                if (d instanceof Directional dir) {
                    dir.setFacing(BlockFace.SOUTH);             // back against the north wall
                    ladder.setBlockData(dir, false);
                }
            }
        }
        if (placeTorches && dug > 0 && dug % torchInterval == 0) {
            Block side = at.clone().add(1, 0, 0).getBlock();
            Block anchor = at.clone().add(2, 0, 0).getBlock();
            if (side.getType().isAir() && anchor.getType().isSolid()) {
                side.setType(Material.WALL_TORCH, false);
                BlockData d = side.getBlockData();
                if (d instanceof Directional dir) {
                    dir.setFacing(BlockFace.EAST);
                    side.setBlockData(dir, false);
                }
            }
        }
    }

    /**
     * Cobblestone over any fluid touching the block we are about to remove.
     *
     * @return false when the pocket is too large to be worth sealing
     */
    private boolean sealNeighbours(Block target) {
        int found = 0;
        for (BlockFace face : new BlockFace[]{
                BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
                BlockFace.WEST, BlockFace.DOWN}) {
            Block n = target.getRelative(face);
            if (host.isFluid(n.getType())) {
                if (++found > 4) return false;
                n.setType(Material.COBBLESTONE);
                sealed++;
            }
        }
        return true;
    }

    private void finish(BukkitRunnable self) {
        stopQuietly(self);
        String note = sealed > 0 ? " Sealed " + sealed + " fluid pocket(s) on the way." : "";
        host.say(player, "Shaft complete, sir — " + dug + " blocks down to y="
                + cursor.getBlockY() + "." + note
                + (placeLadders ? " There are ladders, should you wish to return." : ""));
    }

    /**
     * End the shaft without the completion line. Used where self-explain is
     * about to speak: a diagnosis followed two seconds later by "Shaft
     * complete, sir" reads as though he did not notice.
     */
    private void stopQuietly(BukkitRunnable self) {
        self.cancel();
        host.taskDone(player, self);
    }

    /** How far he got, folded into the line he says when the shaft stops early. */
    private String progressNote() {
        return " " + dug + " blocks down, to y=" + cursor.getBlockY() + "."
                + (sealed > 0 ? " Sealed " + sealed + " fluid pocket(s) on the way." : "");
    }
}
