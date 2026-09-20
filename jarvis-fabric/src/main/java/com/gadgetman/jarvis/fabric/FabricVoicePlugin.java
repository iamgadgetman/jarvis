package com.gadgetman.jarvis.fabric;

import com.gadgetman.jarvis.voice.svc.SvcVoicePlugin;

/**
 * The {@code voicechat} entrypoint named in fabric.mod.json. Simple Voice
 * Chat constructs it at mod load, when there is no server yet, so it is
 * given the way to find the host of the moment rather than the host.
 */
public final class FabricVoicePlugin extends SvcVoicePlugin {

    public FabricVoicePlugin() {
        super(() -> {
            JarvisFabric mod = JarvisFabric.get();
            return mod == null ? null : mod.voiceHost();
        });
    }
}
