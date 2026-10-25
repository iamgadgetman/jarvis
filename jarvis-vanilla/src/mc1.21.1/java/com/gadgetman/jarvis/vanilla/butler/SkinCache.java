package com.gadgetman.jarvis.vanilla.butler;

// Minecraft 1.21.1 copy of src/main/java/com/gadgetman/jarvis/vanilla/butler/SkinCache.java: the profile cache does the name lookup, and ResolvableProfile.resolve() the textures
// Keep it in step with that file.

import com.gadgetman.jarvis.core.platform.Log;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The butler's uniform: the game profile, textures included, of the Mojang
 * account with his name, as Citizens fetches it for the Paper butler. The
 * name lookup is quick and done on the calling thread; the textures come
 * back later, so the first summon after a start may be in plain clothes
 * unless {@link #prefetch} ran early. Without a lookup (offline mode, no
 * such account, no network) the profile is an offline one.
 */
public final class SkinCache {

    private final MinecraftServer server;
    private final Log log;
    private final Map<String, UUID> ids = new ConcurrentHashMap<>();
    private final Map<String, GameProfile> dressed = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    public SkinCache(MinecraftServer server, Log log) {
        this.server = server;
        this.log = log;
    }

    /** Start resolving a name's textures, if not already known or under way. */
    public void prefetch(String name) {
        if (dressed.containsKey(name) || !pending.add(name)) return;
        UUID uuid = lookup(name);
        if (uuid == null) {
            pending.remove(name);
            return;
        }
        new ResolvableProfile(Optional.empty(), Optional.of(uuid), new PropertyMap()).resolve()
                .whenComplete((resolved, error) -> {
                    pending.remove(name);
                    GameProfile profile = resolved == null ? null : resolved.gameProfile();
                    if (error == null && profile != null && !profile.getName().isEmpty()) {
                        dressed.put(name, profile);
                        log.fine("Uniform ready for " + name);
                    } else {
                        log.info("No uniform for " + name + ": "
                                + (error == null ? "no profile came back" : error.toString()));
                    }
                });
    }

    /** The best profile known right now. */
    public GameProfile profile(String name) {
        GameProfile ready = dressed.get(name);
        if (ready != null) return ready;
        prefetch(name);
        UUID uuid = ids.get(name);
        return new GameProfile(uuid != null ? uuid : UUIDUtil.createOfflinePlayerUUID(name), name);
    }

    private UUID lookup(String name) {
        UUID known = ids.get(name);
        if (known != null) return known;
        try {
            // Asks Mojang when the server is in online mode (singleplayer is);
            // offline, it is the offline id, and the textures lookup finds nothing.
            GameProfileCache cache = server.getProfileCache();
            UUID uuid = cache == null ? null : cache.get(name).map(GameProfile::getId).orElse(null);
            if (uuid == null) return null;
            ids.put(name, uuid);
            return uuid;
        } catch (RuntimeException e) {
            log.info("Could not look up " + name + ": " + e.getMessage());
            return null;
        }
    }
}
