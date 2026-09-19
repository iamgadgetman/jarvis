package com.gadgetman.jarvis.steward;

import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StewardTest {

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

    @Test
    @DisplayName("the report reads the time, the weather and the server's health from the platform")
    void morningReport() {
        f.world.time = 7000;
        f.world.raining = true;
        f.world.fullTime = 24000 * 3 + 100;
        f.platform.tps = 15.0;

        f.core.morningReport().deliver(p, false);

        assertTrue(p.wasTold("Good afternoon, sir. Day 3, raining."), String.join("\n", p.plainMessages()));
        assertTrue(p.wasTold("TPS 15.0"));
        assertTrue(p.wasTold("straining"));
        assertTrue(p.wasTold("My bags are empty"));
    }

    @Test
    @DisplayName("a standing duty is kept, listed and struck")
    void duties() {
        DutyScheduler.Duty duty = f.core.duties().addBroadcast("Backups at midnight", 60, 3600, "alice");
        assertEquals(1, f.core.duties().count());

        f.core.duties().showDuties(p);
        assertTrue(p.wasTold("Backups at midnight"), String.join("\n", p.plainMessages()));

        assertTrue(f.core.duties().remove(duty.id));
        assertEquals(0, f.core.duties().count());
        assertFalse(f.core.duties().remove(duty.id));
    }

    @Test
    @DisplayName("the TPS monitor warns admins, and only admins")
    void tpsWarning() {
        FakeOwner admin = f.player("bob");
        admin.permissions.add("jarvis.admin");
        f.platform.tps = 12.0;

        f.tick(1200);

        assertTrue(admin.wasTold("Server TPS is 12.0"));
        assertFalse(p.wasTold("Server TPS"));
    }
}
