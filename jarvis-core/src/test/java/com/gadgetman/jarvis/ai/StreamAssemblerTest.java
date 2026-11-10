package com.gadgetman.jarvis.ai;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StreamAssemblerTest {

    private static final String REPLY =
            "{\"action\":\"chat\",\"parameters\":{},\"response\":\"Hello there. Let me check.\"}";

    /** Records sentences; accepts the actions it was given. */
    private static final class Rec implements StreamAssembler.Sink {
        final List<String> said = new ArrayList<>();
        final Set<String> ok;
        boolean abandoned;
        Rec(String... ok) { this.ok = Set.of(ok); }
        @Override public boolean accepts(String actionType) { return ok.contains(actionType); }
        @Override public void sentence(String text) { said.add(text); }
        @Override public void abandoned() { abandoned = true; }
    }

    /** Cut a reply into small pieces, the way a model streams it. */
    private static List<String> pieces(String s, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += size) out.add(s.substring(i, Math.min(s.length(), i + size)));
        return out;
    }

    private static String openAiLine(String delta) {
        return "data: " + new JSONObject().put("choices", new org.json.JSONArray()
                .put(new JSONObject().put("delta", new JSONObject().put("content", delta)))).toString();
    }

    private static String ollamaLine(String delta, boolean done) {
        return new JSONObject().put("message", new JSONObject().put("role", "assistant").put("content", delta))
                .put("done", done).toString();
    }

    @Test
    @DisplayName("OpenAI-style SSE: sentences arrive one by one and the full reply still parses")
    void openAiSse() {
        Rec rec = new Rec("chat");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.OPENAI_SSE, rec, 0, () -> 0);
        for (String p : pieces(REPLY, 5)) a.line(openAiLine(p));
        a.line("data: [DONE]");
        String full = a.finish();
        assertEquals(List.of("Hello there.", "Let me check."), rec.said);
        assertTrue(a.done());
        assertEquals("chat", new JSONObject(full).getString("action"));
    }

    @Test
    @DisplayName("Ollama NDJSON gives the same sentences")
    void ollamaNdjson() {
        Rec rec = new Rec("chat");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.OLLAMA_NDJSON, rec, 0, () -> 0);
        List<String> ps = pieces(REPLY, 7);
        for (int i = 0; i < ps.size(); i++) a.line(ollamaLine(ps.get(i), false));
        a.line(new JSONObject().put("message", new JSONObject().put("content", "")).put("done", true)
                .put("eval_count", 12).put("prompt_eval_count", 300).toString());
        a.finish();
        assertEquals(List.of("Hello there.", "Let me check."), rec.said);
        assertTrue(a.usage().contains("in=300 out=12"), a.usage());
    }

    @Test
    @DisplayName("Anthropic SSE: text deltas are read, other events ignored")
    void anthropicSse() {
        Rec rec = new Rec("chat");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.ANTHROPIC_SSE, rec, 0, () -> 0);
        a.line("event: message_start");
        a.line("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":900}}}");
        for (String p : pieces(REPLY, 9)) {
            a.line("event: content_block_delta");
            a.line("data: " + new JSONObject().put("type", "content_block_delta")
                    .put("delta", new JSONObject().put("type", "text_delta").put("text", p)));
        }
        a.line("data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":30}}");
        a.line("data: {\"type\":\"message_stop\"}");
        a.finish();
        assertEquals(List.of("Hello there.", "Let me check."), rec.said);
        assertTrue(a.done());
        assertTrue(a.usage().contains("in=900 out=30"), a.usage());
    }

    @Test
    @DisplayName("Gemini SSE parts are read")
    void geminiSse() {
        Rec rec = new Rec("chat");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.GEMINI_SSE, rec, 0, () -> 0);
        for (String p : pieces(REPLY, 11)) {
            a.line("data: " + new JSONObject().put("candidates", new org.json.JSONArray().put(new JSONObject()
                    .put("content", new JSONObject().put("parts", new org.json.JSONArray()
                            .put(new JSONObject().put("text", p)))))));
        }
        a.finish();
        assertEquals(List.of("Hello there.", "Let me check."), rec.said);
    }

    @Test
    @DisplayName("a sentence split across chunks is sent once, whole")
    void splitSentence() {
        Rec rec = new Rec("chat");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.OLLAMA_NDJSON, rec, 0, () -> 0);
        a.text("{\"action\":\"chat\",\"response\":\"Splen");
        a.text("did wea");
        a.text("ther. Isn't it");
        a.text("?\"}");
        a.finish();
        assertEquals(List.of("Splendid weather.", "Isn't it?"), rec.said);
    }

    @Test
    @DisplayName("an escape split across chunks decodes right")
    void splitEscape() {
        Rec rec = new Rec("chat");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.OLLAMA_NDJSON, rec, 0, () -> 0);
        a.text("{\"action\":\"chat\",\"response\":\"He said \\");
        a.text("\"hi\\");
        a.text("\" \\u00");
        a.text("e9t\\u00e9.\"}");
        a.finish();
        assertEquals(List.of("He said \"hi\" été."), rec.said);
    }

    @Test
    @DisplayName("text outside the response string is never spoken")
    void onlyResponseIsSpoken() {
        Rec rec = new Rec("chat", "follow");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.OLLAMA_NDJSON, rec, 0, () -> 0);
        a.text("{\"action\":\"follow\",\"parameters\":{\"message\":\"Secret. Stuff. \",\"response\":\"Nested. No.\"},"
                + "\"response\":\"Right behind you. Truly.\",\"extra\":\"After. Words. \"}");
        a.finish();
        assertEquals(List.of("Right behind you.", "Truly."), rec.said);
    }

    @Test
    @DisplayName("an action the sink won't stream for is held back whole")
    void heldBack() {
        Rec rec = new Rec("chat");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.OLLAMA_NDJSON, rec, 0, () -> 0);
        a.text("{\"action\":\"teleport\",\"parameters\":{\"x\":1},\"response\":\"Arriving. Now.\"}");
        a.finish();
        assertTrue(rec.said.isEmpty(), rec.said.toString());
        assertEquals(0, a.emitted());
    }

    @Test
    @DisplayName("a reply that puts response before action isn't streamed")
    void responseFirst() {
        Rec rec = new Rec("chat");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.OLLAMA_NDJSON, rec, 0, () -> 0);
        a.text("{\"response\":\"Too soon. Sir.\",\"action\":\"chat\"}");
        a.finish();
        assertTrue(rec.said.isEmpty());
    }

    @Test
    @DisplayName("malformed trailing JSON: speech still delivered, no exception")
    void malformedTail() {
        Rec rec = new Rec("chat");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.OLLAMA_NDJSON, rec, 0, () -> 0);
        a.text("{\"action\":\"chat\",\"response\":\"Hi there. Bye.\",\"parameters\":{oops ");
        assertDoesNotThrow(a::finish);
        assertEquals(List.of("Hi there.", "Bye."), rec.said);
    }

    @Test
    @DisplayName("an unfinished response is still spoken on finish")
    void cutOffResponse() {
        Rec rec = new Rec("chat");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.OLLAMA_NDJSON, rec, 0, () -> 0);
        a.text("{\"action\":\"chat\",\"response\":\"One. And then");
        a.finish();
        assertEquals(List.of("One.", "And then"), rec.said);
        assertFalse(a.responseComplete());
    }

    @Test
    @DisplayName("numbers and dotted words don't split a sentence")
    void noFalseSplits() {
        Rec rec = new Rec("chat");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.OLLAMA_NDJSON, rec, 0, () -> 0);
        a.text("{\"action\":\"chat\",\"response\":\"Version 1.21.5 is fine.\"}");
        a.finish();
        assertEquals(List.of("Version 1.21.5 is fine."), rec.said);
    }

    @Test
    @DisplayName("sentences closer than min-interval are joined, then flushed")
    void pacing() {
        Rec rec = new Rec("chat");
        AtomicLong now = new AtomicLong(1000);
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.OLLAMA_NDJSON, rec, 400, now::get);
        a.text("{\"action\":\"chat\",\"response\":\"One. ");   // sent at once
        a.text("Two. ");                                       // 0 ms later: held
        now.addAndGet(100);
        a.text("Three. ");                                     // 100 ms: still held
        now.addAndGet(400);
        a.text("Four. ");                                      // 500 ms: flush "Two. Three. Four."
        a.text("Five.\"}");                                    // held until finish
        a.finish();
        assertEquals(List.of("One.", "Two. Three. Four.", "Five."), rec.said);
    }

    @Test
    @DisplayName("a provider error event throws so the caller can retry")
    void errorEvent() {
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.ANTHROPIC_SSE, new Rec("chat"), 0, () -> 0);
        assertThrows(IllegalStateException.class,
                () -> a.line("data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}"));
    }

    @Test
    @DisplayName("abandon tells the sink only when something was already said")
    void abandon() {
        Rec quiet = new Rec("chat");
        new StreamAssembler(StreamAssembler.Wire.OLLAMA_NDJSON, quiet, 0, () -> 0).abandon();
        assertFalse(quiet.abandoned);

        Rec spoke = new Rec("chat");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.OLLAMA_NDJSON, spoke, 0, () -> 0);
        a.text("{\"action\":\"chat\",\"response\":\"First. ");
        a.abandon();
        assertTrue(spoke.abandoned);
    }

    @Test
    @DisplayName("prose with quotes and braces before the reply object is skipped")
    void proseBeforeJson() {
        Rec rec = new Rec("chat");
        StreamAssembler a = new StreamAssembler(StreamAssembler.Wire.OPENAI_SSE, rec, 0, () -> 0);
        a.text("Sure, here's \"the plan\" (no {braces} here):\n");
        a.text("{\"action\":\"chat\",\"response\":\"Plan made. Off we go.\"}");
        a.finish();
        assertEquals(List.of("Plan made.", "Off we go."), rec.said);
    }
}
