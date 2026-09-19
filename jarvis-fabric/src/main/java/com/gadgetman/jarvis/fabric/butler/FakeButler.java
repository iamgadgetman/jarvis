package com.gadgetman.jarvis.fabric.butler;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Slot;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.fabric.fake.FakePlayer;
import com.gadgetman.jarvis.fabric.platform.FabricEntity;
import com.gadgetman.jarvis.fabric.platform.FabricItems;
import com.gadgetman.jarvis.fabric.platform.FabricWorld;
import com.gadgetman.jarvis.fabric.platform.FabricWorlds;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Core's {@link Butler} over a fake player. A handle keyed on the owner:
 * it looks the player up on every call, so it is valid before he is
 * summoned and after he is dismissed, and answers with safe defaults in
 * between. Walking is jarvis-nav through the {@link FakeDriver}.
 */
public final class FakeButler implements Butler {

    private final FakeButlers butlers;
    private final UUID ownerId;

    FakeButler(FakeButlers butlers, UUID ownerId) {
        this.butlers = butlers;
        this.ownerId = ownerId;
    }

    private FakePlayer p() {
        return butlers.player(ownerId);
    }

    private MinecraftServer server() {
        return butlers.platform().server();
    }

    private ItemStack stack(Item item) {
        return FabricItems.toStack(server(), item);
    }

    // ---- identity and place ----

    @Override public Owner owner() { return butlers.platform().players().owner(ownerId); }

    @Override
    public void spawn(World world, Vec3 at, String name) {
        butlers.spawn(ownerId, ((FabricWorld) world).handle(), at, name);
    }

    @Override public void despawn() { butlers.despawn(ownerId); }
    @Override public boolean exists() { return butlers.exists(ownerId); }
    @Override public boolean isSpawned() { return p() != null; }

    @Override
    public Optional<Entity> entity() {
        FakePlayer p = p();
        return p == null ? Optional.empty() : Optional.of(new FabricEntity(p));
    }

    @Override
    public Optional<World> world() {
        FakePlayer p = p();
        return p == null ? Optional.empty() : Optional.of(new FabricWorld(p.level()));
    }

    @Override
    public Vec3 pos() {
        FakePlayer p = p();
        return p == null ? Vec3.ZERO : FabricWorlds.vec(p.position());
    }

    @Override
    public Vec3 eyePos() {
        FakePlayer p = p();
        return p == null ? Vec3.ZERO : FabricWorlds.vec(p.getEyePosition());
    }

    @Override
    public Look look() {
        FakePlayer p = p();
        return p == null ? Look.SOUTH : FabricWorlds.look(p);
    }

    @Override
    public boolean isOnGround() {
        FakePlayer p = p();
        return p != null && p.onGround();
    }

    @Override
    public void setLook(Look look) {
        FakePlayer p = p();
        if (p != null) p.actionPack().look(look.yaw(), look.pitch());
    }

    @Override
    public void setVelocity(Vec3 velocity) {
        FakePlayer p = p();
        if (p != null) {
            p.setDeltaMovement(FabricWorlds.mc(velocity));
            p.syncVelocity = true;
        }
    }

    @Override
    public void teleport(Vec3 pos) {
        teleport(pos, look());
    }

    @Override
    public void teleport(Vec3 pos, Look look) {
        FakePlayer p = p();
        if (p != null) p.teleportTo(p.level(), pos.x(), pos.y(), pos.z(), Set.of(), look.yaw(), look.pitch(), true);
    }

    @Override
    public void teleport(World world, Vec3 pos, Look look) {
        FakePlayer p = p();
        if (p != null) p.teleportTo(((FabricWorld) world).handle(), pos.x(), pos.y(), pos.z(), Set.of(), look.yaw(), look.pitch(), true);
    }

    @Override
    public void setProtected(boolean invulnerable) {
        FakePlayer p = p();
        if (p != null) {
            p.setPermanentlyInvulnerable(invulnerable);
            p.getAbilities().invulnerable = invulnerable;
        }
    }

    @Override
    public void setSwimming(boolean swim) {
        // The follower holds jump in water; nothing to switch on.
    }

    // ---- looking ----

