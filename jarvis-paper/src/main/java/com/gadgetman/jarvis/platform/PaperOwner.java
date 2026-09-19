package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Core's {@link Owner} over a player id.
 *
 * <p>Looks the live player up on every call, so a handle made before a relog
 * is still good after it, and never keeps a stale {@link Player} alive.
 */
public final class PaperOwner implements Owner {

    private final UUID id;

    public PaperOwner(UUID id) {
        this.id = id;
    }

    /** The live player, when online. */
    public Optional<Player> player() {
        return Optional.ofNullable(Bukkit.getPlayer(id));
    }

    private Player p() {
        return Bukkit.getPlayer(id);
    }

    @Override public UUID id() { return id; }

    @Override
    public String name() {
        Player p = p();
        if (p != null) return p.getName();
        String offline = Bukkit.getOfflinePlayer(id).getName();
        return offline != null ? offline : id.toString();
    }

    @Override public boolean isOnline() { return p() != null; }

    @Override
    public boolean isOp() {
        Player p = p();
        return p != null && p.isOp();
    }

    @Override
    public boolean hasPermission(String node) {
        Player p = p();
        return p != null && p.hasPermission(node);
    }

    @Override
    public WorldId world() {
        Player p = p();
        return p == null ? WorldId.OVERWORLD : PaperWorlds.id(p.getWorld());
    }

    @Override
    public Vec3 pos() {
        Player p = p();
        return p == null ? Vec3.ZERO : PaperWorlds.vec(p.getLocation());
    }

    @Override
    public Vec3 eyePos() {
        Player p = p();
        return p == null ? Vec3.ZERO : PaperWorlds.vec(p.getEyeLocation());
    }

    @Override
    public Look look() {
        Player p = p();
        return p == null ? Look.SOUTH : PaperWorlds.look(p.getLocation());
    }

    @Override
    public double health() {
        Player p = p();
        return p == null ? 0 : p.getHealth();
    }

    @Override
    @SuppressWarnings("deprecation")
    public double maxHealth() {
        Player p = p();
        return p == null ? 20 : p.getMaxHealth();
    }

    @Override
    public int level() {
        Player p = p();
        return p == null ? 0 : p.getLevel();
    }

    @Override
    public int foodLevel() {
        Player p = p();
        return p == null ? 20 : p.getFoodLevel();
    }

    @Override
    public double heldItemWear() {
        Player p = p();
        if (p == null) return -1;
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) return -1;
        int max = held.getType().getMaxDurability();
        if (max <= 20) return -1;                       // not a tool, or wears too little to matter
        if (!(held.getItemMeta() instanceof Damageable d)) return -1;
        return d.getDamage() / (double) max;
    }

    @Override
    public boolean isSneaking() {
        Player p = p();
        return p != null && p.isSneaking();
    }

    @Override
    public String gameMode() {
        Player p = p();
        return p == null ? "survival" : p.getGameMode().name().toLowerCase(Locale.ROOT);
    }

    @Override
    public Item heldItem() {
        Player p = p();
        return p == null ? Item.EMPTY : PaperItems.toItem(p.getInventory().getItemInMainHand());
    }

    @Override
    public List<Item> inventory() {
        Player p = p();
        return p == null ? List.of() : PaperItems.toItems(p.getInventory().getStorageContents());
    }

    @Override
    public void give(Item item) {
        Player p = p();
        if (p == null || item.isEmpty()) return;
        for (ItemStack rest : p.getInventory().addItem(PaperItems.toStack(item)).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    @Override
    public Optional<BlockPos> targetBlock(double reach) {
        Player p = p();
        if (p == null) return Optional.empty();
        Block b = p.getTargetBlockExact((int) Math.ceil(reach));
        return b == null ? Optional.empty() : Optional.of(new BlockPos(b.getX(), b.getY(), b.getZ()));
    }

    @Override
    public Site respawnPlace() {
        Player p = p();
        if (p == null) {
            org.bukkit.World w = Bukkit.getWorlds().get(0);
            return new Site(new PaperWorld(w), PaperWorlds.vec(w.getSpawnLocation()));
        }
        Location home = p.getRespawnLocation();
        if (home == null) home = p.getWorld().getSpawnLocation();
        return PaperWorlds.site(home);
    }

    @Override
    public void teleport(WorldId world, Vec3 pos, Look look) {
        Player p = p();
        org.bukkit.World w = PaperWorlds.bukkitWorld(world);
        if (p == null || w == null) return;
        p.teleport(PaperWorlds.location(w, pos, look));
    }

    @Override
    public void message(String text) {
        Player p = p();
        if (p != null && text != null) p.sendMessage(LegacyComponentSerializer.legacySection().deserialize(text));
    }

    @Override
    public void actionBar(String text) {
        Player p = p();
        if (p != null && text != null) p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(text));
    }

    @Override
    public void sound(String soundId, float volume, float pitch) {
        Player p = p();
        if (p != null) p.playSound(p.getLocation(), soundId, volume, pitch);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Owner other && other.id().equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return name();
    }
}
