package com.gadgetman.jarvis.neoforge;

import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/** The NeoForge calls that differ between versions, for NeoForge 26.x. The 21.1 copy is in neo21.1. */
final class NeoCompat {

    private NeoCompat() {
    }

    /** The damage that got through. */
    static float damageDealt(LivingDamageEvent.Post event) {
        return event.getInflictedDamage();
    }
}
