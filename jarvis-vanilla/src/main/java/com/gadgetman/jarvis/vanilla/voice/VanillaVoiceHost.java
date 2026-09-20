package com.gadgetman.jarvis.vanilla.voice;

import com.gadgetman.jarvis.JarvisCore;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.vanilla.JarvisMod;
import com.gadgetman.jarvis.vanilla.butler.FakeButlers;
import com.gadgetman.jarvis.vanilla.fake.FakePlayer;
import com.gadgetman.jarvis.voice.svc.VoiceHost;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * The voice plugin's view of a vanilla server: the butler's body is his
 * fake player, and the owner's level is their server player's.
 */
public final class VanillaVoiceHost implements VoiceHost {

    private final JarvisMod mod;

    public VanillaVoiceHost(JarvisMod mod) {
        this.mod = mod;
    }

    @Override
    public JarvisCore core() {
        return mod.core();
    }

    @Override
    public Platform platform() {
        return mod.platform();
    }

    private ServerPlayer player(Owner owner) {
        return mod.platform() == null ? null : mod.platform().server().getPlayerList().getPlayer(owner.id());
    }

    @Override
    public Optional<Object> butlerEntityNear(Owner owner, double distance) {
        FakeButlers butlers = mod.butlers();
        ServerPlayer p = player(owner);
        if (butlers == null || p == null) return Optional.empty();
        FakePlayer fake = butlers.fakeOf(owner.id()).orElse(null);
        if (fake == null || fake.level() != p.level() || fake.distanceTo(p) > distance) return Optional.empty();
        return Optional.of(fake);
    }

    @Override
    public Optional<Object> levelOf(Owner owner) {
        ServerPlayer p = player(owner);
        return p == null ? Optional.empty() : Optional.of(p.level());
    }
}
