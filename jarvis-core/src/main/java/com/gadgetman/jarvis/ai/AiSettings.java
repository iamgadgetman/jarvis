package com.gadgetman.jarvis.ai;

import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The AI providers as an operator sets them up: which are enabled, their
 * keys, addresses and models. Every change is written to config.yml (in
 * place, comments kept) and the connector re-reads it at once, so the
 * menu and the command see the same thing and nothing waits for a restart.
 */
public final class AiSettings {

    public static final List<String> PROVIDERS = AIConnector.KNOWN_PROVIDERS;

    private final Platform platform;
    private final AIConnector ai;
    private final Log log;

    public AiSettings(Platform platform, AIConnector ai) {
        this.platform = platform;
        this.ai = ai;
        this.log = platform.log();
    }

    private Config cfg() {
        return platform.config();
    }

    /** The canonical name for what someone typed, or null when it is not a provider. */
    public String resolve(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.equals("anthropic")) n = "claude";
        if (n.equals("xai")) n = "grok";
        if (n.equals("google")) n = "gemini";
        return PROVIDERS.contains(n) ? n : null;
    }

    public boolean needsKey(String provider) {
        return !"ollama".equals(provider);
    }

    // ---- reading ----

    /** The priority list as configured, or the default when nothing is set. */
    public List<String> enabled() {
        List<String> list = cfg().getStringList("ai.provider-priority");
        List<String> out = new ArrayList<>();
        for (String p : list.isEmpty() ? PROVIDERS : list) {
            String r = resolve(p);
            if (r != null && !out.contains(r)) out.add(r);
        }
        return out;
    }

    public boolean isEnabled(String provider) {
        return enabled().contains(provider);
    }

    public boolean hasKey(String provider) {
        return ai.hasApiKey(provider);
    }

    public String model(String provider) {
        return ai.modelOf(provider);
    }

    public String endpoint(String provider) {
        return ai.endpointOf(provider);
    }

    /** "available", "no API key", "cooldown (12s)", "disabled". */
    public String status(String provider) {
        return ai.describe(provider);
    }

    // ---- writing ----

    public void setEnabled(String provider, boolean on) {
        List<String> list = enabled();
        if (on && !list.contains(provider)) {
            // Keep the default order when adding back.
            list.clear();
            for (String p : PROVIDERS) {
                if (p.equals(provider) || isEnabled(p)) list.add(p);
            }
        } else if (!on) {
            list.remove(provider);
        }
        cfg().set("ai.provider-priority", list);
        apply();
    }

    public void setKey(String provider, String key) {
        cfg().set("ai." + provider + ".api-key", key == null ? "" : key.trim());
        apply();
    }

    /** True when the address was accepted; false with a message otherwise. */
    public boolean setEndpoint(String provider, String url) {
        String u = url == null ? "" : url.trim();
        if (!u.isEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) return false;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        cfg().set("ai." + provider + ".endpoint", u);
        apply();
        return true;
    }

    public void setModel(String provider, String model) {
        cfg().set("ai." + provider + ".model", model == null ? "" : model.trim());
        apply();
    }

    private void apply() {
        try {
            cfg().save();
        } catch (RuntimeException e) {
            log.warn("Could not save config.yml: " + e.getMessage());
        }
        ai.reloadConfig();
    }

    // ---- asking the servers ----

    /** The models an Ollama server offers, delivered on the server thread; the failure is a sentence. */
    public void ollamaModels(Consumer<List<String>> ok, Consumer<String> failed) {
        String endpoint = endpoint("ollama");
        platform.scheduler().async(() -> {
            try {
                return OllamaModels.list(endpoint, 4000);
            } catch (Exception e) {
                return e;
            }
        }, result -> {
            if (result instanceof Exception e) failed.accept(reason(e));
            else ok.accept(castList(result));
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return (List<String>) o;
    }

    /** Send one tiny request to a provider; the verdict arrives on the server thread. */
    public void test(String provider, Consumer<String> verdict) {
        long started = System.currentTimeMillis();
        platform.scheduler().async(() -> {
            try {
                String reply = ai.probe(provider);
                long ms = System.currentTimeMillis() - started;
                return "answered in " + ms + " ms" + (reply.isEmpty() ? "" : " (\"" + shorten(reply) + "\")");
            } catch (Exception e) {
                return "failed: " + reason(e);
            }
        }, verdict);
    }

    private static String shorten(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 40 ? one.substring(0, 37) + "..." : one;
    }

    private static String reason(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String m = root.getMessage();
        if (m == null || m.isBlank()) m = root.getClass().getSimpleName();
        if (root instanceof java.net.ConnectException) m = "connection refused (" + m + ")";
        return m.length() > 160 ? m.substring(0, 157) + "..." : m;
    }
}
