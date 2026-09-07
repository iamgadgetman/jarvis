package com.gadgetman.jarvis.npc.combat;

/**
 * What to do about the thing in front of him, this tick.
 *
 * <p>A weapon and a way of standing, decided together. They have to be decided
 * together: drawing a bow without also backing off is how you get a butler
 * shot at point-blank range, and backing off without drawing one is just
 * running away.
 */
public record Engagement(Armament weapon, Tactic tactic) {

    public enum Tactic {
        /** In range and armed for it — swing. */
        STRIKE,
        /** Too far to strike and not equipped to shoot — walk at it. */
        CLOSE,
        /** In the band. Stand still, face it, loose. */
        LOOSE,
        /** Inside the near edge of the band. Give ground, and shoot while doing it. */
        WITHDRAW
    }
}
