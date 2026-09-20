package com.gadgetman.jarvis.ai;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Getting JSON out of a model reply that was asked for JSON.
 *
 * <p>The prompts say "output ONLY valid JSON", and models mostly comply --
 * but not always, and not completely. Claude likes to wrap the object in a
 * ```json fence; a chatty model adds a line of prose first; and a reply
 * that ran into the provider's output cap stops mid-string. Handing any of
 * those to {@code new JSONObject(reply)} throws, and the caller's only
 * signal is "failed to generate", which points at the wrong thing.
 */
public final class ModelJson {

    private ModelJson() { }

    /**
     * The outermost JSON object in a reply: fences stripped, leading and
     * trailing prose dropped, nested braces and braces inside strings
     * respected. A reply with no closing brace comes back from its opening
     * brace to the end, so the caller sees the truncation instead of a
     * substring that happens to parse as something else.
     */
    public static String extractObject(String reply) {
        if (reply == null) return "";
        String text = reply.trim();

        // Strip a markdown fence if that is all that is wrong.
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            if (firstBreak > 0) text = text.substring(firstBreak + 1);
            int close = text.lastIndexOf("```");
            if (close >= 0) text = text.substring(0, close);
            text = text.trim();
        }
        if (text.startsWith("{") && text.endsWith("}")) return text;

        int start = text.indexOf('{');
        if (start < 0) return text;
        int end = closingBrace(text, start);
        return end < 0 ? text.substring(start) : text.substring(start, end + 1);
    }

    /**
     * The complete objects inside the array under {@code key}, for a reply
     * whose object as a whole does not parse. A build plan cut off by an
     * output cap ends in the middle of one block; every block before it is
     * still good, and building those beats building nothing.
     *
     * @return the objects that parse, in order; empty when the key or its
     *         array is not there at all
     */
    public static List<JSONObject> salvageArray(String reply, String key) {
        List<JSONObject> out = new ArrayList<>();
        String text = extractObject(reply);
        int at = keyStart(text, key);
        if (at < 0) return out;
        int open = text.indexOf('[', at);
        if (open < 0) return out;

        int i = open + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ']') break;
            if (c == '{') {
                int close = closingBrace(text, i);
                if (close < 0) break;                       // the cut-off one
                try {
                    out.add(new JSONObject(text.substring(i, close + 1)));
                } catch (JSONException ignored) {
                    // a malformed element in the middle; skip it
                }
                i = close + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    /** Index of the top-level {@code "key":} in the text, or -1. */
    private static int keyStart(String text, String key) {
        String quoted = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int at = text.indexOf(quoted, from);
            if (at < 0) return -1;
            int colon = at + quoted.length();
            while (colon < text.length() && Character.isWhitespace(text.charAt(colon))) colon++;
            if (colon < text.length() && text.charAt(colon) == ':') return at;
            from = at + 1;
        }
    }

    /**
     * Index of the brace that closes the one at {@code open}, counting
     * depth and skipping braces inside strings, or -1 when the text ends
     * first.
     */
    private static int closingBrace(String text, int open) {
        int depth = 0;
        boolean inString = false, escaped = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped)            { escaped = false; continue; }
            if (c == '\\')          { escaped = true;  continue; }
            if (c == '"')           { inString = !inString; continue; }
            if (inString)           continue;
            if (c == '{')           depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }
}
