package com.gadgetman.jarvis.onboarding;

/**
 * Things a player can do that the tutorial (FB-11) waits for. Each is
 * reported by the code that does it, so a step completes however the player
 * got there: a slash command, a bell button, or a sentence in chat.
 */
public enum Milestone {
    /** The bell menu opened (the bell, or a click on the butler). */
    BELL,
    /** The butler is out, beside this owner. */
    SUMMONED,
    /** Follow mode started. */
    FOLLOWING,
    /** A deposit chest was registered. */
    CHEST,
    /** A mining job started (ore seeking or a branch mine). */
    MINING,
    /** A deposit was asked for and there was a chest to go to. */
    DEPOSIT,
    /** An undo started putting blocks back. */
    UNDO
}
