package com.gadgetman.jarvis.building;

import java.util.Locale;
import java.util.Set;

/**
 * Corrects a block id a model got slightly wrong, when the right one is obvious.
 *
 * <p>Every script build on the GadgetCraft test server in November 2026 paid
 * for a second, full-price model call to fix a name: {@code chain} (renamed
 * {@code iron_chain} in 1.21.9), {@code bed} (beds are coloured), and the like.
 * The repair call resends the whole script and gets the whole script back, so
 * a one-word mistake doubled the cost of the build. Where the intended block
 * is unambiguous it is cheaper, and just as good, to swap it here and say so.
 *
 * <p>Deliberately conservative. Anything that would change the block's
 * material -- {@code diorite_pillar} to {@code quartz_pillar} -- is left for
 * the model to decide through the normal repair path.
 */
public final class BlockIdFixer {

    /**
     * Material picked for a bare noun that only exists in variants: {@code bed}
     * becomes {@code white_bed}, {@code chain} becomes {@code iron_chain}. Oak
     * comes first so {@code door} is a door a player can open by hand, not an
     * iron one that needs redstone.
     */
    private static final String[] DEFAULT_PREFIXES = {
            "oak_", "iron_", "white_", "stone_", "red_"
    };

    private final Set<String> validBlocks;

    /** @param validBlocks every placeable id, lowercase, without the namespace */
    public BlockIdFixer(Set<String> validBlocks) {
        this.validBlocks = validBlocks;
    }

    /**
     * The real block a near-miss id meant.
     *
     * @param base an id without namespace or states that is NOT a valid block
     * @return the replacement id, or null when there is no safe single answer
     */
    public String fix(String base) {
        if (validBlocks == null || base == null || base.isEmpty()) return null;
        String b = base.toLowerCase(Locale.ROOT);

        // brick -> bricks, stone_brick -> stone_bricks, and the reverse.
        if (validBlocks.contains(b + "s")) return b + "s";
        if (b.endsWith("s") && validBlocks.contains(b.substring(0, b.length() - 1))) {
            return b.substring(0, b.length() - 1);
        }
        // blackstone_bricks -> polished_blackstone_bricks.
        if (validBlocks.contains("polished_" + b)) return "polished_" + b;

        // A bare noun with variants: bed -> white_bed, chain -> iron_chain.
        if (!b.contains("_")) {
            for (String prefix : DEFAULT_PREFIXES) {
                if (validBlocks.contains(prefix + b)) return prefix + b;
            }
        }
        return null;
    }

    /**
     * Rewrites a full spec, keeping its namespace and block states.
     *
     * @return the corrected spec, or null when {@link #fix} has no answer
     */
    public String fixSpec(String spec, String base) {
        String fixed = fix(base);
        if (fixed == null) return null;
        String s = spec.trim();
        int br = s.indexOf('[');
        String states = br >= 0 ? s.substring(br) : "";
        return fixed + states;
    }
}
