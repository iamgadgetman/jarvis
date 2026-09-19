package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.progression.Rank;
import com.gadgetman.jarvis.progression.ServiceRecord;
import com.gadgetman.jarvis.recovery.TaskFailure;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * What a task needs from the butler's keeper: his body ({@link Butler}), his
 * voice, his task register, his kit and his bags.
 *
 * <p>The task classes (Lumberjack, Farmer, Defender and the rest) talk to
 * this and to {@link Butler}, never to a platform. The Paper adapter's
 * JarvisNPC implements it today; the orchestration behind it moves into core
 * in step 6 of the platform plan.
 */
public interface ButlerHost {

    Platform platform();

    Config config();

    /** The owner's butler, spawned or not. */
    Butler butler(Owner owner);

    /** The world his butler stands in, when spawned. */
    default Optional<World> worldOf(Owner owner) {
        return butler(owner).world();
    }

    /** Where his butler is, or null when there is no butler. */
    default Vec3 currentLocation(Owner owner) {
        Butler b = butler(owner);
        return b.isSpawned() ? b.pos() : null;
    }

    /** The pack-mule service: chests, homes, patrol routes and portal memory. */
    DepositManager deposits();

    /** What he is doing for this owner, in a few words, or null when idle. */
    String describeCurrentTask(Owner owner);

    /**
     * Metres from this owner's Jarvis to the owner, or {@link Double#MAX_VALUE}
     * when the question does not apply: not summoned, not spawned, or a world
     * away. One answer to "is he near enough to have noticed?".
     */
    double distanceToOwner(Owner owner);

    // ---- voice ----

    void say(Owner owner, String text);

    /** The action bar, for progress that is not worth a chat line. */
    void sayQuiet(Owner owner, String text);

    // ---- the task register ----

    void registerTask(Owner owner, Task task);

    void taskDone(Owner owner, Task task);

    /** Cancel whatever he is doing for this owner. */
    void stopTask(Owner owner);

    /** A task is starting; resets its self-explain budget. */
    void beginTask(Owner owner, String taskType);

    void reportFailure(TaskFailure failure);

    // ---- standing ----

    void credit(Owner owner, ServiceRecord.Discipline discipline, int amount);

    boolean hasCapability(Owner owner, Rank.Capability capability);

    boolean celebrationsEnabled();

    // ---- kit ----

    /** Put the rank-appropriate version of this kind of tool in his hand. */
    void equipTool(Owner owner, String itemId);

    Item toolInHand(Owner owner);

    void giveStartingEquipment(Owner owner);

    void giveGuardEquipment(Owner owner);

    void drawWeapon(Owner owner, Rank.ToolKind kind);

    void syncWeaponToSurroundings(Owner owner);

    boolean isSubmerged(Owner owner);

    String describeHeldTool(Owner owner);

    // ---- bags ----

    int lootCapacity();

    int lootSlotsUsed(Owner owner);

    default void pickupNearbyItems(Owner owner, Vec3 npcPos) {
        pickupNearbyItems(owner, npcPos, false);
    }

    void pickupNearbyItems(Owner owner, Vec3 npcPos, boolean includeJunk);

    // ---- the world ----

    /** Break a block with the tool policy applied: timing, speed, restoring a lost pickaxe. */
    void breakBlockProperly(Owner owner, World world, BlockPos pos, Consumer<Boolean> onDone);

    /** A standable spot at or near a point. */
    Vec3 findSafeNear(World world, Vec3 near);
}
