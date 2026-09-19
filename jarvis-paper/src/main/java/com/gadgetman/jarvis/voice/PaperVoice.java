package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.JarvisCore;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.voice.svc.SvcVoicePlugin;
import com.gadgetman.jarvis.voice.svc.VoiceHost;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Voice on Paper: registers the shared {@link SvcVoicePlugin} with Simple
 * Voice Chat's Bukkit service and answers its two questions about the
 * world, where the butler's body is (the Citizens NPC's entity) and which
 * world the owner is in.
 */
public final class PaperVoice implements VoiceHost {

    private final Jarvis plugin;
    private SvcVoicePlugin svc;

    public PaperVoice(Jarvis plugin) {
        this.plugin = plugin;
    }

    /**
     * Hook into Simple Voice Chat if it is installed.
     *
     * @return true if registered; false when the plugin is absent or voice is off
     */
    public boolean register() {
        if (!plugin.getCoreConfig().getBoolean("voice.enabled", false)) return false;

        if (Bukkit.getPluginManager().getPlugin("voicechat") == null) {
            plugin.getLogger().warning("voice.enabled is true but Simple Voice Chat "
                    + "is not installed; Jarvis will not hear you.");
            return false;
        }
        BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            plugin.getLogger().warning("Simple Voice Chat is present but offered no API service.");
            return false;
        }
        svc = new SvcVoicePlugin(() -> this);
        service.registerPlugin(svc);
        svc.refresh();
        return true;
    }

    public void shutdown() {
        if (svc != null) svc.detach();
    }

    @Override
    public JarvisCore core() {
        return plugin.core();
    }

    @Override
    public Platform platform() {
        return plugin.getPlatform();
    }

    @Override
    public Optional<Object> butlerEntityNear(Owner owner, double distance) {
        try {
            NPC npc = plugin.getNpcProvider().getCitizensNPC(owner.id());
            Player p = plugin.getPlatform().player(owner).orElse(null);
            if (npc == null || !npc.isSpawned() || p == null) return Optional.empty();
            Entity mouth = npc.getEntity();
            if (mouth == null || !mouth.getWorld().equals(p.getWorld())
                    || mouth.getLocation().distance(p.getLocation()) > distance) {
                return Optional.empty();
            }
            return Optional.of(mouth);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Object> levelOf(Owner owner) {
        return plugin.getPlatform().player(owner).map(p -> (Object) p.getWorld());
    }
}
