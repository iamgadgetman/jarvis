package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.core.platform.Config;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Whether a transcribed sentence was addressed to Jarvis, and what it said.
 *
 * <p>The wake word is matched loosely on purpose. Speech recognition mangles
 * uncommon proper nouns: on one server "Jarvis" came back as "garibas" and
 * "garbous", every time, so an exact match would have rejected every order
 * the player gave. {@code voice.wake-words} takes a list of phrases and
 * {@code voice.wake-fuzz} an edit-distance tolerance, so near-misses still
 * land. Multi-word phrases are the reliable ones ("hey jarvis" survives a
 * degraded signal because the surrounding word anchors the recogniser), and
 * longer phrases are tried first so "hey jarvis" strips both words.
 *
 * <p>Pure: no threads, no platform. The voice plugin on each platform hands
 * it transcripts.
 */
public final class WakeWords {

    private final List<String> phrases;
    private final int fuzz;
    private final int scan;

    /**
     * @param phrases   wake phrases, any case; blank ones are dropped
     * @param fuzz      maximum edit distance, applied to words of five or more letters
     * @param scanWords how far into the sentence the phrase may start
     */
    public WakeWords(List<String> phrases, int fuzz, int scanWords) {
        List<String> words = phrases == null ? List.of() : phrases;
        if (words.isEmpty()) words = List.of("computer");
        this.phrases = words.stream()
                .map(w -> w.toLowerCase(Locale.ROOT).trim())
                .filter(w -> !w.isEmpty())
                .sorted((a, b) -> Integer.compare(          // longest phrase wins
                        b.split("\\s+").length, a.split("\\s+").length))
                .toList();
        this.fuzz = fuzz;
        this.scan = Math.max(1, scanWords);
    }

    public static WakeWords fromConfig(Config cfg) {
        return new WakeWords(cfg.getStringList("voice.wake-words"),
                cfg.getInt("voice.wake-fuzz", 2), cfg.getInt("voice.wake-scan-words", 4));
    }

    public List<String> phrases() {
        return phrases;
    }

    /**
     * Does this sentence carry Jarvis's name? Returns the order with the
     * name and anything before it removed, or null if he wasn't addressed.
     *
     * <p>The phrase need not open the sentence. Recognition regularly
     * prepends filler: a real "hey jarvis build a panic shelter" arrived as
     * "Again, a Jarvis build a panic shelter", with the name third. A short
     * window is scanned and everything up to and including the phrase goes.
     */
    public String strip(String text) {
        if (text == null) return null;
        String[] spoken = text.trim().split("\\s+");
        if (spoken.length == 0 || spoken[0].isEmpty()) return null;

        for (String wake : phrases) {
            String[] tokens = wake.split("\\s+");
            int limit = Math.min(scan, spoken.length - tokens.length + 1);
            for (int offset = 0; offset < limit; offset++) {
                boolean matched = true;
                for (int i = 0; i < tokens.length; i++) {
                    String heard = spoken[offset + i].replaceAll("[^\\p{L}]", "").toLowerCase(Locale.ROOT);
                    String want = tokens[i];
                    // Fuzz only on words long enough that it cannot swallow a
                    // different short word whole.
                    int allowed = want.length() >= 5 ? fuzz : 0;
                    if (heard.isEmpty() || editDistance(heard, want) > allowed) {
                        matched = false;
                        break;
                    }
                }
                if (!matched) continue;
                String rest = String.join(" ", Arrays.copyOfRange(spoken, offset + tokens.length, spoken.length));
                return rest.replaceFirst("^[,.!?\\s]+", "").trim();
            }
        }
        return null;
    }

    /**
     * What a recogniser writes that is not speech. Whisper emits bracketed
     * tags such as "[BLANK_AUDIO]" or "(wind blowing)" for noise; those are
     * not orders and must not reach a model that will gamely act on them.
     */
    public static String clean(String transcript) {
        if (transcript == null) return "";
        return transcript.trim()
                .replaceAll("\\[[^\\]]*\\]", " ")     // [BLANK_AUDIO]
                .replaceAll("\\([^)]*\\)", " ")       // (wind blowing)
                .replaceAll("\\*[^*]*\\*", " ")       // *sighs*
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Levenshtein, iterative and allocation-light; this runs per utterance. */
    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }
}
