package com.gadgetman.jarvis.steward.remarks;

/**
 * What he can see of you, right now, for nothing.
 *
 * <p>Every field here is a plain getter on the player or their location —
 * inventory, altitude, light, weather. Nothing is logged, nothing is
 * persisted, and no world event has to have been captured for any of it to be
 * true. That is the whole point: a butler who notices things does not need an
 * audit trail, he needs eyes.
 *
 * <p>Deliberately free of Bukkit types. The reading happens in {@link Observer}
 * on the main thread; the judgement in {@link RemarkDoctrine} is then a pure
 * function of plain data, and can be reasoned about — and tested — without a
 * server running.
 *
 * @param heldName        item in the main hand, prettified ("diamond pickaxe"),
 *                        or {@code null} when empty-handed
 * @param hoardName       the material he is carrying most of, prettified
 * @param hoardCount      how many of it, totalled across the whole inventory
 * @param slotsFree       empty slots in the 36-slot main inventory
 * @param xpLevel         experience levels, unspent
 * @param biome           biome key, lower case ("desert", "deep_dark")
 * @param dimension       "normal", "nether" or "the_end"
 * @param y               block altitude
 * @param underground     is there meaningful rock overhead
 * @param lightLevel      block light where they are standing, 0–15
 * @param night           world time is past dusk
 * @param thundering      a storm with lightning, not merely rain
 * @param homeDistance    metres to their bed, or to world spawn if they have
 *                        none; negative when the answer is another world
 * @param nearbyMonsters  hostiles within sixteen metres
 */
public record Observation(
        String heldName,
        String hoardName,
        int hoardCount,
        int slotsFree,
        int xpLevel,
        String biome,
        String dimension,
        int y,
        boolean underground,
        int lightLevel,
        boolean night,
        boolean thundering,
        double homeDistance,
        int nearbyMonsters
) { }
