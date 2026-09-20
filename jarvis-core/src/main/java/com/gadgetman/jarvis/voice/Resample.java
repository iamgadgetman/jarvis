package com.gadgetman.jarvis.voice;

/**
 * The sample-rate arithmetic between voice chat (48 kHz) and the speech
 * engines (whisper listens at 16 kHz; a Piper voice speaks at 22.05 or
 * 24 kHz). Pure functions, so they can be checked without any audio.
 */
public final class Resample {

    private Resample() { }

    /** 48 kHz shorts to 16 kHz floats in [-1, 1]: each output sample is the mean of three. */
    public static float[] to16k(short[] pcm48k) {
        int n = pcm48k.length / 3;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int j = i * 3;
            out[i] = (pcm48k[j] + pcm48k[j + 1] + pcm48k[j + 2]) / (3f * 32768f);
        }
        return out;
    }

    /**
     * Any rate to 48 kHz by linear interpolation. Exact for 24 kHz (every
     * other sample is an original); for 22.05 kHz the in-between values are
     * interpolated rather than repeated, which is what keeps a voice from
     * buzzing.
     */
    public static short[] to48k(short[] pcm, int rate) {
        if (pcm == null) return null;
        if (rate == 48000 || pcm.length == 0) return pcm;
        long outLength = (long) pcm.length * 48000L / rate;
        short[] out = new short[(int) outLength];
        double step = rate / 48000.0;
        for (int i = 0; i < out.length; i++) {
            double pos = i * step;
            int a = (int) pos;
            int b = Math.min(a + 1, pcm.length - 1);
            double frac = pos - a;
            out[i] = (short) Math.round(pcm[a] * (1 - frac) + pcm[b] * frac);
        }
        return out;
    }

    /** Silence appended up to {@code minSamples}; whisper refuses anything under a second. */
    public static short[] padTo(short[] pcm, int minSamples) {
        if (pcm.length >= minSamples) return pcm;
        short[] out = new short[minSamples];
        System.arraycopy(pcm, 0, out, 0, pcm.length);
        return out;
    }
}
