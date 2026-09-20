package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.platform.Audience;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Players;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Core's {@link Players} over the Bukkit server. */
public final class PaperPlayers implements Players {

    private final Audience console = text -> {
        if (text != null) Bukkit.getConsoleSender().sendMessage(LegacyComponentSerializer.legacySection().deserialize(text));
    };

    @Override
    public Owner owner(UUID id) {
        return new PaperOwner(id);
    }

    @Override
    public Optional<Owner> byId(UUID id) {
        return Bukkit.getPlayer(id) == null ? Optional.empty() : Optional.of(new PaperOwner(id));
    }

    @Override
    public Collection<Owner> online() {
        List<Owner> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(new PaperOwner(p.getUniqueId()));
        return out;
    }

    @Override
    public Audience console() {
        return console;
    }

    @Override
    public void broadcast(String text) {
        if (text != null) Bukkit.getServer().broadcast(LegacyComponentSerializer.legacySection().deserialize(text));
    }
}
