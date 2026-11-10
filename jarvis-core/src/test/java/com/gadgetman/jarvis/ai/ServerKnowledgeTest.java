package com.gadgetman.jarvis.ai;

import com.gadgetman.jarvis.core.testing.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerKnowledgeTest {

    private static final String SAMPLE = """
            facts:
              - "Spawn is at 0,64,0"
              - "PvP is off"
            locations:
              market:
                x: 120
                y: 70
                z: -40
                note: "player shops"
              farm: "east of spawn, past the river"
            """;

    @Test
    @DisplayName("parses facts and named locations")
    void parses() {
        ServerKnowledge k = ServerKnowledge.parse(SAMPLE);
        assertEquals(List.of("Spawn is at 0,64,0", "PvP is off"), k.facts());
        assertEquals(2, k.locations().size());
        String out = k.render();
        assertTrue(out.contains("treat as true"), out);
        assertTrue(out.contains("PvP is off"), out);
        assertTrue(out.contains("market: x 120, y 70, z -40"), out);
        assertTrue(out.contains("player shops"), out);
        assertTrue(out.contains("farm: east of spawn"), out);
    }

    @Test
    @DisplayName("a missing file is an empty block, not an exception")
    void missingFile() {
        ServerKnowledge k = ServerKnowledge.load(Path.of("/nonexistent/jarvis/knowledge.yml"), null);
        assertTrue(k.isEmpty());
        assertEquals("", k.render());
    }

    @Test
    @DisplayName("a broken file is an empty block, not an exception")
    void brokenFile() throws IOException {
        Path dir = Files.createTempDirectory("jarvis-knowledge");
        Path file = dir.resolve("knowledge.yml");
        Files.writeString(file, "facts: [unclosed\n  : : :");
        assertEquals("", ServerKnowledge.load(file, null).render());
    }

    @Test
    @DisplayName("an example file with only comments is empty")
    void commentsOnly() {
        assertTrue(ServerKnowledge.parse("# facts:\n#   - nothing yet\n").isEmpty());
    }

    @Test
    @DisplayName("core loads knowledge.yml from the data folder and re-reads it on reload")
    void hotReload() throws IOException {
        try (Fixture f = new Fixture()) {
            assertTrue(f.core.knowledge().isEmpty());
            Files.writeString(f.dataDir.resolve("knowledge.yml"), SAMPLE);
            f.core.reload();
            assertTrue(f.core.knowledge().render().contains("PvP is off"));
        }
    }
}
