package com.gadgetman.jarvis.ai;

import com.gadgetman.jarvis.core.testing.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** FB-09: what the startup check says, given keys, Ollama and Jarvis Cloud. */
class ProviderProbeTest {

    private static final List<String> ALL = AIConnector.KNOWN_PROVIDERS;
    private static final ProviderProbe.OllamaLister DOWN = e -> { throw new ConnectException("Connection refused"); };

    private Fixture f;

    @AfterEach
    void stop() {
        if (f != null) f.close();
    }

    private ProviderProbe probe(String aiYaml, Map<String, String> env, ProviderProbe.OllamaLister ollama) throws IOException {
        f = new Fixture(Fixture.DEFAULT_CONFIG.replace("ai:\n", "ai:\n" + aiYaml));
        return new ProviderProbe(f.platform.config(), env, true, ALL, ollama);
    }

    @Test
    @DisplayName("nothing usable: no keys, Ollama down, no Cloud key; the box lists the three paths in order")
    void nothingUsable() throws IOException {
        List<ProviderProbe.Status> st = probe("", Map.of(), DOWN).run();
        assertTrue(ProviderProbe.usable(st).isEmpty(), st.toString());
        String box = String.join("\n", ProviderProbe.noProviderBox("mistral"));
        int ollama = box.indexOf("ollama pull mistral");
        int gemini = box.indexOf("aistudio.google.com");
        int cloud = box.indexOf("/jarvis cloud activate");
        assertTrue(ollama > 0 && gemini > ollama && cloud > gemini, box);
    }

    @Test
    @DisplayName("GEMINI_API_KEY in the environment makes gemini usable, and says where the key came from")
    void geminiFromEnv() throws IOException {
        List<ProviderProbe.Status> st = probe("", Map.of("GEMINI_API_KEY", "AIza-test"), DOWN).run();
        assertEquals(List.of("gemini"), ProviderProbe.usable(st));
        assertTrue(ProviderProbe.statusLine(st).contains("gemini OK (key from GEMINI_API_KEY)"), ProviderProbe.statusLine(st));
    }

    @Test
    @DisplayName("api-key: env:NAME reads that variable; a missing one says which")
    void explicitEnvReference() throws IOException {
        String yaml = "  openai:\n    api-key: \"env:MY_OPENAI\"\n";
        List<ProviderProbe.Status> st = probe(yaml, Map.of(), DOWN).run();
        assertTrue(ProviderProbe.statusLine(st).contains("openai no key (MY_OPENAI is not set)"), ProviderProbe.statusLine(st));
        st = probe(yaml, Map.of("MY_OPENAI", "sk-test"), DOWN).run();
        assertTrue(ProviderProbe.usable(st).contains("openai"), st.toString());
    }

    @Test
    @DisplayName("Ollama reachable with no models: running, no model pulled; with the model: OK")
    void ollamaStates() throws IOException {
        List<ProviderProbe.Status> st = probe("", Map.of(), e -> List.of()).run();
        assertTrue(ProviderProbe.statusLine(st).contains("ollama running, no model pulled"), ProviderProbe.statusLine(st));
        assertFalse(ProviderProbe.usable(st).contains("ollama"));

        // The fixture's model is "test"; Ollama lists it with its tag.
        st = probe("", Map.of(), e -> List.of("test:latest", "nomic-embed-text:latest")).run();
        assertTrue(ProviderProbe.statusLine(st).contains("ollama OK (test)"), ProviderProbe.statusLine(st));

        st = probe("", Map.of(), e -> List.of("llama3.2:latest")).run();
        assertTrue(ProviderProbe.statusLine(st).contains("ollama running, test not pulled"), ProviderProbe.statusLine(st));
    }

    @Test
    @DisplayName("a Jarvis Cloud key on the claude provider reads as jarvis-cloud OK")
    void cloudKey() throws IOException {
        List<ProviderProbe.Status> st = probe("  claude:\n    api-key: gc_live_abc\n", Map.of(), DOWN).run();
        assertEquals(List.of("claude"), ProviderProbe.usable(st));
        assertTrue(ProviderProbe.statusLine(st).contains("jarvis-cloud OK"), ProviderProbe.statusLine(st));
    }

    @Test
    @DisplayName("the status line is stable: free providers first, Cloud named when not activated")
    void statusLineSnapshot() throws IOException {
        List<ProviderProbe.Status> st = probe("", Map.of(), e -> List.of("test")).run();
        assertEquals("AI providers: ollama OK (test) · gemini no key · jarvis-cloud not activated · claude no key"
                + " · openai no key · grok no key", ProviderProbe.statusLine(st));
    }

    @Test
    @DisplayName("ai.env-keys: false ignores the default variables but still honours env:NAME")
    void envKeysOff() throws IOException {
        f = new Fixture(Fixture.DEFAULT_CONFIG.replace("ai:\n", "ai:\n  openai:\n    api-key: \"env:K\"\n"));
        ProviderProbe p = new ProviderProbe(f.platform.config(), Map.of("GEMINI_API_KEY", "x", "K", "y"), false, ALL, DOWN);
        assertEquals(List.of("openai"), ProviderProbe.usable(p.run()));
    }

    @Test
    @DisplayName("ApiKeys: literal, env:NAME, default variable, and nothing")
    void apiKeys() {
        Map<String, String> env = Map.of("GEMINI_API_KEY", " g ", "X", "x");
        assertEquals("lit", ApiKeys.resolve("lit", "gemini", env, true));
        assertEquals("x", ApiKeys.resolve("env:X", "gemini", env, true));
        assertEquals("", ApiKeys.resolve("env:MISSING", "gemini", env, true));
        assertEquals("g", ApiKeys.resolve("", "gemini", env, true));
        assertEquals("", ApiKeys.resolve("", "gemini", env, false));
        assertEquals("", ApiKeys.resolve("", "ollama", env, true));
        assertEquals("GEMINI_API_KEY", ApiKeys.source("", "gemini", env, true));
        assertNull(ApiKeys.source("lit", "gemini", env, true));
    }

    @Test
    @DisplayName("the connector itself uses an env key: gemini becomes a real provider")
    void connectorReadsEnv() throws IOException {
        f = new Fixture();
        assertFalse(f.core.ai().hasApiKey("gemini"));
        f.core.ai().useEnvironment(Map.of("GEMINI_API_KEY", "AIza-test"));
        assertTrue(f.core.ai().hasApiKey("gemini"));
    }
}
