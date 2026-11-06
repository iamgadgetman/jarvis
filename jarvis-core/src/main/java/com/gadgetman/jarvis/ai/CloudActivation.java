package com.gadgetman.jarvis.ai;

import com.gadgetman.jarvis.core.platform.Audience;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.core.text.RichLine;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code /jarvis cloud activate}: turn Jarvis Cloud on without anyone copying
 * a key. Jarvis Cloud hands back a short link; the owner clicks it in chat
 * and approves in the browser; this polls until the key arrives, then writes
 * it into config.yml and puts Claude-via-Jarvis-Cloud first.
 *
 * <p>The key comes back already bound to this install's {@link InstallId}.
 */
public final class CloudActivation {

    public static final String DEFAULT_URL = "https://api.gadgetcraft.cloud";
    private static final String SUPPORT = "support@gadgetcraft.cloud";

    /** One HTTP exchange. Status -1 means it never got an answer. */
    public record Reply(int status, JSONObject body) {
        String errorMessage() {
            JSONObject e = body.optJSONObject("error");
            String m = e == null ? "" : e.optString("message", "");
            return m.replaceFirst("^Jarvis Cloud: ", "");
        }
    }

    /** The two calls, so tests can stand in for the network. */
    public interface Api {
        Reply post(String url, JSONObject body, Map<String, String> headers) throws Exception;

        Reply get(String url, Map<String, String> headers) throws Exception;
    }

    private final Platform platform;
    private final AiSettings settings;
    private final String installId;
    private final Api api;

    // At most one activation waits at a time; touched only on the server thread.
    private Task polling;
    private boolean inFlight;

    public CloudActivation(Platform platform, AiSettings settings, String installId, Api api) {
        this.platform = platform;
        this.settings = settings;
        this.installId = installId;
        this.api = api;
    }

    public String baseUrl() {
        String u = platform.config().getString("cloud.url", DEFAULT_URL).trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.isEmpty() ? DEFAULT_URL : u;
    }

    /** True when the Claude provider holds a Jarvis Cloud key. */
    public boolean isActive() {
        return platform.config().getString("ai.claude.api-key", "").trim().startsWith("gc_");
    }

    public boolean isWaiting() {
        return polling != null;
    }

    // ==================== activate ====================

    public void activate(Audience who, String name) {
        if (polling != null) {
            who.message(Colors.GOLD + "Jarvis: An activation link is already waiting, sir. Use that one, or let it expire (15 minutes).");
            return;
        }
        if (isActive()) {
            who.message(Colors.GOLD + "Jarvis: Jarvis Cloud is already active here, sir. " + Colors.GRAY + "/jarvis cloud status shows the plan.");
            return;
        }
        JSONObject body = new JSONObject()
                .put("server_id", installId)
                .put("kind", platform.singleplayer() ? "singleplayer" : "server");
        if (name != null && !name.isBlank()) body.put("name", name.trim());

        who.message(Colors.GRAY + "Jarvis: Asking Jarvis Cloud for an activation link, sir...");
        inFlight = true;
        platform.scheduler().async(() -> attempt(() -> api.post(baseUrl() + "/v1/activate/start", body, Map.of())),
                r -> {
                    inFlight = false;
                    onStarted(who, r);
                });
    }

    private void onStarted(Audience who, Reply r) {
        switch (r.status()) {
            case 201 -> {
                String url = r.body().optString("verification_url", "");
                String code = r.body().optString("user_code", "");
                String device = r.body().optString("device_code", "");
                int interval = Math.max(2, r.body().optInt("interval", 5));
                long expires = parseTime(r.body().optString("expires_at", ""), System.currentTimeMillis() + 15 * 60_000L);
                if (url.isEmpty() || device.isEmpty()) {
                    who.message(Colors.RED + "Jarvis: Jarvis Cloud sent an odd reply, sir. Please try again shortly.");
                    return;
                }
                who.rich(new RichLine()
                        .text(Colors.GOLD + "Jarvis: ")
                        .url(Colors.GREEN + "[Click here to activate Jarvis Cloud]", url, "Opens " + url));
                who.message(Colors.GRAY + "Code " + Colors.WHITE + code + Colors.GRAY
                        + " · or open " + url + " · the link lasts 15 minutes.");
                long period = interval * 20L;
                polling = platform.scheduler().every(period, period, task -> pollOnce(who, device, expires));
            }
            case 409 -> who.message(Colors.GOLD + "Jarvis: This install already has a Jarvis Cloud key, sir. "
                    + Colors.GRAY + "If you rebuilt it, write to " + SUPPORT + " and we'll free the old one.");
            case 429 -> who.message(Colors.RED + "Jarvis: Too many activation attempts from here, sir. Try again in an hour.");
            case -1 -> who.message(Colors.RED + "Jarvis: I couldn't reach Jarvis Cloud (" + r.errorMessage() + "), sir.");
            default -> who.message(Colors.RED + "Jarvis: Jarvis Cloud couldn't start an activation (" + r.status()
                    + (r.errorMessage().isEmpty() ? "" : ": " + r.errorMessage()) + "), sir.");
        }
    }

