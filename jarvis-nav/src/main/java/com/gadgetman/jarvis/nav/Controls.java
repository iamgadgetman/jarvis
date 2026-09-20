package com.gadgetman.jarvis.nav;

/**
 * What the follower wants the body to do this tick, in the vocabulary of a
 * player's keyboard: where to look, how hard to push forward and sideways,
 * and which keys are held.
 *
 * @param useDoor true when the next node is a door he should open now
 */
public record Controls(float yaw, float pitch, float forward, float strafe,
                       boolean jump, boolean sneak, boolean sprint, boolean useDoor) {

    public static final Controls IDLE = new Controls(0, 0, 0, 0, false, false, false, false);

    public boolean isIdle() {
        return forward == 0 && strafe == 0 && !jump;
    }
}
