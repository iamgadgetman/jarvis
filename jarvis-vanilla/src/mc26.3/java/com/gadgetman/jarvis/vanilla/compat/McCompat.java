package com.gadgetman.jarvis.vanilla.compat;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * The few game calls whose names changed between Minecraft versions, for
 * 26.3. The 26.1/26.2 copy is in mc26.1; a build picks
 * one source directory by its {@code mc_compat} property. Everything else in
 * jarvis-vanilla compiles unchanged on both.
 */
public final class McCompat {

    private McCompat() {
    }

    /** Swings the arm and tells every watching client, the swinger included. */
    public static void swing(LivingEntity entity, InteractionHand hand) {
        entity.swing(hand, SwingAnimation.DEFAULT, true);
    }

    /** Marks the entity's velocity to be sent to clients on the next tick. */
    public static void syncVelocity(Entity entity) {
        entity.syncVelocity = true;
    }

    /** Invulnerable to everything but what bypasses invulnerability (the void, /kill). */
    public static void setInvulnerable(Entity entity, boolean invulnerable) {
        entity.setPermanentlyInvulnerable(invulnerable);
    }

    /** True when the server, not the client, is expected to swing the arm for this result. */
    public static boolean serverSwings(InteractionResult.Success success) {
        return success.swingSource() == InteractionResult.SwingSource.SERVER_ONLY;
    }
}
