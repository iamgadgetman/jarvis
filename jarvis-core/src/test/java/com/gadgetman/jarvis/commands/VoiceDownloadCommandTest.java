package com.gadgetman.jarvis.commands;

import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Voice models are installed only when an admin asks (store rules): /jarvis voice download. */
class VoiceDownloadCommandTest {

    private Fixture f;

    @BeforeEach
    void boot() throws IOException {
        f = new Fixture();
    }

    @AfterEach
    void stop() {
        f.close();
    }

    private void run(FakeOwner p, String... args) {
        f.core.commands().jarvis(p, Optional.of(p), List.of(args), false);
    }

    @Test
    @DisplayName("/jarvis voice download lists the files, size and source, and fetches nothing")
    void listsWithoutFetching() {
        FakeOwner p = f.player("alice");
        p.permissions.add("jarvis.admin");
        run(p, "voice", "download");
        String all = String.join("\n", p.plainMessages());
        assertTrue(all.contains("about 210 MB from huggingface.co"), all);
        assertTrue(all.contains("ggml-base.en.bin  from  https://huggingface.co/"), all);
        assertTrue(all.contains("/jarvis voice download confirm"), all);
        assertFalse(Files.exists(f.dataDir.resolve("models")), "nothing written");
    }

    @Test
    @DisplayName("turning voice on doesn't fetch the models; it says how to install them")
    void enableDoesNotFetch() {
        FakeOwner p = f.player("alice");
        p.permissions.add("jarvis.admin");
        run(p, "voice", "enable");
        assertTrue(p.wasTold("/jarvis voice download"), String.join("\n", p.plainMessages()));
        assertFalse(Files.exists(f.dataDir.resolve("models")));
    }

    @Test
    @DisplayName("only admins can download")
    void adminsOnly() {
        FakeOwner p = f.player("bob");
        run(p, "voice", "download", "confirm");
        assertTrue(p.wasTold("permission"), String.join("\n", p.plainMessages()));
        assertFalse(Files.exists(f.dataDir.resolve("models")));
    }
}
