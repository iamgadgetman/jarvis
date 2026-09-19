package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.EntityKind;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.LivingEntity;

import java.util.Optional;
import java.util.UUID;

/** Core's {@link Entity} over a Bukkit entity. */
public final class PaperEntity implements Entity {

    private final org.bukkit.entity.Entity e;

    public PaperEntity(org.bukkit.entity.Entity e) {
        this.e = e;
    }

    public org.bukkit.entity.Entity handle() {
        return e;
    }

    @Override public UUID id() { return e.getUniqueId(); }
    @Override public String typeId() { return e.getType().getKey().toString(); }
    @Override public EntityKind kind() { return PaperWorlds.kind(e); }
    @Override public boolean isAlive() { return e.isValid() && !e.isDead(); }
    @Override public boolean isCreeper() { return e instanceof Creeper; }
    @Override public WorldId world() { return PaperWorlds.id(e.getWorld()); }
    @Override public Vec3 pos() { return PaperWorlds.vec(e.getLocation()); }
    @Override public Vec3 velocity() { return PaperWorlds.vec(e.getVelocity()); }
    @Override public void setVelocity(Vec3 v) { e.setVelocity(PaperWorlds.vector(v)); }

    @Override
    public double health() {
        return e instanceof LivingEntity le ? le.getHealth() : 0.0;
    }

    @Override
    public void damage(double amount) {
        if (e instanceof LivingEntity le) le.damage(amount);
    }

    @Override
    public Optional<Item> asItem() {
        return e instanceof org.bukkit.entity.Item item
                ? Optional.of(PaperItems.toItem(item.getItemStack()))
                : Optional.empty();
    }

    @Override public void remove() { e.remove(); }

    @Override
    public boolean equals(Object o) {
        return o instanceof PaperEntity other && other.e.getUniqueId().equals(e.getUniqueId());
    }

    @Override
    public int hashCode() {
        return e.getUniqueId().hashCode();
    }
}
