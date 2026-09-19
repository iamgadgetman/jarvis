package com.gadgetman.jarvis.npc.provider;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Slot;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.platform.PaperEntity;
import com.gadgetman.jarvis.platform.PaperItems;
import com.gadgetman.jarvis.platform.PaperWorld;
import com.gadgetman.jarvis.platform.PaperWorlds;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Inventory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Core's {@link Butler} over a Citizens NPC.
 *
 * <p>A handle keyed on the owner: it looks the NPC up in the provider's
 * registry on every call, so it is valid before he is summoned and after he
 * is dismissed, and answers with safe defaults in between.
 */
public final class CitizensButler implements Butler {

    /** Refuse to teleport out of being stuck; the tick loops handle recovery. */
    private static final net.citizensnpcs.api.ai.StuckAction NO_TELEPORT = (n, navigator) -> false;

    private final Jarvis plugin;
    private final CitizensNPCProvider provider;
    private final UUID ownerId;

    CitizensButler(Jarvis plugin, CitizensNPCProvider provider, UUID ownerId) {
        this.plugin = plugin;
        this.provider = provider;
        this.ownerId = ownerId;
    }

    private NPC npc() {
        return provider.getCitizensNPC(ownerId);
    }

    private NPC spawnedNpc() {
        NPC npc = npc();
        return npc != null && npc.isSpawned() && npc.getEntity() != null ? npc : null;
    }

    private Player player() {
        return Bukkit.getPlayer(ownerId);
    }

    private Location location() {
        NPC npc = npc();
        if (npc == null) return null;
        if (npc.getEntity() != null) return npc.getEntity().getLocation();
        return npc.getStoredLocation();
    }

    private Location here(Vec3 v) {
        Location at = location();
        org.bukkit.World w = at != null ? at.getWorld() : Bukkit.getWorlds().get(0);
        return PaperWorlds.location(w, v);
    }

    // ---- identity and place ----

    @Override public Owner owner() { return plugin.getPlatform().players().owner(ownerId); }
    @Override public boolean isSpawned() { return spawnedNpc() != null; }

    @Override
    public Optional<Entity> entity() {
        NPC npc = spawnedNpc();
        return npc == null ? Optional.empty() : Optional.of(new PaperEntity(npc.getEntity()));
    }

    @Override
    public Optional<World> world() {
        Location at = location();
        return at == null || at.getWorld() == null ? Optional.empty() : Optional.of(new PaperWorld(at.getWorld()));
    }

    @Override
    public Vec3 pos() {
        Location at = location();
        return at == null ? Vec3.ZERO : PaperWorlds.vec(at);
    }

    @Override
    public Vec3 eyePos() {
        NPC npc = spawnedNpc();
        if (npc != null && npc.getEntity() instanceof LivingEntity le) return PaperWorlds.vec(le.getEyeLocation());
        return pos().add(0, 1.62, 0);
    }

    @Override
    public Look look() {
        Location at = location();
        return at == null ? Look.SOUTH : PaperWorlds.look(at);
    }

    @Override
    public boolean isOnGround() {
        NPC npc = spawnedNpc();
        return npc != null && npc.getEntity().isOnGround();
    }

    @Override
    public void setLook(Look look) {
        NPC npc = spawnedNpc();
        if (npc != null) npc.getEntity().setRotation(look.yaw(), look.pitch());
    }

    @Override
    public void setVelocity(Vec3 velocity) {
        NPC npc = spawnedNpc();
        if (npc != null) npc.getEntity().setVelocity(PaperWorlds.vector(velocity));
    }

    @Override
    public void teleport(Vec3 pos) {
        teleport(pos, look());
    }

