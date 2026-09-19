package com.gadgetman.jarvis.fabric.butler;

import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.nav.Terrain;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * The pathfinder's view of a server level. A block is solid when it has a
 * collision shape and is not something a body passes through on purpose
 * (a ladder, a door). Doors and fence gates that open by hand count as
 * doors whether open or shut; the driver only uses the shut ones.
 */
public final class LevelTerrain implements Terrain {

    private final ServerLevel level;

    public LevelTerrain(ServerLevel level) {
        this.level = level;
    }

    private static net.minecraft.core.BlockPos mc(BlockPos pos) {
        return new net.minecraft.core.BlockPos(pos.x(), pos.y(), pos.z());
    }

    public BlockState state(BlockPos pos) {
        return level.getBlockState(mc(pos));
    }

    @Override
    public boolean isSolid(BlockPos pos) {
        net.minecraft.core.BlockPos at = mc(pos);
        BlockState s = level.getBlockState(at);
        if (s.isAir() || isDoorState(s) || s.is(BlockTags.CLIMBABLE)) return false;
        return !s.getCollisionShape(level, at).isEmpty();
    }

    @Override
    public boolean isPassable(BlockPos pos) {
        net.minecraft.core.BlockPos at = mc(pos);
        BlockState s = level.getBlockState(at);
        if (s.isAir() || s.is(BlockTags.CLIMBABLE)) return true;
        if (isDoorState(s)) return false;   // a door is "roomy" through isDoor, not here
        return s.getCollisionShape(level, at).isEmpty();
    }

    @Override
    public boolean isLiquid(BlockPos pos) {
        return !level.getFluidState(mc(pos)).isEmpty();
    }

    @Override
    public boolean isClimbable(BlockPos pos) {
        return level.getBlockState(mc(pos)).is(BlockTags.CLIMBABLE);
    }

    @Override
    public boolean isDoor(BlockPos pos) {
        return isDoorState(level.getBlockState(mc(pos)));
    }

    /** A shut door the driver should open before walking through. */
    public boolean isShutDoor(BlockPos pos) {
        BlockState s = level.getBlockState(mc(pos));
        if (s.getBlock() instanceof DoorBlock) return !s.getValue(DoorBlock.OPEN);
        if (s.getBlock() instanceof FenceGateBlock) return !s.getValue(FenceGateBlock.OPEN);
        return false;
    }

    private static boolean isDoorState(BlockState s) {
        if (s.getBlock() instanceof DoorBlock door) return door.type().canOpenByHand();
        if (s.getBlock() instanceof FenceGateBlock) return true;
        return false;
    }

    @Override
    public boolean isHazard(BlockPos pos) {
        net.minecraft.core.BlockPos at = mc(pos);
        FluidState fluid = level.getFluidState(at);
        if (fluid.is(FluidTags.LAVA)) return true;
        BlockState s = level.getBlockState(at);
        return s.is(BlockTags.FIRE) || s.is(BlockTags.CAMPFIRES)
                || s.is(Blocks.CACTUS) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.SWEET_BERRY_BUSH)
                || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.POINTED_DRIPSTONE);
    }

    @Override
    public int minY() {
        return level.getMinY();
    }

    @Override
    public int maxY() {
        return level.getMaxY();
    }
}
