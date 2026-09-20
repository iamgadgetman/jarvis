package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.intent.IntentPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceTimingsTest {

    @Test
    @DisplayName("nothing timed yet says nothing")
    void empty() {
        assertEquals("", new VoiceTimings().describe());
        assertEquals("n/a", VoiceTimings.format(-1));
    }

    @Test
    @DisplayName("each stage is named with its time, seconds above a second")
    void describes() {
        VoiceTimings t = new VoiceTimings();
        t.transcribed(2.1, 850);
        t.understood(3200);
        t.synthesised(310);
        assertEquals("hearing 850 ms (2.1 s of speech), understanding 3.2 s, speaking 310 ms", t.describe());
    }

    @Test
    @DisplayName("the intent stage is timed to the first line back, and lines pass through")
    void timesTheFirstReply() {
        VoiceTimings t = new VoiceTimings();
        List<String> out = new ArrayList<>();
        IntentPipeline.Responder inner = new IntentPipeline.Responder() {
            @Override public void speak(Owner player, String jarvisLine) { out.add("speak:" + jarvisLine); }
            @Override public void feedback(Owner player, String line) { out.add("feedback:" + line); }
        };
        IntentPipeline.Responder timed = t.timing(inner);
        assertEquals(-1, t.intentMs());
        timed.feedback(null, "Torch spacing: 9");
        long first = t.intentMs();
        assertTrue(first >= 0);
        timed.speak(null, "Very good, sir.");
        assertEquals(first, t.intentMs());
        assertEquals(List.of("feedback:Torch spacing: 9", "speak:Very good, sir."), out);
    }
}
