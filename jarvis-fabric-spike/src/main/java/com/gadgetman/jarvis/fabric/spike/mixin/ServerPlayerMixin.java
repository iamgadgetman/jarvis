package com.gadgetman.jarvis.fabric.spike.mixin;

import com.gadgetman.jarvis.fabric.spike.fake.ActionPack;
import com.gadgetman.jarvis.fabric.spike.fake.ActionPackHolder;
import com.gadgetman.jarvis.fabric.spike.fake.FakePlayer;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every server player carries an action pack; only a fake player's is
 * ticked. Adapted from Carpet's ServerPlayer_actionPackMixin (MIT).
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin implements ActionPackHolder {

    @Unique
    private ActionPack jarvis$actionPack;

    @Override
    public ActionPack jarvis$actionPack() {
        return jarvis$actionPack;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void jarvis$onConstruct(MinecraftServer server, ServerLevel level, GameProfile profile,
                                    ClientInformation cli, CallbackInfo ci) {
        this.jarvis$actionPack = new ActionPack((ServerPlayer) (Object) this);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void jarvis$onTick(CallbackInfo ci) {
        if ((Object) this instanceof FakePlayer) {
            jarvis$actionPack.onUpdate();
        }
    }
}
