package com.gadgetman.jarvis.fabric.spike.fake;

import com.gadgetman.jarvis.fabric.spike.FakeDriver;
import com.gadgetman.jarvis.fabric.spike.SpikeMod;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.OldUsersConverter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A server-side player with no client: it joins through a connection that
 * goes nowhere and is moved by an {@link ActionPack} instead of packets.
 * Adapted from Carpet's EntityPlayerMPFake (MIT, see
 * THIRD-PARTY-LICENSES.md): no shadowing, respawning or saved player data,
 * and a spawn that falls back to an offline profile instead of failing.
 */
public class FakePlayer extends ServerPlayer {

    public Runnable fixStartingPosition = () -> { };
    private FakeDriver driver;

    /**
     * Spawn one at a position, facing a way, wearing the skin of the Mojang
     * account of that name when the server can look it up. The lookup is
     * asynchronous, so the player is handed to {@code onSpawned} on the
     * server thread once he is in the world, or null if placing him threw.
     * Without a lookup (offline mode, no such account, no network) he spawns
     * with an offline profile and a default skin.
     */
    public static void spawn(String username, MinecraftServer server, ServerLevel level, Vec3 pos,
                             float yaw, float pitch, GameType gameMode, Consumer<FakePlayer> onSpawned) {
        server.services().nameToIdCache().resolveOfflineUsers(false);
        UUID uuid = OldUsersConverter.convertMobOwnerIfNecessary(server, username);
        if (uuid == null) {
            server.services().nameToIdCache().resolveOfflineUsers(server.isDedicatedServer() && server.usesAuthentication());
            uuid = UUIDUtil.createOfflinePlayerUUID(username);
        }
        GameProfile bare = new GameProfile(uuid, username);
        ResolvableProfile.createUnresolved(uuid).resolveProfile(server.services().profileResolver())
                .whenCompleteAsync((resolved, error) -> {
                    boolean dressed = error == null && resolved != null && !resolved.name().isEmpty();
                    if (!dressed) {
                        SpikeMod.LOG.info("No uniform for {}: {}", username,
                                error == null ? "no profile came back" : error.toString());
                    }
                    FakePlayer placed;
                    try {
                        placed = place(server, level, dressed ? resolved : bare, pos, yaw, pitch, gameMode);
                    } catch (RuntimeException e) {
                        SpikeMod.LOG.error("Could not spawn {}", username, e);
                        placed = null;
                    }
                    onSpawned.accept(placed);
                }, server);
    }

    /** Put a player with this profile into the world. Runs on the server thread. */
    private static FakePlayer place(MinecraftServer server, ServerLevel level, GameProfile profile, Vec3 pos,
                                    float yaw, float pitch, GameType gameMode) {
        FakePlayer instance = new FakePlayer(server, level, profile, ClientInformation.createDefault());
        instance.fixStartingPosition = () -> instance.snapTo(pos.x, pos.y, pos.z, yaw, pitch);
        server.getPlayerList().placeNewPlayer(new FakeConnection(PacketFlow.SERVERBOUND), instance,
                new CommonListenerCookie(profile, 0, instance.clientInformation(), false));
        instance.stopRiding();
        instance.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), yaw, pitch, true);
        instance.setHealth(20.0F);
        instance.unsetRemoved();
        instance.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6F);
        instance.gameMode.changeGameModeForPlayer(gameMode);
        server.getPlayerList().broadcastAll(new ClientboundRotateHeadPacket(instance, (byte) (instance.yHeadRot * 256 / 360)), level.dimension());
        server.getPlayerList().broadcastAll(ClientboundEntityPositionSyncPacket.of(instance), level.dimension());
        instance.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f); // all model layers
        return instance;
    }

    private FakePlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation cli) {
        super(server, level, profile, cli);
    }

    public ActionPack actionPack() {
        return ((ActionPackHolder) this).jarvis$actionPack();
    }

    public FakeDriver driver() {
        if (driver == null) driver = new FakeDriver(this);
        return driver;
    }

    @Override
    public void kill(ServerLevel level) {
        kill(Component.literal("Killed"));
    }

    /** Leave the server, next tick. */
    public void kill(Component reason) {
        MinecraftServer server = level().getServer();
        server.schedule(new TickTask(server.getTickCount(),
                () -> this.connection.onDisconnect(new DisconnectionDetails(reason))));
    }

    @Override
    public void tick() {
        if (level().getServer().getTickCount() % 10 == 0) {
            this.connection.resetPosition();
            this.level().getChunkSource().move(this);
        }
        // Decide this tick's controls before the action pack applies them
        // (it runs at the head of super.tick()).
        if (driver != null) driver.tick();
        super.tick();
        this.doTick();
    }

    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        setHealth(20);
        this.foodData = new FoodData();
        kill(this.getCombatTracker().getDeathMessage());
    }

    @Override
    public String getIpAddress() {
        return "127.0.0.1";
    }

    @Override
    public boolean allowsListing() {
        return true;
    }
}
