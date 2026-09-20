package com.gadgetman.jarvis.vanilla.fake;

import com.gadgetman.jarvis.vanilla.butler.BlockBreaker;
import com.gadgetman.jarvis.vanilla.butler.FakeDriver;
import com.mojang.authlib.GameProfile;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * A server-side player with no client: it joins through a connection that
 * goes nowhere and is moved by an {@link ActionPack} instead of packets.
 * Adapted from Carpet's EntityPlayerMPFake (MIT, see
 * THIRD-PARTY-LICENSES.md): no shadowing, respawning or saved player data.
 * The butler is one of these.
 */
public class FakePlayer extends ServerPlayer {

    public Runnable fixStartingPosition = () -> { };
    private FakeDriver driver;
    private BlockBreaker breaker;
    private boolean leaving;

    /** Put a player with this profile into the world. Runs on the server thread. */
    public static FakePlayer place(MinecraftServer server, ServerLevel level, GameProfile profile, Vec3 pos,
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
        instance.getInventory().setSelectedSlot(0);   // slot 0 is his hand, as core expects
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

    public BlockBreaker breaker() {
        if (breaker == null) breaker = new BlockBreaker(this);
        return breaker;
    }

    /** True once he has been told to leave, whether or not the disconnect has run yet. */
    public boolean isLeaving() {
        return leaving || isRemoved();
    }

    @Override
    public void kill(ServerLevel level) {
        kill(Component.literal("Killed"));
    }

    /** Leave the server, next tick. */
    public void kill(Component reason) {
        leaving = true;
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
        // Decide this tick's input before the action pack applies it
        // (it runs at the head of super.tick()).
        if (!leaving) {
            if (breaker != null) breaker.tick();
            if (driver != null) driver.tick();
        }
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
