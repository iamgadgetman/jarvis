package com.gadgetman.jarvis.core.platform;

import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One player's Jarvis, as a body in the world.
 *
 * <p>This is the fake-player vocabulary: look, walk, hold, swing, break, and a
 * bag of items. Citizens implements every method here; a fake player on
 * Fabric will too. What the butler <i>decides</i> to do lives in core, in the
 * task classes and their {@link com.gadgetman.jarvis.npc.ButlerHost}.
 *
 * <p>A handle is keyed on the owner and stays valid whether or not he is
 * spawned; the getters answer with safe defaults while he is not.
 */
public interface Butler {

    Owner owner();

    boolean isSpawned();

    Optional<Entity> entity();

    /** The world he is standing in, when spawned. */
    Optional<World> world();

    /** Where he is, or where he last was. */
    Vec3 pos();

    Vec3 eyePos();

    Look look();

    boolean isOnGround();

    void setLook(Look look);

    void setVelocity(Vec3 velocity);

    /** Move him within the world he is in. */
    void teleport(Vec3 pos);

    void teleport(Vec3 pos, Look look);

    void teleport(World world, Vec3 pos, Look look);

    void setProtected(boolean invulnerable);

    void setSwimming(boolean swim);

    // ---- looking ----

    void lookAt(Vec3 target);

    default void lookAt(Entity target) {
        if (target != null) lookAt(target.pos());
    }

    // ---- moving ----

    /** The plugin's navigator policy: range, repath rate, no teleport when stuck. */
    void applyNavigationDefaults(Runnable onStuck);

    /** Walk to a point, keeping whatever stuck handler is in force. */
    void navigateTo(Vec3 target);

    /** Walk to a point; {@code onStuck} fires once if the path fails, and navigation stops. */
    void navigateTo(Vec3 target, Runnable onStuck);

    /** Follow or chase an entity. */
    void navigateTo(Entity target, boolean aggressive);

    void cancelNavigation();

    boolean isNavigating();

    void setNavigationPaused(boolean paused);

    boolean isNavigationPaused();

    // ---- hands ----

    Item heldItem();

    void setHeldItem(Item item);

    Item equipment(Slot slot);

    void setEquipment(Slot slot, Item item);

    void swing();

    void swingOffHand();

    boolean hasLineOfSight(Entity target);

    // ---- acting on the world ----

    /**
     * Break a block the way a player would: face it, crack it at the speed the
     * tool allows, drop the loot, then report. {@code speedModifier} scales the
     * break speed; 1 is vanilla.
     */
    void breakBlock(BlockPos pos, Item tool, double speedModifier, Consumer<Boolean> onDone);

    /** Abandon a break in progress, if any. */
    void cancelBreaking();

    /** One blow with what he is holding, credited to him. */
    void attack(Entity target, double damage);

    /** Loose an arrow from a point in a direction. Pickup is disallowed; misses are cleaned up. */
    void shootArrow(Vec3 from, Vec3 direction, double speed, double damage, int knockback, boolean flame);

    /** Throw a conjured trident. He never loses the one in his hand. */
    void throwTrident(Vec3 from, Vec3 direction, double speed, double damage);

    // ---- carrying ----

    /** Every slot, in order. Slot 0 is his hand. */
    List<Item> inventory();

    void setInventory(List<Item> items);

    /** Stack onto what he has, then fill an empty slot. False when nothing fits. */
    boolean addToInventory(Item item);

    void openInventory(Owner viewer);

    // ---- surroundings ----

    /** Entities inside a box around him, the game's own query. */
    List<Entity> nearbyEntities(double dx, double dy, double dz);
}
