package com.gadgetman.jarvis.core.platform;

import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;

import java.util.Optional;
import java.util.UUID;

/** A live entity in a world, as core sees it. Handles read through to the live entity. */
public interface Entity {

    UUID id();

    /** The registry id, such as {@code minecraft:zombie}. */
    String typeId();

    EntityKind kind();

    boolean isAlive();

    /** The Defender special-cases creepers, which explode rather than fight. */
    boolean isCreeper();

    WorldId world();

    Vec3 pos();

    Vec3 eyePos();

    Vec3 velocity();

    void setVelocity(Vec3 v);

    double health();

    /** Hurt the entity by this much, with no attacker on record. */
    void damage(double amount);

    int fireTicks();

    void setFireTicks(int ticks);

    /** True when this is a mob whose current target is the owner. */
    boolean isTargeting(Owner owner);

    /** The stack, when this is an item lying on the ground. */
    Optional<Item> asItem();

    /** Replace the stack of an item lying on the ground. No-op for anything else. */
    void setItem(Item item);

    void remove();
}
