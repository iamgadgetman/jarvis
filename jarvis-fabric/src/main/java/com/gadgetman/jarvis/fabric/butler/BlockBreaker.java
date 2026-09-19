package com.gadgetman.jarvis.fabric.butler;

import com.gadgetman.jarvis.fabric.fake.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/**
 * Breaks one block the way a player would: face it, crack it at the speed
 * the held tool allows, then let the game mode break it so the loot and the
 * tool wear are the game's own. One at a time; a new request supersedes the
 * last, which reports failure.
 */
public final class BlockBreaker {

    private static final int MAX_TICKS = 600;   // 30 s per block, whatever the tool

    private final FakePlayer player;
    private BlockPos pos;
    private double speed = 1.0;
    private float progress;
    private int ticks;
    private Consumer<Boolean> onDone;

    public BlockBreaker(FakePlayer player) {
        this.player = player;
    }

    public boolean isBreaking() {
        return pos != null;
    }

    public void start(BlockPos target, double speedModifier, Consumer<Boolean> done) {
        cancel();
        ServerLevel level = player.level();
        if (level.getBlockState(target).isAir()) {
            done.accept(true);
            return;
        }
        pos = target;
        speed = speedModifier <= 0 ? 1.0 : speedModifier;
        progress = 0;
        ticks = 0;
        onDone = done;
    }

    public void cancel() {
        if (pos == null) return;
        player.level().destroyBlockProgress(player.getId(), pos, -1);
        Consumer<Boolean> done = onDone;
        pos = null;
        onDone = null;
        if (done != null) done.accept(false);
    }

    /** Called from the fake player's tick. */
    public void tick() {
        if (pos == null) return;
        ServerLevel level = player.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            finish(true);
            return;
        }
        if (++ticks > MAX_TICKS) {
            level.destroyBlockProgress(player.getId(), pos, -1);
            finish(false);
            return;
        }
        player.actionPack().lookAt(Vec3.atCenterOf(pos));
        if (ticks % 4 == 1) player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);

        if (player.gameMode.isCreative()) {
            progress = 1;
        } else {
            progress += state.getDestroyProgress(player, level, pos) * (float) speed;
        }
        if (progress < 1) {
            level.destroyBlockProgress(player.getId(), pos, (int) (progress * 10));
            return;
        }
        level.destroyBlockProgress(player.getId(), pos, -1);
        boolean broken = player.gameMode.destroyBlock(pos);
        finish(broken || level.getBlockState(pos).isAir());
    }

    private void finish(boolean ok) {
        Consumer<Boolean> done = onDone;
        pos = null;
        onDone = null;
        if (done != null) done.accept(ok);
    }
}
