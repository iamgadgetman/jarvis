package com.gadgetman.jarvis.npc.combat;

import com.gadgetman.jarvis.npc.combat.Engagement.Tactic;

/**
 * Which weapon, and how to stand while using it.
 *
 * <p>The trident established the principle this is built on: <b>the right
 * weapon depends on where he is standing, not only on what rank he has
 * reached.</b> Archery extends that to distance. Rather than adding a third
 * set of branches to the tick loop, the whole decision lives here as one pure
 * function of the situation — which also means it can be reasoned about
 * without a server running.
 */
public final class WeaponDoctrine {

    private WeaponDoctrine() { }

    /**
     * Everything the choice depends on.
     *
     * @param distance      metres to the target
     * @param submerged     is his head underwater
     * @param hasBow        does his standing grant archery
     * @param hasTrident    does his standing grant the trident
     * @param lineOfSight   can he actually see the target — an arrow will not
     *                      go round a corner, so without this a bow is a
     *                      liability rather than an option
     * @param explosive     a creeper, or anything else it is a mistake to be
     *                      standing next to
     * @param canGiveGround is there somewhere behind him to retreat to
     */
    public record Situation(double distance, boolean submerged, boolean hasBow,
                            boolean hasTrident, boolean lineOfSight,
                            boolean explosive, boolean canGiveGround) { }

    public static Engagement choose(Situation s) {

        // ---- Water. A bow is no use here: arrows are spent almost at once in
        // water, and the trident exists precisely for this. ----
        if (s.submerged()) {
            if (s.hasTrident()) {
                if (s.distance() <= Armament.TRIDENT.reach())        return plan(Armament.TRIDENT, Tactic.STRIKE);
                if (s.distance() <= Armament.TRIDENT.standOffMax())  return plan(Armament.TRIDENT, Tactic.LOOSE);
                return plan(Armament.TRIDENT, Tactic.CLOSE);
            }
            return melee(s);
        }

        // ---- Dry land, and he can shoot ----
        if (s.hasBow() && s.lineOfSight()) {
            if (s.distance() > Armament.BOW.standOffMax()) {
                // Out of range: walk it down, but keep the bow up so he is
                // ready the moment the band opens.
                return plan(Armament.BOW, Tactic.CLOSE);
            }
            if (s.distance() >= Armament.BOW.standOffMin()) {
                return plan(Armament.BOW, Tactic.LOOSE);
            }
            // Inside the near edge. Backing off is the whole point of a bow.
            if (s.canGiveGround()) {
                return plan(Armament.BOW, Tactic.WITHDRAW);
            }
            // Cornered. Against something that explodes, shooting from a bad
            // position still beats walking into the blast; against anything
            // else, draw the sword and deal with it.
            if (s.explosive()) {
                return plan(Armament.BOW, Tactic.LOOSE);
            }
        }

        return melee(s);
    }

    /** No bow, no water, or nothing to shoot through: the sword, as ever. */
    private static Engagement melee(Situation s) {
        return plan(Armament.SWORD,
                s.distance() <= Armament.SWORD.reach() ? Tactic.STRIKE : Tactic.CLOSE);
    }

    private static Engagement plan(Armament weapon, Tactic tactic) {
        return new Engagement(weapon, tactic);
    }
}
