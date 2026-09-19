package com.gadgetman.jarvis.core.platform;

import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A real player who owns a Jarvis.
 *
 * <p>An Owner is a handle keyed on the player's id. It stays valid across
 * relogs: the adapter looks the live player up on every call, and while the
 * player is offline the getters answer with safe defaults. Two Owners are
 * equal when their ids are.
 */
public interface Owner extends Audience {

    UUID id();

    String name();

    boolean isOnline();

    boolean isOp();

    boolean hasPermission(String node);

    WorldId world();

    Vec3 pos();

    Vec3 eyePos();

    Look look();

    double health();

    double maxHealth();

    /** Experience level. */
    int level();

    boolean isSneaking();

    /** The game mode's lower-case name: survival, creative, adventure, spectator. */
    String gameMode();

    Item heldItem();

    /** The storage slots, hotbar included, in slot order; empty slots as empty items. */
    List<Item> inventory();

    /** Give an item, dropping at the feet whatever does not fit. */
    void give(Item item);

    /** The block the player is looking at within reach, if any. */
    Optional<BlockPos> targetBlock(double reach);

    /** Where the player would wake up: the bed if it is set, else world spawn. */
    Site respawnPlace();

    void teleport(WorldId world, Vec3 pos, Look look);
}
