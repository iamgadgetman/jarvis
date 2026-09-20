package com.gadgetman.jarvis.core.testing;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Butlers;
import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Slot;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Butlers with no body: a record per owner of where he is, what he holds
 * and what he was last told to do. Walking is instant unless a test says
 * otherwise, and breaking a block is immediate and always succeeds.
 */
public final class FakeButlers implements Butlers {

    /** What the adapter would keep about one butler. */
    public static final class State {
        public boolean spawned;
        public String name;
        public FakeWorld world;
        public Vec3 pos = Vec3.ZERO;
        public Look look = Look.SOUTH;
        public boolean onGround = true;
        public boolean invulnerable;
        public boolean swimming;
        public boolean navigating;
        public boolean paused;
        public Vec3 navTarget;
        public Entity navEntity;
        public final List<Item> inventory = new ArrayList<>();
        public final Map<Slot, Item> equipment = new EnumMap<>(Slot.class);
        public int swings;
        public int arrowsShot;
        public int tridentsThrown;
        public final List<BlockPos> broken = new ArrayList<>();
        public final List<Vec3> lookedAt = new ArrayList<>();
        public final List<Vec3> teleports = new ArrayList<>();

        State() {
            for (int i = 0; i < 36; i++) inventory.add(Item.EMPTY);
        }
    }

    private final FakePlatform platform;
    private final Map<UUID, State> states = new HashMap<>();
    /** When true, navigateTo puts him at the target at once. */
    public boolean instantTravel = true;

    FakeButlers(FakePlatform platform) {
        this.platform = platform;
    }

    public State state(Owner owner) {
        return states.get(owner.id());
    }

    @Override public String name() { return "fake"; }
    @Override public Butler of(Owner owner) { return new FakeButler(owner.id()); }

    @Override
    public Collection<Butler> spawned() {
        List<Butler> out = new ArrayList<>();
        for (Map.Entry<UUID, State> e : states.entrySet()) if (e.getValue().spawned) out.add(new FakeButler(e.getKey()));
        return out;
    }

    @Override
    public Collection<Butler> all() {
        List<Butler> out = new ArrayList<>();
        for (UUID id : states.keySet()) out.add(new FakeButler(id));
        return out;
    }

    /** The handle: valid whether or not he exists, as the contract says. */
    public final class FakeButler implements Butler {
        private final UUID ownerId;

        FakeButler(UUID ownerId) {
            this.ownerId = ownerId;
        }

        private State s() {
            return states.get(ownerId);
        }

        private State live() {
            State s = s();
            return s != null && s.spawned ? s : null;
        }

        @Override public Owner owner() { return platform.players().owner(ownerId); }

        @Override
        public void spawn(World world, Vec3 at, String name) {
            State s = states.computeIfAbsent(ownerId, k -> new State());
            if (s.spawned) return;
            s.spawned = true;
            s.name = name;
            s.world = (FakeWorld) world;
            s.pos = at;
        }

        @Override public void despawn() { states.remove(ownerId); }
        @Override public boolean exists() { return s() != null; }
        @Override public boolean isSpawned() { return live() != null; }
        @Override public Optional<Entity> entity() { return Optional.empty(); }

        @Override
        public Optional<World> world() {
            State s = s();
            return s == null || s.world == null ? Optional.empty() : Optional.of(s.world);
        }

        @Override public Vec3 pos() { State s = s(); return s == null ? Vec3.ZERO : s.pos; }
        @Override public Vec3 eyePos() { return pos().add(0, 1.62, 0); }
        @Override public Look look() { State s = s(); return s == null ? Look.SOUTH : s.look; }
        @Override public boolean isOnGround() { State s = live(); return s != null && s.onGround; }
        @Override public void setLook(Look look) { State s = live(); if (s != null) s.look = look; }
        @Override public void setVelocity(Vec3 velocity) { }

        @Override public void teleport(Vec3 pos) { State s = live(); if (s != null) { s.pos = pos; s.teleports.add(pos); } }
        @Override public void teleport(Vec3 pos, Look look) { teleport(pos); setLook(look); }

        @Override
        public void teleport(World world, Vec3 pos, Look look) {
            State s = live();
            if (s == null) return;
            s.world = (FakeWorld) world;
            teleport(pos, look);
        }

        @Override public void setProtected(boolean invulnerable) { State s = s(); if (s != null) s.invulnerable = invulnerable; }
        @Override public void setSwimming(boolean swim) { State s = s(); if (s != null) s.swimming = swim; }
        @Override public void lookAt(Vec3 target) { State s = live(); if (s != null) s.lookedAt.add(target); }
        @Override public void applyNavigationDefaults(Runnable onStuck) { }

