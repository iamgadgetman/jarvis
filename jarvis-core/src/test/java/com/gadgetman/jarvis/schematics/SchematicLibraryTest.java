package com.gadgetman.jarvis.schematics;

import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicLibraryTest {

    private Fixture f;
    private FakeOwner p;

    @BeforeEach
    void boot() throws IOException {
        f = new Fixture();
        p = f.summoned("alice");
    }

    @AfterEach
    void stop() {
        f.close();
    }

    /** A 2x2x1 Sponge schematic: stone bricks on the bottom, planks on top. */
    private void writeHut(String name) throws IOException {
        Map<String, Object> palette = new LinkedHashMap<>();
        palette.put(Ids.STONE_BRICKS, 0);
        palette.put(Ids.OAK_PLANKS, 1);
        ByteArrayOutputStream blocks = new ByteArrayOutputStream();
        // Y-Z-X order: y=0 row (2 blocks) then y=1 row
        for (int i = 0; i < 2; i++) Nbt.writeVarInt(blocks, 0);
        for (int i = 0; i < 2; i++) Nbt.writeVarInt(blocks, 1);

        Map<String, Object> nbt = new LinkedHashMap<>();
        nbt.put("Version", 2);
        nbt.put("Width", (short) 2);
        nbt.put("Height", (short) 2);
        nbt.put("Length", (short) 1);
        nbt.put("Palette", palette);
        nbt.put("PaletteMax", 2);
        nbt.put("BlockData", blocks.toByteArray());

        Path file = f.dataDir.resolve("schematics").resolve(name + ".schem");
        Files.createDirectories(file.getParent());
        try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(file));
             DataOutputStream out = new DataOutputStream(gz)) {
            Nbt.write(out, "Schematic", nbt);
        }
    }

    @Test
    @DisplayName("the folder scan finds a .schem and a request matches it by name")
    void scansAndMatches() throws IOException {
        writeHut("storage_shed");
        f.core.schematics().scanFolder();

        assertEquals(1, f.core.schematics().getSchematicCount());
        assertEquals("storage_shed", f.core.schematics().bestMatchName("a shed for storage"));
        assertTrue(f.core.schematics().bestMatchScore("a shed for storage") >= 50);
        assertNull(f.core.schematics().bestMatchName("a diamond sword"));
    }

    @Test
    @DisplayName("the native reader reads the file and pastes it where you stand")
    void pastesNatively() throws IOException {
        writeHut("hut");
        f.core.schematics().scanFolder();

        f.core.schematics().pasteSchematic(p, "hut");
        f.tick(5);

        BlockPos at = p.pos.block();
        assertEquals(Ids.STONE_BRICKS, f.world.block(at).id());
        assertEquals(Ids.STONE_BRICKS, f.world.block(at.offset(1, 0, 0)).id());
        assertEquals(Ids.OAK_PLANKS, f.world.block(at.offset(0, 1, 0)).id());
        assertEquals(Ids.OAK_PLANKS, f.world.block(at.offset(1, 1, 0)).id());
        assertTrue(p.wasTold("Schematic Complete"), String.join("\n", p.plainMessages()));
    }

    @Test
    @DisplayName("an unknown name gets suggestions from the library")
    void suggestsOnMiss() throws IOException {
        writeHut("castle_keep");
        f.core.schematics().scanFolder();

        f.core.schematics().pasteSchematic(p, "casserole");

        assertTrue(p.wasTold("Schematic not found"));
        assertTrue(p.wasTold("Did you mean: castle_keep"), String.join("\n", p.plainMessages()));
    }

    @Test
    @DisplayName("feature tags out-score raw wording when the words differ")
    void featureTagsScore() {
        int raw = SchematicLibrary.scoreMatch("storage_shed", "somewhere to store my loot");
        int tagged = SchematicLibrary.scoreWithFeatures("storage_shed", "somewhere to store my loot",
                new RequestFeatures("storage", "shed", ""));
        assertEquals(0, raw);
        assertEquals(90, tagged);
    }
}
