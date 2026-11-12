package com.gadgetman.jarvis.ai;

import com.gadgetman.jarvis.core.platform.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which AI providers can answer right now, for the startup log, the op's
 * join line and {@code /jarvis setup} (FB-09). A fresh install should know
 * within one line whether Jarvis can talk, and if not, the three quickest
 * free ways to fix that.
 *
 * <p>{@link #run()} blocks on one short HTTP call to Ollama; call it off the
 * server thread.
 */
public final class ProviderProbe {

    /** One provider's verdict. {@code text} is the status-line fragment, e.g. "ollama OK (mistral)". */
    public record Status(String provider, boolean usable, String text) { }

    /** The models an Ollama server has pulled. {@link OllamaModels#list} in real use. */
    public interface OllamaLister {
        List<String> models(String endpoint) throws IOException;
    }

    private final Config config;
    private final Map<String, String> env;
    private final boolean envKeys;
    private final List<String> enabled;
    private final OllamaLister ollama;

    /**
     * @param config  the whole config (reads {@code ai.*})
     * @param env     the environment keys are read from
     * @param envKeys {@code ai.env-keys}
     * @param enabled the provider priority list, in order
     * @param ollama  how to ask Ollama what it has
     */
    public ProviderProbe(Config config, Map<String, String> env, boolean envKeys, List<String> enabled, OllamaLister ollama) {
        this.config = config;
        this.env = env;
        this.envKeys = envKeys;
        this.enabled = enabled;
        this.ollama = ollama;
    }

    /** The probe for a running connector: its config, environment and priority list. */
    public static ProviderProbe of(Config config, AIConnector ai, List<String> enabled) {
        return new ProviderProbe(config, ai.environment(), ai.envKeys(), enabled,
                endpoint -> OllamaModels.list(endpoint, 3000));
    }

    /** Every known provider, enabled ones in priority order first, then the rest. */
    public List<Status> run() {
        List<String> order = new ArrayList<>(enabled);
        for (String p : AIConnector.KNOWN_PROVIDERS) if (!order.contains(p)) order.add(p);
        List<Status> out = new ArrayList<>();
        for (String p : order) out.add(check(p, enabled.contains(p)));
        return out;
    }

    private Status check(String provider, boolean on) {
        if (provider.equals("ollama")) return on ? checkOllama() : new Status("ollama", false, "ollama disabled");
        String raw = config.getString("ai." + provider + ".api-key", "");
        String key = ApiKeys.resolve(raw, provider, env, envKeys);
        if (provider.equals("claude") && key.startsWith("gc_")) {
            return on ? new Status("claude", true, "jarvis-cloud OK")
                    : new Status("claude", false, "jarvis-cloud disabled");
        }
        String name = provider.equals("claude") ? "claude" : provider;
        if (!on) return new Status(provider, false, name + " disabled");
        if (key.isEmpty()) {
            if (raw.trim().regionMatches(true, 0, "env:", 0, 4)) {
                return new Status(provider, false, name + " no key (" + raw.trim().substring(4).trim() + " is not set)");
            }
            return new Status(provider, false, name + " no key");
        }
        String from = ApiKeys.source(raw, provider, env, envKeys);
        return new Status(provider, true, name + " OK" + (from == null ? "" : " (key from " + from + ")"));
    }

    private Status checkOllama() {
        String endpoint = config.getString("ai.ollama.endpoint", "http://localhost:11434").trim();
        if (endpoint.isEmpty()) endpoint = "http://localhost:11434";
        String model = config.getString("ai.ollama.model", "mistral").trim();
        List<String> models;
        try {
            models = ollama.models(endpoint);
        } catch (IOException | RuntimeException e) {
            return new Status("ollama", false, "ollama not reachable at " + endpoint);
        }
        if (models.isEmpty()) return new Status("ollama", false, "ollama running, no model pulled");
        if (hasModel(models, model)) return new Status("ollama", true, "ollama OK (" + model + ")");
        return new Status("ollama", false, "ollama running, " + model + " not pulled");
    }

    /** "mistral" matches "mistral:latest"; "qwen2.5:7b" must match exactly. */
    static boolean hasModel(List<String> pulled, String model) {
        for (String m : pulled) {
            if (m.equals(model) || (!model.contains(":") && m.equals(model + ":latest"))) return true;
        }
        return false;
    }

    /** Providers that can answer, in priority order. */
    public static List<String> usable(List<Status> statuses) {
        List<String> out = new ArrayList<>();
        for (Status s : statuses) if (s.usable()) out.add(s.provider());
        return out;
    }

    /** "AI providers: ollama OK (mistral) · gemini OK · jarvis-cloud not activated · …" */
    public static String statusLine(List<Status> statuses) {
        StringBuilder b = new StringBuilder("AI providers: ");
        for (int i = 0; i < statuses.size(); i++) {
            if (i > 0) b.append(" · ");
            Status s = statuses.get(i);
            b.append(s.provider().equals("claude") && s.text().startsWith("claude no key")
                    ? "jarvis-cloud not activated · " + s.text() : s.text());
        }
        return b.toString();
    }

    /** The boxed console message when nothing can answer: the three fastest free paths, in order. */
    public static List<String> noProviderBox(String ollamaModel) {
        String model = ollamaModel == null || ollamaModel.isBlank() ? "mistral" : ollamaModel.trim();
        String rule = "+" + "-".repeat(66);
        return List.of(
                rule,
                "| Jarvis has no AI provider yet. Commands work; sentences need one.",
                "| The three quickest ways, all free to start:",
                "|  1. Local: install Ollama (ollama.com), then: ollama pull " + model,
                "|  2. Gemini free tier: get a key at aistudio.google.com and set",
                "|     GEMINI_API_KEY in the server's environment (or ai.gemini.api-key)",
                "|  3. Jarvis Cloud trial: /jarvis cloud activate (no key to manage)",
                "| Then run /jarvis setup to check again.",
                rule);
    }

    /** The in-game line for an op who joins while nothing can answer. */
    public static String joinLine(String ollamaModel) {
        String model = ollamaModel == null || ollamaModel.isBlank() ? "mistral" : ollamaModel.trim();
        return "Jarvis has no AI provider yet, sir. Free options: ollama pull " + model
                + " · a Gemini key (GEMINI_API_KEY) · /jarvis cloud activate. /jarvis setup to re-check.";
    }
}
