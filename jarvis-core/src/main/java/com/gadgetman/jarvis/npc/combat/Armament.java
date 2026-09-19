package com.gadgetman.jarvis.npc.combat;

import com.gadgetman.jarvis.progression.Rank;

/**
 * A weapon class, and the geometry that comes with it.
 *
 * <p>Reach used to be one constant shared by everything he could hold, which
 * was fine while "everything" meant a sword. It stops being fine the moment
 * two weapons want to be used at different distances: a bow is not a sword
 * with a bigger number, it is a different way of standing.
 *
 * <p>So each armament carries its own engagement band. {@link #reach()} is how
 * close he must be to swing; {@link #standOffMin()} and {@link #standOffMax()}
 * are the window a ranged weapon wants to hold, and are meaningless for a
 * melee one. A future spear slots in here as a longer {@code reach()} rather
 * than as another branch in the tick loop.
 */
public enum Armament {

    /** The default. Everything he does on dry land, up close. */
    SWORD(Rank.ToolKind.SWORD, 2.7, 0.0, 0.0, false),

    /**
     * Underwater only. Slightly longer than a sword because it is a polearm,
     * and thrown when he cannot close — swimming to a guardian is slow, and he
     * is a sitting duck doing it.
     */
    TRIDENT(Rank.ToolKind.TRIDENT, 3.0, 3.0, 24.0, true),

    /**
     * The stand-off weapon. The minimum matters more than the maximum: a bow
     * is worthless in someone's face, so the band's near edge is what makes
     * him back up rather than a number he is allowed to ignore.
     */
    BOW(Rank.ToolKind.BOW, 2.7, 8.0, 28.0, true);

    private final Rank.ToolKind kind;
    private final double reach;
    private final double standOffMin;
    private final double standOffMax;
    private final boolean ranged;

    Armament(Rank.ToolKind kind, double reach, double standOffMin, double standOffMax, boolean ranged) {
        this.kind        = kind;
        this.reach       = reach;
        this.standOffMin = standOffMin;
        this.standOffMax = standOffMax;
        this.ranged      = ranged;
    }

    public Rank.ToolKind kind() { return kind; }

    /** How close he must be to hit something with it by hand. */
    public double reach() { return reach; }

    /** Nearest he wants to be while using it at range; 0 for a melee weapon. */
    public double standOffMin() { return standOffMin; }

    /** Furthest he will loose from; beyond this he closes on foot. */
    public double standOffMax() { return standOffMax; }

    public boolean ranged() { return ranged; }
}