    @Override
    public void lookAt(Vec3 target) {
        FakePlayer p = p();
        if (p != null) p.actionPack().lookAt(FabricWorlds.mc(target));
    }

    // ---- moving ----

    @Override
    public void applyNavigationDefaults(Runnable onStuck) {
        FakePlayer p = p();
        if (p != null) p.driver().setDefaultOnStuck(onStuck);
    }

    @Override
    public void navigateTo(Vec3 target) {
        FakePlayer p = p();
        if (p != null) p.driver().goTo(target.block());
    }

    @Override
    public void navigateTo(Vec3 target, Runnable onStuck) {
        FakePlayer p = p();
        if (p != null) p.driver().goTo(target.block(), onStuck);
    }

    @Override
    public void navigateTo(Entity target, boolean aggressive) {
        FakePlayer p = p();
        if (p != null && target instanceof FabricEntity fe) p.driver().follow(fe.handle(), aggressive);
    }

    @Override
    public void cancelNavigation() {
        FakePlayer p = p();
        if (p != null) p.driver().stop();
    }

    @Override
    public boolean isNavigating() {
        FakePlayer p = p();
        return p != null && p.driver().isNavigating();
    }

    @Override
    public void setNavigationPaused(boolean paused) {
        FakePlayer p = p();
        if (p != null) p.driver().setPaused(paused);
    }

    @Override
    public boolean isNavigationPaused() {
        FakePlayer p = p();
        return p != null && p.driver().isPaused();
    }

    // ---- hands ----

    private static EquipmentSlot slot(Slot slot) {
        return switch (slot) {
            case HAND -> EquipmentSlot.MAINHAND;
            case OFF_HAND -> EquipmentSlot.OFFHAND;
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
        };
    }

    @Override
    public Item heldItem() {
        FakePlayer p = p();
        return p == null ? Item.EMPTY : FabricItems.toItem(p.getInventory().getItem(0));
    }

    /** Slot 0 of the inventory is the hand: the selected hotbar slot stays at 0. */
    @Override
    public void setHeldItem(Item item) {
        FakePlayer p = p();
        if (p == null) return;
        p.getInventory().setSelectedSlot(0);
        p.getInventory().setItem(0, stack(item));
    }

    @Override
    public Item equipment(Slot slot) {
        FakePlayer p = p();
        if (p == null) return Item.EMPTY;
        if (slot == Slot.HAND) return heldItem();
        return FabricItems.toItem(p.getItemBySlot(slot(slot)));
    }

    @Override
    public void setEquipment(Slot slot, Item item) {
        if (slot == Slot.HAND) {
            setHeldItem(item);
            return;
        }
        FakePlayer p = p();
        if (p != null) p.setItemSlot(slot(slot), stack(item));
    }

    @Override
    public void swing() {
        FakePlayer p = p();
        if (p != null) p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
    }

    @Override
    public void swingOffHand() {
        FakePlayer p = p();
        if (p != null) p.swing(InteractionHand.OFF_HAND, SwingAnimation.DEFAULT, true);
    }

    @Override
    public boolean hasLineOfSight(Entity target) {
        FakePlayer p = p();
        return p != null && target instanceof FabricEntity fe && p.hasLineOfSight(fe.handle());
    }

    // ---- acting on the world ----

    @Override
    public void breakBlock(BlockPos pos, Item tool, double speedModifier, Consumer<Boolean> onDone) {
        FakePlayer p = p();
        if (p == null) {
            onDone.accept(false);
            return;
        }
        p.breaker().start(FabricWorlds.mc(pos), speedModifier, onDone);
    }

    @Override
    public void cancelBreaking() {
        FakePlayer p = p();
        if (p != null) p.breaker().cancel();
    }

    @Override
    public void attack(Entity target, double damage) {
        FakePlayer p = p();
        if (p == null || !(target instanceof FabricEntity fe)) return;
        ServerLevel level = p.level();
        fe.handle().hurtServer(level, level.damageSources().playerAttack(p), (float) damage);
        swing();
    }

