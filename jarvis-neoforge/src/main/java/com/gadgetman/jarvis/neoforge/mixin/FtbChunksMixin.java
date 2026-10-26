package com.gadgetman.jarvis.neoforge.mixin;

import com.gadgetman.jarvis.neoforge.JarvisNeoForge;
import com.gadgetman.jarvis.vanilla.compat.McCompat;
import com.gadgetman.jarvis.vanilla.fake.FakePlayer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * FTB Chunks judges the butler as his owner: he may dig, open and use what
 * his owner may in a team's claims, and nothing more. Without this he is a
 * stranger there, under a fresh id each summon, so allying him would leave a
 * stale ally behind every time. Does nothing when FTB Chunks is absent.
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbchunks.data.ChunkTeamDataImpl", remap = false)
public abstract class FtbChunksMixin {

    @ModifyVariable(method = "canPlayerUse", at = @At("HEAD"), argsOnly = true, require = 0)
    private ServerPlayer jarvis$asOwner(ServerPlayer player) {
        if (!(player instanceof FakePlayer)) return player;
        JarvisNeoForge mod = JarvisNeoForge.get();
        if (mod == null || mod.butlers() == null) return player;
        ServerPlayer owner = mod.butlers().ownerOf(player)
                .map(id -> McCompat.level(player).getServer().getPlayerList().getPlayer(id))
                .orElse(null);
        return owner != null ? owner : player;
    }
}
