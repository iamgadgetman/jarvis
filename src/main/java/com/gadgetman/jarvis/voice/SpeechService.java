package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.Jarvis;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * SpeechService — turns audio into text against an OpenAI-compatible speech
 * endpoint.
 *
 * <p>Any server implementing {@code /v1/audio/transcriptions} works. The
 * reference deployment is a {@code speaches} container, which serves both
 * transcription and synthesis from one process.
 *
 * <p>Model choice is a latency decision, not an accuracy one, for this
 * workload. Measured on the reference box (8-thread CPU, int8) against a 2.7s
 * clip of "Jarvis, go and mine some diamonds": {@code base.en} returned it
 * verbatim in 1.1s, {@code small.en} returned the same text in 3.0s. Both were
 * correct, so base.en is the default — short imperative sentences do not need
 * the larger model, and three seconds of silence after you stop speaking is
 * the difference between a butler and a form submission.
 *
 * <p>Every call here blocks. Callers must be off the main thread.
 */
public class SpeechService {

    private final Jarvis plugin;
    private final HttpClient http;

    private final String endpoint;
    private final String sttModel;
    private final String ttsModel;
    private final String ttsVoice;
    private final String hotwords;
    private final String apiKey;
    private final int timeoutSeconds;

    public SpeechService(Jarvis plugin) {
        this.plugin         = plugin;
        var cfg             = plugin.getConfig();
        this.endpoint       = stripTrailingSlash(cfg.getString("voice.endpoint", "http://127.0.0.1:8000"));
        this.sttModel       = cfg.getString("voice.stt-model", "guillaumekln/faster-whisper-base.en");
        this.ttsModel       = cfg.getString("voice.tts-model", "speaches-ai/piper-en_GB-alan-medium");
        this.ttsVoice       = cfg.getString("voice.tts-voice", "alan");
        this.hotwords       = cfg.getString("voice.hotwords", "");
        this.apiKey         = cfg.getString("voice.api-key", "");
        this.timeoutSeconds = cfg.getInt("voice.timeout-seconds", 20);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public String getEndpoint() { return endpoint; }

    /**
     * Transcribe 48 kHz mono 16-bit PCM.
     *
     * @return the recognised text, or null if the call failed or heard nothing
     */
    public String transcribe(short[] pcm) {
        if (pcm == null || pcm.length == 0) return null;
        try {
            byte[] wav = toWav(pcm, 48000);
            String boundary = "jarvis-" + UUID.randomUUID();

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writePart(body, boundary, "model", sttModel);
            writePart(body, boundary, "response_format", "json");
            // Bias recognition toward names it would otherwise mangle. Whisper
            // is poor at uncommon proper nouns with no context around them.
            if (hotwords != null && !hotwords.isBlank()) {
                writePart(body, boundary, "hotwords", hotwords);
            }
            writeFilePart(body, boundary, "file", "speech.wav", "audio/wav", wav);
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/v1/audio/transcriptions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
            if (apiKey != null && !apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                plugin.getLogger().warning("Speech transcription failed: HTTP " + res.statusCode()
                        + " — " + trim(res.body()));
                return null;
            }

            String text = new org.json.JSONObject(res.body()).optString("text", "").trim();
            return text.isEmpty() ? null : text;

        } catch (Exception e) {
            plugin.getLogger().warning("Speech transcription error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Synthesise speech. Returns 48 kHz mono 16-bit PCM, ready for Opus.
     *
     * <p>Piper emits 22.05 or 24 kHz; voice chat wants 48 kHz. When the source
     * rate divides 48000 exactly — as 24000 does — upsampling is a clean
     * integer duplication with no resampling artefacts, which is why the
     * default voice is a 24 kHz one.
     *
     * @return samples, or null on failure
     */
    public short[] synthesize(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String payload = new org.json.JSONObject()
                    .put("model", ttsModel)
                    .put("voice", ttsVoice)
                    .put("input", text)
                    .put("response_format", "wav")
                    .toString();

            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/v1/audio/speech"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (apiKey != null && !apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<byte[]> res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                plugin.getLogger().warning("Speech synthesis failed: HTTP " + res.statusCode());
                return null;
            }
            return resampleTo48k(readWav(res.body()));

        } catch (Exception e) {
            plugin.getLogger().warning("Speech synthesis error: " + e.getMessage());
            return null;
        }
    }

    /** Samples plus the rate they were recorded at. */
    private record Pcm(short[] samples, int sampleRate) { }

    /**
     * Pull PCM out of a RIFF/WAVE payload by walking the chunk list. Piper's
     * output is plain 16-bit mono, but the header is not always exactly 44
     * bytes, so the chunks are walked rather than assumed.
     */
    private Pcm readWav(byte[] wav) {
        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (wav.length < 12) return null;
        buf.position(12);                       // skip "RIFF" size "WAVE"

        int sampleRate = 24000;
        while (buf.remaining() >= 8) {
            int id   = buf.getInt();
            int size = buf.getInt();
            if (size < 0 || size > buf.remaining()) size = buf.remaining();

            if (id == 0x20746d66) {             // "fmt " little-endian
                int start = buf.position();
                buf.getShort();                 // audio format
                buf.getShort();                 // channels
                sampleRate = buf.getInt();
                buf.position(start + size);
            } else if (id == 0x61746164) {      // "data"
                short[] out = new short[size / 2];
                for (int i = 0; i < out.length; i++) out[i] = buf.getShort();
                return new Pcm(out, sampleRate);
            } else {
                buf.position(buf.position() + size + (size & 1));
            }
        }
        return null;
    }

    /** Nearest-neighbour upsample to 48 kHz. Exact for integer ratios. */
    private short[] resampleTo48k(Pcm pcm) {
        if (pcm == null || pcm.samples().length == 0) return null;
        if (pcm.sampleRate() == 48000) return pcm.samples();

        double ratio = 48000.0 / pcm.sampleRate();
        int outLength = (int) (pcm.samples().length * ratio);
        short[] out = new short[outLength];
        for (int i = 0; i < outLength; i++) {
            int src = (int) (i / ratio);
            out[i] = pcm.samples()[Math.min(src, pcm.samples().length - 1)];
        }
        return out;
    }

    private String trim(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    // ==================== MULTIPART ====================

    private void writePart(ByteArrayOutputStream out, String boundary, String name, String value)
            throws Exception {
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void writeFilePart(ByteArrayOutputStream out, String boundary, String name,
                               String filename, String contentType, byte[] data) throws Exception {
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    // ==================== WAV ====================

    /** Wrap raw mono 16-bit samples in a RIFF/WAVE header. */
    public static byte[] toWav(short[] pcm, int sampleRate) {
        int dataBytes = pcm.length * 2;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);

        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + dataBytes);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));

        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);                     // PCM header size
        buf.putShort((short) 1);            // format: PCM
        buf.putShort((short) 1);            // channels: mono
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * 2);         // byte rate
        buf.putShort((short) 2);            // block align
        buf.putShort((short) 16);           // bits per sample

        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(dataBytes);
        for (short s : pcm) buf.putShort(s);

        return buf.array();
    }
}
