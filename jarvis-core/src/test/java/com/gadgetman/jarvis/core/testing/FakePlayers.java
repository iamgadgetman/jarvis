package com.gadgetman.jarvis.core.testing;

import com.gadgetman.jarvis.core.platform.Audience;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Players;
import com.gadgetman.jarvis.core.text.Colors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The players the test has created, plus a console that keeps what it is told. */
public final class FakePlayers implements Players {

    private final FakePlatform platform;
    private final Map<UUID, FakeOwner> owners = new LinkedHashMap<>();
    public final List<String> consoleLines = new ArrayList<>();
    public final List<String> broadcasts = new ArrayList<>();

    private final Audience console = text -> consoleLines.add(Colors.strip(text));

    FakePlayers(FakePlatform platform) {
        this.platform = platform;
    }

    /** Create an online player standing at the platform's default spot. */
    public FakeOwner join(String name) {
        FakeOwner o = new FakeOwner(platform, UUID.nameUUIDFromBytes(name.getBytes()), name);
        owners.put(o.id(), o);
        return o;
    }

    @Override
    public Owner owner(UUID id) {
        FakeOwner o = owners.get(id);
        if (o == null) {
            o = new FakeOwner(platform, id, id.toString().substring(0, 8));
            o.online = false;
            owners.put(id, o);
        }
        return o;
    }

    @Override
    public Optional<Owner> byId(UUID id) {
        FakeOwner o = owners.get(id);
        return o != null && o.online ? Optional.of(o) : Optional.empty();
    }

    @Override
    public Collection<Owner> online() {
        List<Owner> out = new ArrayList<>();
        for (FakeOwner o : owners.values()) if (o.online) out.add(o);
        return out;
    }

    @Override public Audience console() { return console; }

    @Override
    public void broadcast(String text) {
        broadcasts.add(Colors.strip(text));
        for (Owner o : online()) o.message(text);
    }
}