    @Override
    public void teleport(Vec3 pos, Look look) {
        NPC npc = spawnedNpc();
        if (npc == null) return;
        npc.teleport(PaperWorlds.location(npc.getEntity().getWorld(), pos, look), PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    @Override
    public void teleport(World world, Vec3 pos, Look look) {
        NPC npc = spawnedNpc();
        if (npc == null) return;
        npc.teleport(PaperWorlds.location(PaperWorlds.handle(world), pos, look), PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    @Override
    public void setProtected(boolean invulnerable) {
        NPC npc = npc();
        if (npc != null) npc.setProtected(invulnerable);
    }

    @Override
    public void setSwimming(boolean swim) {
        NPC npc = npc();
        if (npc != null) npc.data().setPersistent(NPC.Metadata.SWIM, swim);
    }

    // ---- looking ----

    @Override
    public void lookAt(Vec3 target) {
        NPC npc = spawnedNpc();
        if (npc != null) npc.faceLocation(here(target));
    }

    // ---- moving ----

    @Override
    public void applyNavigationDefaults(Runnable onStuck) {
        NPC npc = npc();
        if (npc == null) return;
        boolean useAsync = plugin.getConfig().getBoolean("mining.use-async-pathfinder", true);
        double navRange = plugin.getConfig().getDouble("mining.navigator-range", 64.0);

        NavigatorParameters params = npc.getNavigator().getDefaultParameters();
        params.useNewPathfinder(true);            // Citizens A*
        if (useAsync) {
            params.pathfinderType(net.citizensnpcs.api.ai.PathfinderType.CITIZENS_ASYNC);
        }
        params.range((float) navRange);
        params.stationaryTicks(60);               // 3s without movement = stuck
        params.updatePathRate(40);                // repath at most every 2s
        params.distanceMargin(2.0);
        params.pathDistanceMargin(1.0);
        params.baseSpeed(1.0f);
        params.speedModifier(1.1f);
        params.stuckAction(onStuck == null ? NO_TELEPORT : (n, nav) -> { onStuck.run(); return false; });
        // Let the A* route THROUGH water (swim paths) instead of treating
        // ponds as walls he then blunders into and wedges under.
        if (!params.hasExaminer(net.citizensnpcs.api.astar.pathfinder.SwimmingExaminer.class)) {
            params.examiner(new net.citizensnpcs.api.astar.pathfinder.SwimmingExaminer());
        }
    }

    @Override
    public void navigateTo(Vec3 target) {
        NPC npc = spawnedNpc();
        if (npc == null) return;
        Navigator nav = npc.getNavigator();
        if (nav != null) nav.setTarget(here(target));
    }

    @Override
    public void navigateTo(Vec3 target, Runnable onStuck) {
        NPC npc = spawnedNpc();
        if (npc == null) return;
        Navigator nav = npc.getNavigator();
        if (nav.isNavigating()) nav.cancelNavigation();
        nav.setTarget(here(target));
        nav.getLocalParameters().stuckAction(
                onStuck == null ? NO_TELEPORT : (n, navigator) -> { onStuck.run(); return false; });
    }

    @Override
    public void navigateTo(Entity target, boolean aggressive) {
        NPC npc = spawnedNpc();
        if (npc == null || !(target instanceof PaperEntity pe)) return;
        Navigator nav = npc.getNavigator();
        if (nav != null) nav.setTarget(pe.handle(), aggressive);
    }

    @Override
    public void cancelNavigation() {
        NPC npc = npc();
        if (npc != null && npc.getNavigator() != null) npc.getNavigator().cancelNavigation();
    }

    @Override
    public boolean isNavigating() {
        NPC npc = npc();
        return npc != null && npc.getNavigator() != null && npc.getNavigator().isNavigating();
    }

    @Override
    public void setNavigationPaused(boolean paused) {
        NPC npc = npc();
        if (npc != null && npc.getNavigator() != null) npc.getNavigator().setPaused(paused);
    }

    @Override
    public boolean isNavigationPaused() {
        NPC npc = npc();
        return npc != null && npc.getNavigator() != null && npc.getNavigator().isPaused();
    }

    // ---- hands ----

    private static Equipment.EquipmentSlot slot(Slot slot) {
        return switch (slot) {
            case HAND -> Equipment.EquipmentSlot.HAND;
            case OFF_HAND -> Equipment.EquipmentSlot.OFF_HAND;
            case HEAD -> Equipment.EquipmentSlot.HELMET;
            case CHEST -> Equipment.EquipmentSlot.CHESTPLATE;
            case LEGS -> Equipment.EquipmentSlot.LEGGINGS;
            case FEET -> Equipment.EquipmentSlot.BOOTS;
        };
    }

    @Override
    public Item heldItem() {
        return equipment(Slot.HAND);
    }

    /** Slot 0 of the inventory IS the hand for player NPCs, so both are kept in step. */
    @Override
    public void setHeldItem(Item item) {
        NPC npc = npc();
        if (npc == null) return;
        ItemStack stack = PaperItems.toStack(item);
        Inventory inv = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = inv.getContents();
        if (contents.length > 0) {
            contents[0] = stack;
            inv.setContents(contents);
        }
        npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, stack);
    }

    @Override
    public Item equipment(Slot slot) {
        NPC npc = npc();
        if (npc == null) return Item.EMPTY;
        return PaperItems.toItem(npc.getOrAddTrait(Equipment.class).get(slot(slot)));
    }

    @Override
    public void setEquipment(Slot slot, Item item) {
        if (slot == Slot.HAND) {
            setHeldItem(item);
            return;
        }
        NPC npc = npc();
        if (npc != null) npc.getOrAddTrait(Equipment.class).set(slot(slot), PaperItems.toStack(item));
    }

    @Override
    public void swing() {
        NPC npc = spawnedNpc();
        if (npc != null && npc.getEntity() instanceof LivingEntity le) le.swingMainHand();
    }

    @Override
    public void swingOffHand() {
        NPC npc = spawnedNpc();
        if (npc != null && npc.getEntity() instanceof LivingEntity le) le.swingOffHand();
    }

    @Override
    public boolean hasLineOfSight(Entity target) {
        NPC npc = spawnedNpc();
        return npc != null && npc.getEntity() instanceof LivingEntity le
                && target instanceof PaperEntity pe && le.hasLineOfSight(pe.handle());
    }

    // ---- acting on the world ----

    @Override
    public void breakBlock(BlockPos pos, Item tool, double speedModifier, Consumer<Boolean> onDone) {
        NPC npc = spawnedNpc();
        Player player = player();
        if (npc == null || player == null) {
            onDone.accept(false);
            return;
        }
        org.bukkit.block.Block block = npc.getEntity().getWorld().getBlockAt(pos.x(), pos.y(), pos.z());
        provider.breakBlock(player, block, PaperItems.toStack(tool), speedModifier, onDone);
    }

    @Override
    public void cancelBreaking() {
        NPC npc = npc();
        if (npc != null) provider.cancelBreaking(npc.getUniqueId());
    }

    @Override
    public void attack(Entity target, double damage) {
        NPC npc = spawnedNpc();
        if (npc == null || !(target instanceof PaperEntity pe)) return;
        if (pe.handle() instanceof LivingEntity le) le.damage(damage, npc.getEntity());
    }

    @Override
    public void shootArrow(Vec3 from, Vec3 direction, double speed, double damage, int knockback, boolean flame) {
        NPC npc = spawnedNpc();
        if (npc == null) return;
        org.bukkit.entity.Entity self = npc.getEntity();
        Location muzzle = PaperWorlds.location(self.getWorld(), from);
        Vector dir = PaperWorlds.vector(direction);
        Arrow arrow = muzzle.getWorld().spawn(muzzle, Arrow.class, a -> {
            a.setShooter(self instanceof ProjectileSource src ? src : null);
            a.setVelocity(dir.clone().multiply(speed));
            a.setDamage(damage);
            a.setKnockbackStrength(knockback);
            a.setCritical(true);
            a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            if (flame) a.setFireTicks(100);
        });
        muzzle.getWorld().playSound(muzzle, Sound.ENTITY_ARROW_SHOOT, 1f, 1f);
        removeLater(arrow);
    }

    @Override
    public void throwTrident(Vec3 from, Vec3 direction, double speed, double damage) {
        NPC npc = spawnedNpc();
        if (npc == null) return;
        org.bukkit.entity.Entity self = npc.getEntity();
        Location at = PaperWorlds.location(self.getWorld(), from);
        Vector dir = PaperWorlds.vector(direction);
        Trident spear = at.getWorld().spawn(at, Trident.class, t -> {
            t.setShooter(self instanceof ProjectileSource src ? src : null);
            t.setVelocity(dir.clone().multiply(speed));
            t.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            t.setDamage(damage);
        });
        at.getWorld().playSound(at, Sound.ITEM_TRIDENT_THROW, 1f, 1f);
        removeLater(spear);
    }

    /** Never leave a projectile lying in the world if it misses everything. */
    private void removeLater(org.bukkit.entity.Entity projectile) {
        new BukkitRunnable() {
            @Override public void run() {
                if (projectile.isValid()) projectile.remove();
            }
        }.runTaskLater(plugin, 200L);
    }

    // ---- carrying ----

    @Override
    public List<Item> inventory() {
        NPC npc = npc();
        if (npc == null) return List.of();
        return PaperItems.toItems(npc.getOrAddTrait(Inventory.class).getContents());
    }

    @Override
    public void setInventory(List<Item> items) {
        NPC npc = npc();
        if (npc == null) return;
        Inventory inv = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            Item item = i < items.size() ? items.get(i) : Item.EMPTY;
            contents[i] = item.isEmpty() ? null : PaperItems.toStack(item);
        }
        inv.setContents(contents);
        if (!items.isEmpty()) {
            npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND,
                    items.get(0).isEmpty() ? null : PaperItems.toStack(items.get(0)));
        }
    }

    @Override
    public boolean addToInventory(Item item) {
        NPC npc = npc();
        if (npc == null || item.isEmpty()) return false;
        ItemStack stack = PaperItems.toStack(item);
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();

        // Stack with what he has first; slot 0 is his hand and is never used for loot.
        for (int i = 1; i < contents.length; i++) {
            if (contents[i] != null && contents[i].isSimilar(stack)) {
                int canAdd = contents[i].getMaxStackSize() - contents[i].getAmount();
                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, stack.getAmount());
                    contents[i].setAmount(contents[i].getAmount() + toAdd);
                    stack.setAmount(stack.getAmount() - toAdd);
                    if (stack.getAmount() <= 0) {
                        invTrait.setContents(contents);
                        return true;
                    }
                }
            }
        }
        for (int i = 1; i < contents.length; i++) {
            if (contents[i] == null || contents[i].getType() == Material.AIR) {
                contents[i] = stack;
                invTrait.setContents(contents);
                return true;
            }
        }
        return false;
    }

    @Override
    public void openInventory(Owner viewer) {
        NPC npc = npc();
        Player p = Bukkit.getPlayer(viewer.id());
        if (npc != null && p != null) npc.getOrAddTrait(Inventory.class).openInventory(p);
    }

    // ---- surroundings ----

    @Override
    public List<Entity> nearbyEntities(double dx, double dy, double dz) {
        NPC npc = spawnedNpc();
        if (npc == null) return List.of();
        List<Entity> out = new ArrayList<>();
        for (org.bukkit.entity.Entity e : npc.getEntity().getNearbyEntities(dx, dy, dz)) {
            out.add(new PaperEntity(e));
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CitizensButler other && other.ownerId.equals(ownerId);
    }

    @Override
    public int hashCode() {
        return ownerId.hashCode();
    }
}
