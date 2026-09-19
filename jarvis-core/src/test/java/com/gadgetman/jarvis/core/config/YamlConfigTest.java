package com.gadgetman.jarvis.core.config;

import com.gadgetman.jarvis.core.platform.Config;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlConfigTest {

    private static final String DOC = """
            ai:
              provider: auto
              light-timeout-seconds: 5
              log-usage: false
              provider-priority:
                - ollama
                - claude
              ollama:
                endpoint: http://localhost:11434
                model: mistral
              openai:
                api-key: ""
            memory:
              min-text-relevance: 0.55
              enabled: true
            """;

    @Test
    void readsScalarsByDottedPath() {
        Config c = YamlConfig.parse(DOC);
        assertEquals("auto", c.getString("ai.provider", "x"));
        assertEquals(5, c.getInt("ai.light-timeout-seconds", 0));
        assertEquals(0.55, c.getDouble("memory.min-text-relevance", 0), 1e-9);
        assertTrue(c.getBoolean("memory.enabled", false));
        assertFalse(c.getBoolean("ai.log-usage", true));
    }

    @Test
    void missingPathsFallToDefaults() {
        Config c = YamlConfig.parse(DOC);
        assertEquals("d", c.getString("ai.nothing", "d"));
        assertNull(c.getString("ai.nothing"));
        assertEquals(7, c.getInt("nope.deeper", 7));
        assertTrue(c.getStringList("ai.missing-list").isEmpty());
        assertFalse(c.contains("ai.nothing"));
    }

    @Test
    void listsAndSections() {
        Config c = YamlConfig.parse(DOC);
        assertEquals(List.of("ollama", "claude"), c.getStringList("ai.provider-priority"));
        assertTrue(c.isSection("ai.ollama"));
        assertFalse(c.isSection("ai.provider"));
        assertFalse(c.isSection("ai.grok"));

        Config ai = c.section("ai");
        assertEquals("mistral", ai.section("ollama").getString("model"));
        assertEquals("", ai.section("openai").getString("api-key", "def"));
        assertEquals("def", ai.section("grok").getString("api-key", "def"));
        assertEquals(Set.of("ai", "memory"), c.keys());
        assertEquals(Set.of("endpoint", "model"), ai.keys("ollama"));
    }

    @Test
    void fallbackSuppliesWhatTheDocumentLeavesOut() {
        Config defaults = YamlConfig.parse("ai:\n  heavy-timeout-seconds: 240\n  grok:\n    model: grok-4\n");
        Config c = YamlConfig.parse(DOC, defaults);
        assertEquals(240, c.getInt("ai.heavy-timeout-seconds", 0));
        assertEquals(5, c.getInt("ai.light-timeout-seconds", 0));
        assertTrue(c.isSection("ai.grok"));
        assertEquals("grok-4", c.section("ai").section("grok").getString("model"));
    }

    @Test
    void emptyDocumentIsEmptyConfig() {
        Config c = YamlConfig.parse("");
        assertTrue(c.keys().isEmpty());
        assertEquals(1, c.getInt("a", 1));
        Config n = YamlConfig.parse(null);
        assertTrue(n.keys().isEmpty());
    }

    @Test
    void setSaveAndReloadRoundTrip() throws java.io.IOException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("jarvis-config", ".yml");
        java.nio.file.Files.writeString(file, DOC);
        YamlConfig c = YamlConfig.load(file);

        c.set("mining.torch-spacing", 11);
        c.set("ai.provider", "ollama");
        assertEquals(11, c.getInt("mining.torch-spacing", 0), "visible in memory at once");

        c.reload();
        assertEquals("auto", c.getString("ai.provider", "x"), "reload drops what was not saved");

        c.set("mining.torch-spacing", 11);
        c.save();
        YamlConfig again = YamlConfig.load(file);
        assertEquals(11, again.getInt("mining.torch-spacing", 0));
        assertEquals("http://localhost:11434", again.getString("ai.ollama.endpoint", "x"), "the rest survived the write");
    }
}
