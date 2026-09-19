package com.gadgetman.jarvis.ai;

import com.gadgetman.jarvis.core.testing.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSettingsTest {

    private Fixture f;
    private AiSettings s;

    @BeforeEach
    void boot() throws IOException {
        f = new Fixture();
        s = f.core.aiSettings();
    }

    @AfterEach
    void stop() {
        f.close();
    }

    @Test
    @DisplayName("enabling and disabling a provider writes the priority list and the connector follows")
    void enableAndDisable() {
        assertTrue(s.isEnabled("claude"), "the default list has every provider");
        s.setEnabled("claude", false);
        assertFalse(s.isEnabled("claude"));
        assertFalse(f.core.ai().isEnabled("claude"));
        assertEquals(List.of("ollama", "openai", "grok", "gemini"), f.platform.config().getStringList("ai.provider-priority"));
        assertEquals("disabled", s.status("claude"));

        s.setEnabled("claude", true);
        assertTrue(f.core.ai().isEnabled("claude"));
        assertEquals(List.of("ollama", "claude", "openai", "grok", "gemini"), s.enabled(), "back in its usual place");
    }

    @Test
    @DisplayName("a key, a model and an address reach the connector at once")
    void keyModelAndEndpoint() {
        assertFalse(s.hasKey("claude"));
        assertEquals("no API key", s.status("claude"));
        s.setKey("claude", "  sk-ant-test  ");
        assertTrue(s.hasKey("claude"));
        assertEquals("sk-ant-test", f.platform.config().getString("ai.claude.api-key", ""));
        assertEquals("available", s.status("claude"));

        s.setModel("claude", "claude-sonnet-5");
        assertEquals("claude-sonnet-5", s.model("claude"));

        assertFalse(s.setEndpoint("ollama", "10.0.0.5:11434"), "needs a scheme");
        assertTrue(s.setEndpoint("ollama", "http://10.0.0.5:11434/"));
        assertEquals("http://10.0.0.5:11434", s.endpoint("ollama"), "trailing slash dropped");
        assertTrue(s.setEndpoint("ollama", ""), "blank means the default");
        assertEquals("http://localhost:11434", s.endpoint("ollama"));
    }

    @Test
    void namesResolveLoosely() {
        assertEquals("claude", s.resolve("Anthropic"));
        assertEquals("grok", s.resolve(" xai "));
        assertNull(s.resolve("bard"));
        assertTrue(s.needsKey("openai"));
        assertFalse(s.needsKey("ollama"));
    }

    @Test
    @DisplayName("a test against nothing reports failure with a reason, and a model listing likewise")
    void testAndModelsAgainstNothing() {
        AtomicReference<String> verdict = new AtomicReference<>();
        s.test("ollama", verdict::set);
        assertTrue(verdict.get().startsWith("failed:"), verdict.get());

        AtomicReference<String> why = new AtomicReference<>();
        s.ollamaModels(models -> why.set("unexpected " + models), why::set);
        assertFalse(why.get().startsWith("unexpected"), why.get());

        s.test("claude", verdict::set);
        assertTrue(verdict.get().contains("no API key"), verdict.get());
    }
}
