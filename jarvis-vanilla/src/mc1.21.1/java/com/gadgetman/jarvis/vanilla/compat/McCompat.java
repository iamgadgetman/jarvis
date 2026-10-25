package com.gadgetman.jarvis.vanilla.compat;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Unit;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The game calls that differ between Minecraft versions, for 1.21.1 (the
 * version All the Mods 10 is built on). The 26.x copies are in mc26.1 and
 * mc26.3; a build picks one source directory by its {@code mc_compat}
 * property. 1.21.1 predates the 1.21.2-1.21.11 renames: ResourceLocation
 * rather than Identifier, ServerPlayer.serverLevel() rather than a
 * ServerLevel-typed level(), getMin/MaxBuildHeight rather than getMin/MaxY,
 * hurt() rather than hurtServer(), and an InteractionResult that is still an
 * enum. Classes whose overridden signatures differ, and so cannot go through
 * here, have whole 1.21.1 copies beside this one.
 */
public final class McCompat {

    private McCompat() {
    }

    /** Swings the arm and tells every watching client, the swinger included. */
    public static void swing(LivingEntity entity, InteractionHand hand) {
        entity.swing(hand, true);
    }

    /** Marks the entity's velocity to be sent to clients on the next tick. */
    public static void syncVelocity(Entity entity) {
        entity.hurtMarked = true;
    }

    /** Invulnerable to everything but what bypasses invulnerability (the void, /kill). */
    public static void setInvulnerable(Entity entity, boolean invulnerable) {
        entity.setInvulnerable(invulnerable);
    }

    /** True when the server, not the client, is expected to swing the arm for this result. */
    public static boolean serverSwings(InteractionResult result) {
        return result.shouldSwing();
    }

    /** True when using an item on a block did something. */
    public static boolean succeeded(InteractionResult result) {
        return result.consumesAction();
    }

    /** A server player's level, typed as the server's. */
    public static ServerLevel level(ServerPlayer player) {
        return player.serverLevel();
    }

    /** Lowest buildable y. */
    public static int minY(Level level) {
        return level.getMinBuildHeight();
    }

    /** Highest buildable y, inclusive. 1.21.1's getMaxBuildHeight is exclusive. */
    public static int maxY(Level level) {
        return level.getMaxBuildHeight() - 1;
    }

    /**
     * The height the game's own block-interaction checks are given (useItemOn,
     * handleBlockBreakAction). Not quite {@link #maxY}: its meaning moved by
     * one between versions, and these calls mirror each version's own code.
     */
    public static int interactionHeight(Level level) {
        return level.getMaxBuildHeight();
    }

    /** The day-cycle clock, before the modulo. */
    public static long dayTime(ServerLevel level) {
        return level.getDayTime();
    }

    /** The world spawn. */
    public static BlockPos spawnPos(ServerLevel level) {
        return level.getSharedSpawnPos();
    }

    /** As /weather does: the server's weather, which lives on the overworld. */
    public static void setWeather(MinecraftServer server, int clearTicks, int rainTicks, boolean raining, boolean thundering) {
        server.overworld().setWeatherParameters(clearTicks, rainTicks, raining, thundering);
    }

    /** Teleport without relative movement, dismounting as a command would. */
    public static void teleport(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        player.teleportTo(level, x, y, z, yaw, pitch);
    }

    public static void hurt(Entity entity, ServerLevel level, DamageSource source, float amount) {
        entity.hurt(source, amount);
    }

    public static void selectSlot(Player player, int slot) {
        player.getInventory().selected = slot;
    }

    /** A registry entry by its full id: the registry's default when absent, null when the id does not parse. */
    public static <T> T lookup(Registry<T> registry, String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? null : registry.get(key);
    }

    public static boolean contains(Registry<?> registry, String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key != null && registry.containsKey(key);
    }

    /** A resource key by its full id, or null when the id does not parse. */
    public static <T> ResourceKey<T> key(ResourceKey<? extends Registry<T>> registry, String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? null : ResourceKey.create(registry, key);
    }

    public static <T> TagKey<T> tag(ResourceKey<? extends Registry<T>> registry, String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? null : TagKey.create(registry, key);
    }

    /** "minecraft:the_nether" */
    public static String id(ResourceKey<?> key) {
        return key.location().toString();
    }

    /** "the_nether" */
    public static String path(ResourceKey<?> key) {
        return key.location().getPath();
    }

    public static Set<String> paths(Registry<?> registry) {
        Set<String> out = new HashSet<>();
        for (ResourceLocation key : registry.keySet()) out.add(key.getPath());
        return out;
    }

    public static String string(CompoundTag tag, String key) {
        return tag.contains(key) ? tag.getString(key) : null;
    }

    /** No tooltip at all, not even an empty box. */
    public static void hideTooltip(ItemStack stack) {
        stack.set(DataComponents.HIDE_TOOLTIP, Unit.INSTANCE);
    }

    public static void setUnbreakable(ItemStack stack) {
        stack.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
    }

    /** A known player's name from the server's cache. */
    public static Optional<String> cachedName(MinecraftServer server, UUID id) {
        GameProfileCache cache = server.getProfileCache();
        return cache == null ? Optional.empty() : cache.get(id).map(GameProfile::getName);
    }

    public static boolean isOp(MinecraftServer server, ServerPlayer player) {
        return server.getPlayerList().isOp(player.getGameProfile());
    }

    /** Where the player has set a respawn point (a bed, an anchor), if anywhere. */
    public static Optional<GlobalPos> respawnPoint(ServerPlayer player) {
        BlockPos pos = player.getRespawnPosition();
        return pos == null ? Optional.empty() : Optional.of(GlobalPos.of(player.getRespawnDimension(), pos));
    }

    public static ClickEvent runCommand(String command) {
        return new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
    }

    public static HoverEvent showText(Component text) {
        return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
    }

    public static ServerBossEvent bossBar(Component title, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
        return new ServerBossEvent(title, color, overlay);
    }

    public static UUID profileId(GameProfile profile) {
        return profile.getId();
    }


    /** A profile with this name and the given one's textures, under a fresh id. */
    public static GameProfile withFreshId(GameProfile profile, String name) {
        GameProfile fresh = new GameProfile(UUID.randomUUID(), name);
        fresh.getProperties().putAll(profile.getProperties());
        return fresh;
    }

    /** A crit arrow that cannot be picked up. */
    public static Projectile arrow(ServerLevel level, LivingEntity shooter, double damage, boolean flame) {
        Arrow arrow = new Arrow(level, shooter, new ItemStack(Items.ARROW), null);
        arrow.setBaseDamage(damage);
        arrow.setCritArrow(true);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        if (flame) arrow.igniteForSeconds(5);
        return arrow;
    }

    /** A trident that cannot be picked up. */
    public static Projectile trident(ServerLevel level, LivingEntity shooter, double damage) {
        ThrownTrident spear = new ThrownTrident(level, shooter, new ItemStack(Items.TRIDENT));
        spear.setBaseDamage(damage);
        spear.pickup = AbstractArrow.Pickup.DISALLOWED;
        return spear;
    }
}
