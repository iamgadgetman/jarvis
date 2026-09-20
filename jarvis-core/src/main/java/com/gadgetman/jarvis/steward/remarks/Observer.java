package com.gadgetman.jarvis.steward.remarks;

import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.EntityKind;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads an {@link Observation} off a player.
 *
 * <p>Everything here is a getter that was going to be answered anyway — no
 * events are subscribed to, nothing is written down, and the cost of a reading
 * is one pass over 36 inventory slots and one nearby-entity query. Server
 * thread only, like anything else that touches the world.
 */
public final class Observer {

    private Observer() { }

    private static final int MONSTER_RADIUS = 16;

    /** Read the player. Never throws; returns {@code null} if the world will not answer. */
    public static Observation observe(Platform platform, Owner owner) {
        if (owner == null || !owner.isOnline()) return null;
        try {
            World world = platform.world(owner.world()).orElse(null);
            if (world == null) return null;

            // ---- Inventory: what he is holding, what he has most of, what is left ----
            Item held = owner.heldItem();
            String heldName = held.isEmpty() ? null : pretty(held.id());

            Map<String, Integer> totals = new HashMap<>();
            int slotsFree = 0;
            for (Item stack : owner.inventory()) {
                if (stack.isEmpty()) {
                    slotsFree++;
                    continue;
                }
                totals.merge(stack.id(), stack.count(), Integer::sum);
            }

            String hoard = null;
            int hoardCount = 0;
            for (Map.Entry<String, Integer> entry : totals.entrySet()) {
                if (entry.getValue() > hoardCount) {
                    hoard = entry.getKey();
                    hoardCount = entry.getValue();
                }
            }

            // ---- Where they are standing ----
            Vec3 pos = owner.pos();
            BlockPos at = pos.block();
            int y = at.y();
            boolean underground = world.highestY(at.x(), at.z()) > y + 2;
            int light = world.blockLight(at);
            boolean night = world.time() >= 13000;

            int monsters = 0;
            for (Entity entity : world.nearby(pos, MONSTER_RADIUS, 8, MONSTER_RADIUS)) {
                if (entity.kind() == EntityKind.HOSTILE) monsters++;
            }

            return new Observation(
                    heldName,
                    hoard == null ? null : pretty(hoard),
                    hoardCount,
                    slotsFree,
                    owner.level(),
                    world.biome(at),
                    world.environment().key(),
                    y,
                    underground,
                    light,
                    night,
                    world.isThundering(),
                    homeDistance(owner, pos),
                    monsters);
        } catch (Exception e) {
            // An idle remark is the least important thing the plugin does.
            // It never gets to break a tick.
            return null;
        }
    }

    /**
     * Metres to the bed they will wake up in, falling back to world spawn.
     * Negative when the answer is in another world, which is not a distance.
     */
    private static double homeDistance(Owner owner, Vec3 from) {
        Site home = owner.respawnPlace();
        if (!home.world().id().equals(owner.world())) return -1.0;
        return home.pos().distance(from);
    }

    /** "minecraft:deepslate_iron_ore" -> "deepslate iron ore". */
    static String pretty(String id) {
        return Ids.key(id).replace('_', ' ');
    }
}