        @Override
        public void navigateTo(Vec3 target) {
            State s = live();
            if (s == null) return;
            s.navTarget = target;
            if (instantTravel) {
                s.pos = target;
                s.navigating = false;
            } else {
                s.navigating = true;
            }
        }

        @Override public void navigateTo(Vec3 target, Runnable onStuck) { navigateTo(target); }

        @Override
        public void navigateTo(Entity target, boolean aggressive) {
            State s = live();
            if (s == null) return;
            s.navEntity = target;
            navigateTo(target.pos());
        }

        @Override public void cancelNavigation() { State s = s(); if (s != null) { s.navigating = false; s.navTarget = null; } }
        @Override public boolean isNavigating() { State s = s(); return s != null && s.navigating; }
        @Override public void setNavigationPaused(boolean paused) { State s = s(); if (s != null) s.paused = paused; }
        @Override public boolean isNavigationPaused() { State s = s(); return s != null && s.paused; }

        @Override public Item heldItem() { State s = s(); return s == null ? Item.EMPTY : s.inventory.get(0); }

        @Override
        public void setHeldItem(Item item) {
            State s = s();
            if (s == null) return;
            s.inventory.set(0, item == null ? Item.EMPTY : item);
            s.equipment.put(Slot.HAND, item == null ? Item.EMPTY : item);
        }

        @Override
        public Item equipment(Slot slot) {
            State s = s();
            if (s == null) return Item.EMPTY;
            return slot == Slot.HAND ? heldItem() : s.equipment.getOrDefault(slot, Item.EMPTY);
        }

        @Override
        public void setEquipment(Slot slot, Item item) {
            if (slot == Slot.HAND) { setHeldItem(item); return; }
            State s = s();
            if (s != null) s.equipment.put(slot, item);
        }

        @Override public void swing() { State s = live(); if (s != null) s.swings++; }
        @Override public void swingOffHand() { swing(); }
        @Override public boolean hasLineOfSight(Entity target) { return true; }

        @Override
        public void breakBlock(BlockPos pos, Item tool, double speedModifier, Consumer<Boolean> onDone) {
            State s = live();
            if (s == null || s.world == null) { onDone.accept(false); return; }
            s.broken.add(pos);
            s.world.breakNaturally(pos, tool);
            onDone.accept(s.world.block(pos).isAir());
        }

        @Override public void cancelBreaking() { }

        @Override
        public void attack(Entity target, double damage) {
            if (live() != null) target.damage(damage);
        }

        @Override
        public void shootArrow(Vec3 from, Vec3 direction, double speed, double damage, int knockback, boolean flame) {
            State s = live();
            if (s != null) s.arrowsShot++;
        }

        @Override
        public void throwTrident(Vec3 from, Vec3 direction, double speed, double damage) {
            State s = live();
            if (s != null) s.tridentsThrown++;
        }

        @Override public List<Item> inventory() { State s = s(); return s == null ? List.of() : List.copyOf(s.inventory); }

        @Override
        public void setInventory(List<Item> items) {
            State s = s();
            if (s == null) return;
            for (int i = 0; i < s.inventory.size(); i++) {
                s.inventory.set(i, i < items.size() && items.get(i) != null ? items.get(i) : Item.EMPTY);
            }
        }

        @Override
        public Item addToInventory(Item item) {
            State s = s();
            if (s == null || item.isEmpty()) return item;
            int left = item.count();
            int max = platform.items().maxStackSize(item.id());
            for (int i = 1; i < s.inventory.size() && left > 0; i++) {
                Item have = s.inventory.get(i);
                if (!have.isEmpty() && have.id().equals(item.id()) && have.enchants().equals(item.enchants())
                        && have.count() < max) {
                    int add = Math.min(max - have.count(), left);
                    s.inventory.set(i, have.withCount(have.count() + add));
                    left -= add;
                }
            }
            for (int i = 1; i < s.inventory.size() && left > 0; i++) {
                if (s.inventory.get(i).isEmpty()) {
                    int add = Math.min(max, left);
                    s.inventory.set(i, item.withCount(add));
                    left -= add;
                }
            }
            return left <= 0 ? Item.EMPTY : item.withCount(left);
        }

        @Override public void openInventory(Owner viewer) { platform.ui().opened.add("loot:" + ownerId); }

        @Override
        public List<Entity> nearbyEntities(double dx, double dy, double dz) {
            State s = live();
            if (s == null || s.world == null) return List.of();
            return s.world.nearby(s.pos, dx, dy, dz);
        }

        @Override public boolean equals(Object o) { return o instanceof FakeButler other && other.ownerId.equals(ownerId); }
        @Override public int hashCode() { return ownerId.hashCode(); }
    }
}
