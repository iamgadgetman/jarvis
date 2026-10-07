package com.gadgetman.jarvis.vanilla.compat;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The few game calls whose names changed between Minecraft versions, for
 * 26.1.x and 26.2. The 26.3 copy is in mc26.3; a build picks one source
 * directory by its {@code mc_compat} property. Same behaviour, older names:
 * 26.3 renamed hurtMarked to syncVelocity, setInvulnerable to
 * setPermanentlyInvulnerable and SwingSource.SERVER to SERVER_ONLY, and gave
 * swing a SwingAnimation argument.
 */
public final class McCompat {

    private McCompat() {
    }

    /** Swings the arm and tells every watching client, the swinger included. */
    public static void swing(LivingEntity entity, InteractionHand hand) {
        entity.swing(hand, true);
    }

    /** Marks the entity's velocity to be sent to clients on the next tick. */
    public static void syncVelocity(Entity entity) {
        entity.hurtMarked = true;
    }

    /** Invulnerable to everything but what bypasses invulnerability (the void, /kill). */
    public static void setInvulnerable(Entity entity, boolean invulnerable) {
        entity.setInvulnerable(invulnerable);
    }

    /** True when the server, not the client, is expected to swing the arm for this result. */
    public static boolean serverSwings(InteractionResult.Success success) {
        return success.swingSource() == InteractionResult.SwingSource.SERVER;
    }
}
