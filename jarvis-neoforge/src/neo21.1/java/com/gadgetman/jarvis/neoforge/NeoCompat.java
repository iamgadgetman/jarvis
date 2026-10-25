package com.gadgetman.jarvis.neoforge;

import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/** The NeoForge calls that differ between versions, for NeoForge 21.1 (Minecraft 1.21.1). The 26.x copy is in neo26. */
final class NeoCompat {

    private NeoCompat() {
    }

    /** The damage that got through. */
    static float damageDealt(LivingDamageEvent.Post event) {
        return event.getNewDamage();
    }
}
