package com.gadgetman.jarvis.ai;

import java.util.Map;

/**
 * Where a provider's API key comes from (FB-09). {@code api-key} in config.yml
 * may hold the key itself, or {@code "env:NAME"} to read environment variable
 * NAME, so the key never sits in a file that gets shared. When it is blank and
 * {@code ai.env-keys} is on, the provider's usual variable is read instead
 * ({@code GEMINI_API_KEY}, {@code OPENAI_API_KEY}, …), which is what the
 * providers' own setup pages tell people to export.
 */
public final class ApiKeys {

    private ApiKeys() {
    }

    /** The environment variable each provider's own docs use. Ollama needs no key. */
    public static final Map<String, String> DEFAULT_VARS = Map.of(
            "gemini", "GEMINI_API_KEY",
            "openai", "OPENAI_API_KEY",
            "claude", "ANTHROPIC_API_KEY",
            "grok", "XAI_API_KEY");

    /**
     * The key to use, or "" for none.
     *
     * @param configured  what config.yml says ({@code api-key}), may be null
     * @param provider    the provider's name, for the default variable
     * @param env         the environment (System.getenv() in real use)
     * @param implicitEnv whether a blank key may fall back to the default variable
     */
    public static String resolve(String configured, String provider, Map<String, String> env, boolean implicitEnv) {
        String c = configured == null ? "" : configured.trim();
        if (c.regionMatches(true, 0, "env:", 0, 4)) return clean(env.get(c.substring(4).trim()));
        if (!c.isEmpty() || !implicitEnv) return c;
        String var = DEFAULT_VARS.get(provider);
        return var == null ? "" : clean(env.get(var));
    }

    /**
     * Where the key came from, for the status line: "GEMINI_API_KEY" when it
     * was read from that variable, null when it is written in config.yml or
     * there is none.
     */
    public static String source(String configured, String provider, Map<String, String> env, boolean implicitEnv) {
        String c = configured == null ? "" : configured.trim();
        if (c.regionMatches(true, 0, "env:", 0, 4)) {
            String var = c.substring(4).trim();
            return clean(env.get(var)).isEmpty() ? null : var;
        }
        if (!c.isEmpty() || !implicitEnv) return null;
        String var = DEFAULT_VARS.get(provider);
        return var != null && !clean(env.get(var)).isEmpty() ? var : null;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
