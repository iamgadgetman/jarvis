package com.gadgetman.jarvis.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ResampleTest {

    @Test
    @DisplayName("48 kHz to 16 kHz averages triples and scales to [-1, 1]")
    void downsamples() {
        short[] in = {3000, 3000, 3000, -32768, -32768, -32768, 0, 0};
        float[] out = Resample.to16k(in);
        assertEquals(2, out.length, "a trailing partial triple is dropped");
        assertEquals(3000 / 32768f, out[0], 1e-6);
        assertEquals(-1f, out[1], 1e-6);
    }

    @Test
    @DisplayName("24 kHz to 48 kHz keeps every original sample and interpolates between")
    void upsamplesExactly() {
        short[] out = Resample.to48k(new short[] {0, 100, 200}, 24000);
        assertEquals(6, out.length);
        assertEquals(0, out[0]);
        assertEquals(50, out[1]);
        assertEquals(100, out[2]);
        assertEquals(150, out[3]);
        assertEquals(200, out[4]);
    }

    @Test
    @DisplayName("22.05 kHz lands on the right length, and 48 kHz is untouched")
    void upsamplesOddRatio() {
        short[] in = new short[22050];
        assertEquals(48000, Resample.to48k(in, 22050).length);
        assertSame(in, Resample.to48k(in, 48000));
    }

    @Test
    @DisplayName("short clips are padded with silence; long ones are left alone")
    void pads() {
        assertEquals(10, Resample.padTo(new short[] {1, 2}, 10).length);
        short[] longer = new short[20];
        assertSame(longer, Resample.padTo(longer, 10));
    }
}
