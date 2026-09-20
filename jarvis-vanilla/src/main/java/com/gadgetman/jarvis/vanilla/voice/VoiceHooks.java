package com.gadgetman.jarvis.vanilla.voice;

import com.gadgetman.jarvis.voice.svc.SvcVoicePlugin;

/**
 * The two calls an entry point makes into the voice plugin, kept in a class
 * of their own so the entry point never links against Simple Voice Chat's
 * API. Only call these when the voicechat mod is loaded.
 */
public final class VoiceHooks {

    private VoiceHooks() { }

    /** A server is up: attach now, so the log says whether he is listening. */
    public static void serverStarted() {
        SvcVoicePlugin.current().ifPresent(SvcVoicePlugin::refresh);
    }

    /** The server is going: drop the sweep and the open sentences. */
    public static void serverStopping() {
        SvcVoicePlugin.current().ifPresent(SvcVoicePlugin::detach);
    }
}
