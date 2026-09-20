package com.gadgetman.jarvis.neoforge;

import com.gadgetman.jarvis.voice.svc.SvcVoicePlugin;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;

/**
 * Found by Simple Voice Chat through the annotation, which is how its
 * NeoForge build discovers plugins. Constructed at mod load, when there is
 * no server yet, so it is given the way to find the host of the moment.
 */
@ForgeVoicechatPlugin
public final class NeoForgeVoicePlugin extends SvcVoicePlugin {

    public NeoForgeVoicePlugin() {
        super(() -> {
            JarvisNeoForge mod = JarvisNeoForge.get();
            return mod == null ? null : mod.voiceHost();
        });
    }
}
