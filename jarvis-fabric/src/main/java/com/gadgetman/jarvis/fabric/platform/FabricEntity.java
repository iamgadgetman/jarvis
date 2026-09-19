package com.gadgetman.jarvis.fabric.platform;

import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.EntityKind;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;

import java.util.Optional;
import java.util.UUID;

/** Core's {@link Entity} over a game entity. */
public final class FabricEntity implements Entity {

    private final net.minecraft.world.entity.Entity e;

    public FabricEntity(net.minecraft.world.entity.Entity e) {
        this.e = e;
    }

    public net.minecraft.world.entity.Entity handle() {
        return e;
    }

    private ServerLevel level() {
        return (ServerLevel) e.level();
    }

    @Override public UUID id() { return e.getUUID(); }
    @Override public String typeId() { return FabricWorlds.typeId(e); }
    @Override public EntityKind kind() { return FabricWorlds.kind(e); }
    @Override public boolean isAlive() { return e.isAlive(); }
    @Override public boolean isCreeper() { return e instanceof Creeper; }
    @Override public WorldId world() { return FabricWorlds.id(e.level()); }
    @Override public Vec3 pos() { return FabricWorlds.vec(e.position()); }
    @Override public Vec3 eyePos() { return FabricWorlds.vec(e.getEyePosition()); }
    @Override public Vec3 velocity() { return FabricWorlds.vec(e.getDeltaMovement()); }

    @Override
    public void setVelocity(Vec3 v) {
        e.setDeltaMovement(FabricWorlds.mc(v));
        e.hurtMarked = true;
    }

    @Override
    public double health() {
        return e instanceof LivingEntity le ? le.getHealth() : 0.0;
    }

    @Override
    public void damage(double amount) {
        if (e instanceof LivingEntity le) le.hurtServer(level(), level().damageSources().generic(), (float) amount);
    }

    @Override public int fireTicks() { return e.getRemainingFireTicks(); }
    @Override public void setFireTicks(int ticks) { e.setRemainingFireTicks(ticks); }

    @Override
    public boolean isTargeting(Owner owner) {
        return e instanceof Mob mob && mob.getTarget() != null && mob.getTarget().getUUID().equals(owner.id());
    }

    @Override
    public Optional<Item> asItem() {
        return e instanceof ItemEntity item ? Optional.of(FabricItems.toItem(item.getItem())) : Optional.empty();
    }

    @Override
    public void setItem(Item item) {
        if (e instanceof ItemEntity drop) {
            if (item.isEmpty()) drop.discard();
            else drop.setItem(FabricItems.toStack(server(), item));
        }
    }

    private MinecraftServer server() {
        return level().getServer();
    }

    @Override public void remove() { e.discard(); }

    @Override
    public boolean equals(Object o) {
        return o instanceof FabricEntity other && other.e.getUUID().equals(e.getUUID());
    }

    @Override
    public int hashCode() {
        return e.getUUID().hashCode();
    }
}
