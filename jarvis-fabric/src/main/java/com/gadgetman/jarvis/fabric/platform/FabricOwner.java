package com.gadgetman.jarvis.fabric.platform;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.text.RichLine;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;
import com.gadgetman.jarvis.fabric.fake.FakePlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Core's {@link Owner} over a player id. Looks the live player up on every
 * call, so a handle made before a relog is still good after it.
 */
public final class FabricOwner implements Owner {

    private final MinecraftServer server;
    private final UUID id;

    public FabricOwner(MinecraftServer server, UUID id) {
        this.server = server;
        this.id = id;
    }

    /** The live player, when online. Never a fake one. */
    public Optional<ServerPlayer> player() {
        return Optional.ofNullable(p());
    }

    private ServerPlayer p() {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p instanceof FakePlayer ? null : p;
    }

    @Override public UUID id() { return id; }

    @Override
    public String name() {
        ServerPlayer p = p();
        if (p != null) return p.getName().getString();
        return server.services().nameToIdCache().get(id).map(NameAndId::name).orElse(id.toString());
    }

    @Override public boolean isOnline() { return p() != null; }

    @Override
    public boolean isOp() {
        ServerPlayer p = p();
        return p != null && server.getPlayerList().isOp(p.nameAndId());
    }

    /** Fabric has no permission nodes: admin nodes need op, everything else is open. */
    @Override
    public boolean hasPermission(String node) {
        if (p() == null) return false;
        return !node.contains("admin") || isOp();
    }

    @Override
    public WorldId world() {
        ServerPlayer p = p();
        return p == null ? WorldId.OVERWORLD : FabricWorlds.id(p.level());
    }

    @Override
    public Vec3 pos() {
        ServerPlayer p = p();
        return p == null ? Vec3.ZERO : FabricWorlds.vec(p.position());
    }

    @Override
    public Vec3 eyePos() {
        ServerPlayer p = p();
        return p == null ? Vec3.ZERO : FabricWorlds.vec(p.getEyePosition());
    }

    @Override
    public Look look() {
        ServerPlayer p = p();
        return p == null ? Look.SOUTH : FabricWorlds.look(p);
    }

    @Override
    public double health() {
        ServerPlayer p = p();
        return p == null ? 0 : p.getHealth();
    }

    @Override
    public double maxHealth() {
        ServerPlayer p = p();
        return p == null ? 20 : p.getMaxHealth();
    }

    @Override
    public int level() {
        ServerPlayer p = p();
        return p == null ? 0 : p.experienceLevel;
    }

    @Override
    public int foodLevel() {
        ServerPlayer p = p();
        return p == null ? 20 : p.getFoodData().getFoodLevel();
    }

    @Override
    public double heldItemWear() {
        ServerPlayer p = p();
        if (p == null) return -1;
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty() || !held.isDamageableItem()) return -1;
        int max = held.getMaxDamage();
        if (max <= 20) return -1;                       // not a tool, or wears too little to matter
        return held.getDamageValue() / (double) max;
    }

    @Override
    public boolean isSneaking() {
        ServerPlayer p = p();
        return p != null && p.isShiftKeyDown();
    }

    @Override
    public String gameMode() {
        ServerPlayer p = p();
        return p == null ? "survival" : p.gameMode.getGameModeForPlayer().getName().toLowerCase(Locale.ROOT);
    }

    @Override
    public Item heldItem() {
        ServerPlayer p = p();
        return p == null ? Item.EMPTY : FabricItems.toItem(p.getMainHandItem());
    }

    @Override
    public List<Item> inventory() {
        ServerPlayer p = p();
        if (p == null) return List.of();
        List<Item> out = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) out.add(FabricItems.toItem(p.getInventory().getItem(i)));
        return out;
    }

    @Override
    public void give(Item item) {
        ServerPlayer p = p();
        if (p == null || item.isEmpty()) return;
        ItemStack stack = FabricItems.toStack(server, item);
        p.getInventory().add(stack);
        if (!stack.isEmpty()) {
            ItemEntity drop = new ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), stack);
            drop.setDefaultPickUpDelay();
            p.level().addFreshEntity(drop);
        }
    }

    @Override
    public Optional<BlockPos> targetBlock(double reach) {
        ServerPlayer p = p();
        if (p == null) return Optional.empty();
        HitResult hit = p.pick(reach, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) return Optional.empty();
        return Optional.of(FabricWorlds.block(((BlockHitResult) hit).getBlockPos()));
    }

    @Override
    public Site respawnPlace() {
        ServerPlayer p = p();
        ServerLevel level = p == null ? server.overworld() : p.level();
        if (p != null && p.getRespawnConfig() != null) {
            var data = p.getRespawnConfig().respawnData();
            ServerLevel home = server.getLevel(data.dimension());
            if (home != null) {
                net.minecraft.core.BlockPos bed = data.pos();
                return new Site(new FabricWorld(home), new Vec3(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5));
            }
        }
        return new Site(new FabricWorld(level), new FabricWorld(level).spawn());
    }

    @Override
    public void teleport(WorldId world, Vec3 pos, Look look) {
        ServerPlayer p = p();
        ServerLevel level = FabricWorlds.level(server, world);
        if (p == null || level == null) return;
        p.teleportTo(level, pos.x(), pos.y(), pos.z(), Set.of(), look.yaw(), look.pitch(), true);
    }

    @Override
    public void message(String text) {
        ServerPlayer p = p();
        if (p != null && text != null) p.sendSystemMessage(Component.literal(text));
    }

    @Override
    public void rich(RichLine line) {
        ServerPlayer p = p();
        if (p == null) return;
        MutableComponent out = Component.empty();
        for (RichLine.Part part : line.parts()) {
            Style style = Style.EMPTY;
            if (part.command() != null) style = style.withClickEvent(new ClickEvent.RunCommand(part.command()));
            if (part.hover() != null) style = style.withHoverEvent(new HoverEvent.ShowText(Component.literal(part.hover())));
            out = out.append(Component.literal(part.text()).withStyle(style));
        }
        p.sendSystemMessage(out);
    }

    @Override
    public void actionBar(String text) {
        ServerPlayer p = p();
        if (p != null && text != null) p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(text)));
    }

    @Override
    public void sound(String soundId, float volume, float pitch) {
        ServerPlayer p = p();
        SoundEvent sound = FabricWorld.sound(soundId);
        if (p != null && sound != null) {
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
        }
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
