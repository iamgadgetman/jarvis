package com.gadgetman.jarvis.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.LongSupplier;

/**
 * Turns a streamed model reply into whole sentences while it is still being
 * written (FB-05). Pure: no HTTP, no threads, so it is tested on its own.
 *
 * <p>Two layers. {@link #line} takes one line off the wire (OpenAI-style or
 * Anthropic SSE, Ollama NDJSON, Gemini SSE) and pulls out the text the model
 * added. {@link #text} reads that text as the reply JSON
 * ({@code {"action":…,"parameters":{…},"response":"…"}}) as it grows, and
 * hands each finished sentence of the {@code response} string to the sink.
 * Nothing outside that string is ever spoken.
 *
 * <p>The reply is only spoken early when the {@code action} value was complete
 * before {@code response} began and {@link Sink#accepts} says yes for it. The
 * pipeline uses that to hold back replies it may still rewrite (an announcement
 * nobody asked for, a teleport for a "where is" question). A model that writes
 * {@code response} first just isn't streamed; the reply is then spoken whole,
 * as before.
 *
 * <p>Sentences closer together than {@code minIntervalMs} are joined and
 * sent with the next one, so a fast cloud model can't post six lines at once.
 */
public final class StreamAssembler {

    /** The line format each provider streams in. */
    public enum Wire { OPENAI_SSE, ANTHROPIC_SSE, OLLAMA_NDJSON, GEMINI_SSE }

    /** Where sentences go. Called on whatever thread feeds the assembler. */
    public interface Sink {
        /** Whether a reply for this action may be spoken before the whole reply is in. */
        boolean accepts(String actionType);

        void sentence(String text);

        /**
         * The stream broke after some sentences were sent, and the request is
         * being retried without streaming: the retry's reply will be spoken whole.
         */
        default void abandoned() { }
    }

    private final Wire wire;
    private final Sink sink;
    private final long minIntervalMs;
    private final LongSupplier clock;

    private final StringBuilder full = new StringBuilder();
    private boolean done;
    private String inputTokens;
    private String outputTokens;

    // ---- incremental JSON scan of `full` ----
    private int pos;
    private int depth;
    private boolean inString;
    private boolean stringIsKey;
    private boolean expectKey;
    private String lastKey;
    private final StringBuilder str = new StringBuilder();
    private String action;
    private boolean inResponse;
    /** null until the response string starts: then whether it may be spoken early. */
    private Boolean streaming;
    private boolean responseClosed;
    /** Prose before the reply object ("Sure, here's \"the plan\": {…") is skipped. */
    private boolean started;

    // ---- sentence and pacing state ----
    private final StringBuilder sentence = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private long lastEmit = Long.MIN_VALUE;
    private int emitted;