    private void pollOnce(Audience who, String device, long expiresAt) {
        if (inFlight) return;
        // A minute's grace: the browser may approve at 14:59.
        if (System.currentTimeMillis() > expiresAt + 60_000L) {
            stop();
            who.message(Colors.GOLD + "Jarvis: The activation link expired, sir. Run /jarvis cloud activate again when you're ready.");
            return;
        }
        inFlight = true;
        JSONObject body = new JSONObject().put("device_code", device);
        platform.scheduler().async(() -> attempt(() -> api.post(baseUrl() + "/v1/activate/poll", body, Map.of())),
                r -> {
                    inFlight = false;
                    onPolled(who, r);
                });
    }

    private void onPolled(Audience who, Reply r) {
        if (polling == null) return;   // stopped meanwhile
        switch (r.status()) {
            case 202, -1 -> { }        // still waiting, or a network blip: try again next round
            case 200 -> {
                stop();
                String key = r.body().optString("key", "");
                if (!key.startsWith("gc_")) {
                    who.message(Colors.RED + "Jarvis: Jarvis Cloud sent an odd reply, sir. Please write to " + SUPPORT + ".");
                    return;
                }
                settings.useCloud(key, baseUrl() + "/v1/messages");
                String plan = r.body().optString("plan", "");
                platform.log().info("Jarvis Cloud activated (" + plan + ").");
                who.message(Colors.GREEN + "Jarvis: Jarvis Cloud is active, sir"
                        + ("trial".equals(plan) ? ": a 14-day trial, then the free plan." : ": the free plan.")
                        + Colors.GRAY + " Try /jarvis ai test claude.");
                who.sound("minecraft:entity.player.levelup", 0.6f, 1.2f);
            }
            case 410 -> {
                stop();
                boolean claimed = "claimed".equals(r.body().optString("status"));
                who.message(Colors.GOLD + (claimed
                        ? "Jarvis: That activation was already collected, sir. If the key went missing, write to " + SUPPORT + "."
                        : "Jarvis: The activation link expired, sir. Run /jarvis cloud activate again when you're ready."));
            }
            default -> {
                stop();
                who.message(Colors.RED + "Jarvis: Activation stopped (" + r.status()
                        + (r.errorMessage().isEmpty() ? "" : ": " + r.errorMessage()) + "), sir.");
            }
        }
    }

    /** Cancel a waiting activation, e.g. on shutdown. */
    public void stop() {
        if (polling != null) polling.cancel();
        polling = null;
    }

    // ==================== status ====================

    public void status(Audience who) {
        if (!isActive()) {
            who.message(Colors.GOLD + "Jarvis: Jarvis Cloud isn't active here, sir. "
                    + Colors.GRAY + (polling != null ? "An activation link is waiting." : "Run /jarvis cloud activate to turn it on."));
            return;
        }
        String key = platform.config().getString("ai.claude.api-key", "").trim();
        Map<String, String> headers = Map.of("x-api-key", key, "x-jarvis-server", installId);
        platform.scheduler().async(() -> attempt(() -> api.get(baseUrl() + "/v1/usage", headers)), r -> {
            if (r.status() != 200) {
                who.message(Colors.RED + "Jarvis: Jarvis Cloud didn't answer the usage check (" + r.status()
                        + (r.errorMessage().isEmpty() ? "" : ": " + r.errorMessage()) + "), sir.");
                return;
            }
            JSONObject u = r.body();
            String plan = u.optString("plan", "?");
            who.message(Colors.GOLD + "Jarvis: Jarvis Cloud, sir: " + Colors.WHITE + plan + Colors.GRAY + " plan, "
                    + u.optInt("requests_used") + " of " + u.optInt("requests_quota") + " requests this month"
                    + " (resets " + u.optString("resets_at", "").replaceFirst("T.*", "") + ").");
            String trialEnds = u.optString("trial_expires_at", "");
            if (!trialEnds.isEmpty()) {
                who.message(Colors.GRAY + "Trial ends " + trialEnds.replaceFirst("T.*", "") + "; after that it becomes the free plan.");
            }
        });
    }

    // ==================== plumbing ====================

    private static Reply attempt(Callable<Reply> call) {
        try {
            return call.call();
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Reply(-1, new JSONObject().put("error", new JSONObject().put("message", m)));
        }
    }

    private static long parseTime(String iso, long fallback) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** The real network, with plain {@link HttpURLConnection} like the providers. */
    public static final class HttpApi implements Api {
        @Override
        public Reply post(String url, JSONObject body, Map<String, String> headers) throws IOException {
            HttpURLConnection c = open(url, "POST", headers);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            return read(c);
        }

        @Override
        public Reply get(String url, Map<String, String> headers) throws IOException {
            return read(open(url, "GET", headers));
        }

        private static HttpURLConnection open(String url, String method, Map<String, String> headers) throws IOException {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/json");
            headers.forEach(c::setRequestProperty);
            return c;
        }

        private static Reply read(HttpURLConnection c) throws IOException {
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    in.transferTo(out);
                    text = out.toString(StandardCharsets.UTF_8);
                }
            }
            JSONObject body;
            try {
                body = text.isBlank() ? new JSONObject() : new JSONObject(text);
            } catch (JSONException e) {
                body = new JSONObject();
            }
            return new Reply(status, body);
        }
    }
}