    @Override
    public void shootArrow(Vec3 from, Vec3 direction, double speed, double damage, int knockback, boolean flame) {
        FakePlayer p = p();
        if (p == null) return;
        ServerLevel level = p.level();
        Arrow arrow = new Arrow(level, p, new ItemStack(Items.ARROW), null);
        arrow.setPos(from.x(), from.y(), from.z());
        arrow.shoot(direction.x(), direction.y(), direction.z(), (float) speed, 0f);
        arrow.setBaseDamage(damage);
        arrow.setCritArrow(true);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        if (flame) arrow.igniteForSeconds(5);
        level.addFreshEntity(arrow);
        level.playSound(null, from.x(), from.y(), from.z(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1f, 1f);
        removeLater(arrow);
    }

    @Override
    public void throwTrident(Vec3 from, Vec3 direction, double speed, double damage) {
        FakePlayer p = p();
        if (p == null) return;
        ServerLevel level = p.level();
        ThrownTrident spear = new ThrownTrident(level, p, new ItemStack(Items.TRIDENT));
        spear.setPos(from.x(), from.y(), from.z());
        spear.shoot(direction.x(), direction.y(), direction.z(), (float) speed, 0f);
        spear.setBaseDamage(damage);
        spear.pickup = AbstractArrow.Pickup.DISALLOWED;
        level.addFreshEntity(spear);
        level.playSound(null, from.x(), from.y(), from.z(), SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1f, 1f);
        removeLater(spear);
    }

    /** Never leave a projectile lying in the world if it misses everything. */
    private void removeLater(net.minecraft.world.entity.Entity projectile) {
        butlers.platform().scheduler().later(200L, () -> {
            if (projectile.isAlive()) projectile.discard();
        });
    }

    // ---- carrying ----

    @Override
    public List<Item> inventory() {
        FakePlayer p = p();
        if (p == null) return List.of();
        List<Item> out = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) out.add(FabricItems.toItem(p.getInventory().getItem(i)));
        return out;
    }

    @Override
    public void setInventory(List<Item> items) {
        FakePlayer p = p();
        if (p == null) return;
        for (int i = 0; i < 36; i++) {
            Item item = i < items.size() ? items.get(i) : Item.EMPTY;
            p.getInventory().setItem(i, stack(item));
        }
        p.getInventory().setSelectedSlot(0);
    }

    @Override
    public Item addToInventory(Item item) {
        FakePlayer p = p();
        if (p == null || item.isEmpty()) return item;
        ItemStack stack = stack(item);
        var inv = p.getInventory();
        // Stack with what he has first; slot 0 is his hand and is never used for loot.
        for (int i = 1; i < 36 && !stack.isEmpty(); i++) {
            ItemStack have = inv.getItem(i);
            if (have.isEmpty() || !ItemStack.isSameItemSameComponents(have, stack)) continue;
            int room = have.getMaxStackSize() - have.getCount();
            if (room <= 0) continue;
            int moved = Math.min(room, stack.getCount());
            have.grow(moved);
            stack.shrink(moved);
            inv.setItem(i, have);
        }
        for (int i = 1; i < 36 && !stack.isEmpty(); i++) {
            if (!inv.getItem(i).isEmpty()) continue;
            inv.setItem(i, stack.copy());
            stack.setCount(0);
        }
        return stack.isEmpty() ? Item.EMPTY : item.withCount(stack.getCount());
    }

    @Override
    public void openInventory(Owner viewer) {
        FakePlayer p = p();
        ServerPlayer v = server().getPlayerList().getPlayer(viewer.id());
        if (p == null || v == null) return;
        v.openMenu(new SimpleMenuProvider(
                (id, inventory, player) -> new ButlerInventoryScreen(id, inventory, p),
                Component.literal(p.getName().getString() + "'s Inventory")));
    }

    // ---- surroundings ----

    @Override
    public List<Entity> nearbyEntities(double dx, double dy, double dz) {
        FakePlayer p = p();
        if (p == null) return List.of();
        List<Entity> out = new ArrayList<>();
        for (net.minecraft.world.entity.Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(dx, dy, dz))) {
            out.add(new FabricEntity(e));
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FakeButler other && other.ownerId.equals(ownerId);
    }

    @Override
    public int hashCode() {
        return ownerId.hashCode();
    }
}
