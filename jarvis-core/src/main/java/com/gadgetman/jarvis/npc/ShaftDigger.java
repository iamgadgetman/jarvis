package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Facing;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.recovery.TaskFailure;

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

    private final ButlerHost host;
    private final Owner player;
    private final Butler butler;

    private final int requestedDepth;
    private final boolean placeLadders;
    private final boolean placeTorches;
    private final int torchInterval;

    /** Kept clear of bedrock; the branch miner uses the same margin. */
    private static final int FLOOR_MARGIN = 5;

    private World world;
    private BlockPos cursor;
    private int stopY;
    private int dug;
    private int sealed;
    private boolean breaking;

    public ShaftDigger(ButlerHost host, Owner player, int requestedDepth) {
        this.host = host;
        this.player = player;
        this.butler = host.butler(player);
        Config cfg = host.config();
        this.requestedDepth = requestedDepth > 0
                ? requestedDepth : cfg.getInt("mining.shaft.default-depth", 20);
        this.placeLadders = cfg.getBoolean("mining.shaft.place-ladders", true);
        this.placeTorches = cfg.getBoolean("mining.shaft.place-torches", true);
        this.torchInterval = Math.max(2, cfg.getInt("mining.shaft.torch-interval", 6));
    }

    public void start() {
        Vec3 anchor = host.currentLocation(player);
        world = butler.world().orElse(null);
        if (anchor == null || world == null) {
            host.say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }

        this.cursor = anchor.block();
        int floor = world.minY() + FLOOR_MARGIN;
        this.stopY = Math.max(cursor.y() - requestedDepth, floor);

        int achievable = cursor.y() - stopY;
        if (achievable <= 0) {
            host.say(player, "We are already as deep as I am willing to go, sir — "
                    + "bedrock is " + (cursor.y() - world.minY()) + " below.");
            return;
        }
        if (achievable < requestedDepth) {
            host.say(player, "Bedrock will stop us at " + achievable
                    + " rather than " + requestedDepth + ", sir. Proceeding regardless.");
        } else {
            host.say(player, "Digging down " + achievable + ", sir. Mind the drop.");
        }

        Task task = host.platform().scheduler().every(0L, 5L, self -> {
            if (!butler.isSpawned() || !player.isOnline()) {
                self.cancel();
                host.taskDone(player, self);
                return;
            }
            tick(self);
        });
        host.registerTask(player, task);
    }

    private void tick(Task self) {
        if (breaking) return;

        if (cursor.y() <= stopY) {
            finish(self);
            return;
        }

        BlockPos below = cursor.below();

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
                    .where(new Site(world, cursor.center()))
                    .state("depth reached", dug + " blocks, now at y=" + cursor.y())
                    .state("target depth", "y=" + stopY)
                    .state("pockets sealed so far", sealed)
                    .build());
            stopQuietly(self);
            return;
        }
        String belowId = world.block(below).id();
        if (Blocks.isFluid(belowId)) {
            world.setBlock(below, BlockState.of(Ids.COBBLESTONE));
            sealed++;
            return;
        }
        if (Blocks.isPassable(world, below)) {
            descend();
            return;
        }
        if (!Blocks.canDig(belowId)) {
            host.say(player, "Bedrock, sir. That is as far as anyone digs.");
            finish(self);
            return;
        }

        breaking = true;
        host.breakBlockProperly(player, world, below, success -> {
            breaking = false;
            if (success) {
                dug++;
                descend();
            } else {
                host.reportFailure(TaskFailure.of(player, "dig_down")
                        .step("breaking the block underfoot at y=" + (cursor.y() - 1))
                        .reason("the break did not complete — the block is protected, or something "
                                + "changed it mid-swing")
                        .say("That block will not yield, sir. Stopping." + progressNote())
                        .where(new Site(world, cursor.center()))
                        .state("block", Blocks.pretty(world.block(below).id()))
                        .state("depth reached", dug + " blocks, now at y=" + cursor.y())
                        .state("tool", host.describeHeldTool(player))
                        .build());
                stopQuietly(self);
            }
        });
    }

    /** Step into the cleared block and line the walls behind us. */
    private void descend() {
        cursor = cursor.below();
        butler.teleport(cursor.standing());
        host.pickupNearbyItems(player, cursor.standing());
        line(cursor);
    }

    private void line(BlockPos at) {
        if (placeLadders) {
            BlockPos wall = at.side(Facing.NORTH);   // north face
            if (world.isSolid(wall) && world.block(at).isAir()) {
                // Back against the north wall, so the ladder faces south.
                world.setBlock(at, BlockState.of(Ids.LADDER).with("facing", Facing.SOUTH.key()));
            }
        }
        if (placeTorches && dug > 0 && dug % torchInterval == 0) {
            BlockPos side = at.side(Facing.EAST);
            BlockPos anchor = at.offset(2, 0, 0);
            if (world.block(side).isAir() && world.isSolid(anchor)) {
                world.setBlock(side, BlockState.of(Ids.WALL_TORCH).with("facing", Facing.EAST.key()));
            }
        }
    }

    /**
     * Cobblestone over any fluid touching the block we are about to remove.
     *
     * @return false when the pocket is too large to be worth sealing
     */
    private boolean sealNeighbours(BlockPos target) {
        int found = 0;
        for (Facing face : new Facing[]{Facing.NORTH, Facing.EAST, Facing.SOUTH, Facing.WEST, Facing.DOWN}) {
            BlockPos n = target.side(face);
            if (Blocks.isFluid(world, n)) {
                if (++found > 4) return false;
                world.setBlock(n, BlockState.of(Ids.COBBLESTONE));
                sealed++;
            }
        }
        return true;
    }

    private void finish(Task self) {
        stopQuietly(self);
        String note = sealed > 0 ? " Sealed " + sealed + " fluid pocket(s) on the way." : "";
        host.say(player, "Shaft complete, sir — " + dug + " blocks down to y="
                + cursor.y() + "." + note
                + (placeLadders ? " There are ladders, should you wish to return." : ""));
    }

    /**
     * End the shaft without the completion line. Used where self-explain is
     * about to speak: a diagnosis followed two seconds later by "Shaft
     * complete, sir" reads as though he did not notice.
     */
    private void stopQuietly(Task self) {
        self.cancel();
        host.taskDone(player, self);
    }

    /** How far he got, folded into the line he says when the shaft stops early. */
    private String progressNote() {
        return " " + dug + " blocks down, to y=" + cursor.y() + "."
                + (sealed > 0 ? " Sealed " + sealed + " fluid pocket(s) on the way." : "");
    }
}
