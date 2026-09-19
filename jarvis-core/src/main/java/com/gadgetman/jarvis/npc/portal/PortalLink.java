package com.gadgetman.jarvis.npc.portal;

/**
 * Where a portal comes out on the other side.
 *
 * <p>Nothing here touches the world, because nothing needs to: the link between
 * the two dimensions is arithmetic. The Overworld runs at eight times the scale
 * of the Nether, so a portal at x 1600 arrives at x 200, and when you step
 * through, the game looks for an existing portal within
 * {@link #LINK_RADIUS} blocks of that arrival point before building a new one.
 * That is the whole rule, and it answers the question people actually have —
 * "where does this one come out?" — at any distance, in either direction, with
 * no scanning and no chunks loaded.
 *
 * <p><b>The trap is negative coordinates.</b> Java's {@code /} truncates toward
 * zero, so {@code -1227 / 8} is -153, while the game floors and gets -154. Eight
 * blocks is enough to put the answer in the wrong chunk, and every base west or
 * north of spawn is on the wrong side of that. {@link Math#floorDiv} throughout.
 */
public final class PortalLink {

    private PortalLink() { }

    /** Overworld blocks per Nether block. */
    public static final int SCALE = 8;

    /**
     * How far the game looks for an existing portal at the far end before
     * making a new one. Worth telling the player: it is the difference between
     * arriving where they expect and littering the Nether with portals.
     */
    public static final int LINK_RADIUS = 128;

    /** A block position, dimension-agnostic. */
    public record Coords(int x, int y, int z) { }

    /** Overworld position → the Nether position it links to. */
    public static Coords toNether(int x, int y, int z) {
        return new Coords(Math.floorDiv(x, SCALE), y, Math.floorDiv(z, SCALE));
    }

    /** Nether position → the Overworld position it links to. */
    public static Coords toOverworld(int x, int y, int z) {
        return new Coords(x * SCALE, y, z * SCALE);
    }

    /**
     * The counterpart of a portal, whichever side it is on.
     *
     * @param fromNether true when the portal given is in the Nether
     */
    public static Coords counterpart(int x, int y, int z, boolean fromNether) {
        return fromNether ? toOverworld(x, y, z) : toNether(x, y, z);
    }

    /**
     * Y is carried across unscaled, but the destination may not be as tall.
     * Clamped rather than refused — an Overworld portal at y 200 still links,
     * it simply arrives under the Nether roof.
     */
    public static int clampY(int y, int minY, int maxY) {
        return Math.max(minY, Math.min(maxY, y));
    }
}
