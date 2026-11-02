package com.gadgetman.jarvis.vanilla.mixin;

import com.gadgetman.jarvis.vanilla.fake.FakePlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/**
 * The butler never needs to sleep for the night to pass. Vanilla counts
 * every non-spectator player in the level, and he is one, so with him
 * summoned the owner alone in bed was only half the players. Taking him out
 * of the list both counts are made from fixes the wait, the "1/2 players
 * sleeping" message, and {@code areEnoughSleeping} (which reads the counts
 * {@code update} keeps). The {@code playersSleepingPercentage} rule is
 * untouched. The same in 26.1, 26.2 and 26.3.
 */
@Mixin(SleepStatus.class)
public abstract class SleepStatusMixin {

    @ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayer> jarvis$ignoreFakesWhenCounting(List<ServerPlayer> players) {
        return FakePlayer.withoutFakes(players);
    }

    @ModifyVariable(method = "areEnoughDeepSleeping", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayer> jarvis$ignoreFakesWhenWaking(List<ServerPlayer> players) {
        return FakePlayer.withoutFakes(players);
    }
}