    public StreamAssembler(Wire wire, Sink sink, long minIntervalMs, LongSupplier clock) {
        this.wire = wire;
        this.sink = sink;
        this.minIntervalMs = Math.max(0, minIntervalMs);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    // ==================== wire layer ====================

    /**
     * Feed one line as read from the connection.
     *
     * @throws IllegalStateException when the provider streams an error event
     */
    public void line(String raw) {
        if (raw == null) return;
        String l = raw.strip();
        if (l.isEmpty() || l.startsWith(":") || l.startsWith("event:")) return;
        if (wire != Wire.OLLAMA_NDJSON) {
            if (!l.startsWith("data:")) return;
            l = l.substring(5).strip();
            if (l.equals("[DONE]")) { done = true; return; }
        }
        JSONObject j;
        try {
            j = new JSONObject(l);
        } catch (Exception e) {
            return; // a keep-alive or a line we don't know; not text
        }
        switch (wire) {
            case OPENAI_SSE -> {
                JSONArray choices = j.optJSONArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JSONObject c = choices.optJSONObject(0);
                    JSONObject delta = c == null ? null : c.optJSONObject("delta");
                    if (delta != null && delta.has("content") && !delta.isNull("content")) {
                        text(delta.optString("content", ""));
                    }
                }
                if (j.has("error")) throw new IllegalStateException("stream error: " + j.opt("error"));
            }
            case ANTHROPIC_SSE -> {
                String type = j.optString("type");
                switch (type) {
                    case "content_block_delta" -> {
                        JSONObject d = j.optJSONObject("delta");
                        if (d != null && "text_delta".equals(d.optString("type"))) text(d.optString("text", ""));
                    }
                    case "message_start" -> {
                        JSONObject m = j.optJSONObject("message");
                        JSONObject u = m == null ? null : m.optJSONObject("usage");
                        if (u != null) inputTokens = String.valueOf(u.optInt("input_tokens"));
                    }
                    case "message_delta" -> {
                        JSONObject u = j.optJSONObject("usage");
                        if (u != null) outputTokens = String.valueOf(u.optInt("output_tokens"));
                    }
                    case "message_stop" -> done = true;
                    case "error" -> throw new IllegalStateException("stream error: " + j.opt("error"));
                    default -> { }
                }
            }
            case OLLAMA_NDJSON -> {
                if (j.has("error")) throw new IllegalStateException("stream error: " + j.opt("error"));
                JSONObject m = j.optJSONObject("message");
                if (m != null) text(m.optString("content", ""));
                if (j.optBoolean("done", false)) {
                    done = true;
                    if (j.has("prompt_eval_count")) inputTokens = String.valueOf(j.optInt("prompt_eval_count"));
                    if (j.has("eval_count")) outputTokens = String.valueOf(j.optInt("eval_count"));
                }
            }
            case GEMINI_SSE -> {
                JSONArray cands = j.optJSONArray("candidates");
                JSONObject c = cands == null ? null : cands.optJSONObject(0);
                JSONObject content = c == null ? null : c.optJSONObject("content");
                JSONArray parts = content == null ? null : content.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject p = parts.optJSONObject(i);
                        if (p != null) text(p.optString("text", ""));
                    }
                }
                if (c != null && c.has("finishReason")) done = true;
            }
        }
    }

    // ==================== text layer ====================

    /** Feed text the model added. */
    public void text(String delta) {
        if (delta == null || delta.isEmpty()) return;
        full.append(delta);
        scan();
    }

    /**
     * The reply is complete: send whatever is left of the response and return
     * the whole text for the normal parse.
     */
    public String finish() {
        if (Boolean.TRUE.equals(streaming)) {
            String tail = sentence.toString().strip();
            sentence.setLength(0);
            if (!tail.isEmpty()) queue(tail);
            flush(true);
        }
        return full.toString();
    }

    /** Tell the sink the stream was given up after it spoke (see {@link Sink#abandoned}). */
    public void abandon() {
        if (emitted > 0) sink.abandoned();
    }

    public String full() { return full.toString(); }
    public boolean done() { return done; }
    public int emitted() { return emitted; }

    /** Token counts for {@code ai.log-usage}, or null when the provider sent none. */
    public String usage() {
        if (inputTokens == null && outputTokens == null) return null;
        return "in=" + (inputTokens == null ? "?" : inputTokens) + " out=" + (outputTokens == null ? "?" : outputTokens)
                + " (streamed)";
    }

    /** Walk the new characters of {@code full}, tracking just enough JSON to find action and response. */
    private void scan() {
        while (pos < full.length()) {
            char ch = full.charAt(pos);
            if (!started) {
                pos++;
                if (ch == '{') { started = true; depth = 1; expectKey = true; }
                continue;
            }
            if (inString) {
                if (ch == '\\') {
                    int need = pos + 1 < full.length() && full.charAt(pos + 1) == 'u' ? 6 : 2;
                    if (pos + need > full.length()) return; // escape split across chunks: wait
                    char decoded = decodeEscape(full, pos);
                    pos += need;
                    stringChar(decoded);
                    continue;
                }
                if (ch == '"') {
                    pos++;
                    endString();
                    continue;
                }
                pos++;
                stringChar(ch);
                continue;
            }
            pos++;
            switch (ch) {
                case '{' -> { depth++; if (depth == 1) expectKey = true; }
                case '}' -> depth--;
                case '[' -> depth++;
                case ']' -> depth--;
                case ',' -> { if (depth == 1) expectKey = true; }
                case ':' -> { if (depth == 1) expectKey = false; }
                case '"' -> startString();
                default -> { }
            }
        }
    }

    private void startString() {
        inString = true;
        str.setLength(0);
        stringIsKey = depth == 1 && expectKey;
        if (!stringIsKey && depth == 1 && "response".equals(lastKey) && streaming == null) {
            inResponse = true;
            streaming = action != null && sink.accepts(action);
        }
    }

    private void stringChar(char c) {
        if (inResponse) {
            if (Boolean.TRUE.equals(streaming)) responseChar(c);
            return;
        }
        str.append(c);
    }

    private void endString() {
        inString = false;
        if (inResponse) {
            inResponse = false;
            responseClosed = true;
            if (Boolean.TRUE.equals(streaming)) {
                String tail = sentence.toString().strip();
                sentence.setLength(0);
                if (!tail.isEmpty()) queue(tail);
                flush(false);
            }
            return;
        }
        if (stringIsKey) {
            lastKey = str.toString();
        } else if (depth == 1 && "action".equals(lastKey) && action == null) {
            action = str.toString();
        }
    }

    // ---- sentences ----

    private void responseChar(char c) {
        if (c == '\n') {
            String s = sentence.toString().strip();
            sentence.setLength(0);
            if (!s.isEmpty()) queue(s);
            return;
        }
        // A sentence ends at . ! or ? followed by a space: "3.5" and "e.g.x" don't split.
        // Closing quotes and brackets after the mark belong to it: 'Run!' she said.
        if (Character.isWhitespace(c) && sentence.length() > 1) {
            int end = sentence.length() - 1;
            while (end > 0 && CLOSERS.indexOf(sentence.charAt(end)) >= 0) end--;
            char last = sentence.charAt(end);
            // "and... well" is a pause, not the end of a sentence.
            boolean ellipsis = last == '.' && end > 0 && sentence.charAt(end - 1) == '.';
            if ((last == '.' || last == '!' || last == '?') && !ellipsis) {
                String s = sentence.toString().strip();
                sentence.setLength(0);
                if (!s.isEmpty()) queue(s);
                return;
            }
        }
        sentence.append(c);
    }

    /** Characters that close a quote or bracket after a sentence's last mark. */
    private static final String CLOSERS = "\"')]}\u2019\u201D\u00BB";

    private void queue(String s) {
        if (pending.length() > 0) pending.append(' ');
        pending.append(s);
        flush(false);
    }

    private void flush(boolean force) {
        if (pending.length() == 0) return;
        // A piece with no words ("'" after "HAYSTACK! '", seen live) isn't a
        // line of its own: it goes out with the next one, or is dropped once
        // the reply is over (it only closed the sentence before it).
        if (pending.codePoints().noneMatch(Character::isLetterOrDigit)) {
            if (force || responseClosed) pending.setLength(0);
            return;
        }
        long now = clock.getAsLong();
        if (!force && lastEmit != Long.MIN_VALUE && now - lastEmit < minIntervalMs) return;
        String out = pending.toString();
        pending.setLength(0);
        lastEmit = now;
        emitted++;
        sink.sentence(out);
    }

    private static char decodeEscape(CharSequence s, int at) {
        char e = s.charAt(at + 1);
        return switch (e) {
            case 'n' -> '\n';
            case 't' -> ' ';
            case 'r' -> ' ';
            case 'b', 'f' -> ' ';
            case 'u' -> {
                try {
                    yield (char) Integer.parseInt(s.subSequence(at + 2, at + 6).toString(), 16);
                } catch (NumberFormatException ex) {
                    yield '?';
                }
            }
            default -> e; // \" \\ \/
        };
    }

    /** True once the response string closed: everything spoken early is all of it. */
    public boolean responseComplete() {
        return responseClosed;
    }
}
