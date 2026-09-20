package com.gadgetman.jarvis.nav;

/** How a path gets from one node to the next. The follower drives each differently. */
public enum MoveType {
    START,
    WALK,
    STEP_UP,
    DROP,
    JUMP_GAP,
    SWIM,
    CLIMB_UP,
    CLIMB_DOWN,
    DOOR
}
