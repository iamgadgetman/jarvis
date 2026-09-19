package com.gadgetman.jarvis.fabric.platform;

import com.gadgetman.jarvis.core.platform.Audience;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Players;
import com.gadgetman.jarvis.fabric.fake.FakePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Core's {@link Players} over the server's player list. Fake players are not players here. */
public final class FabricPlayers implements Players {

    private final MinecraftServer server;
    private final Audience console;

    public FabricPlayers(MinecraftServer server) {
        this.server = server;
        this.console = text -> {
            if (text != null) server.sendSystemMessage(Component.literal(text));
        };
    }

    @Override
    public Owner owner(UUID id) {
        return new FabricOwner(server, id);
    }

    @Override
    public Optional<Owner> byId(UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p == null || p instanceof FakePlayer ? Optional.empty() : Optional.of(new FabricOwner(server, id));
    }

    @Override
    public Collection<Owner> online() {
        List<Owner> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!(p instanceof FakePlayer)) out.add(new FabricOwner(server, p.getUUID()));
        }
        return out;
    }

    @Override
    public Audience console() {
        return console;
    }

    @Override
    public void broadcast(String text) {
        if (text != null) server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
    }
}
