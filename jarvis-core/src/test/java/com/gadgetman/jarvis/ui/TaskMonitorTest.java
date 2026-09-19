package com.gadgetman.jarvis.ui;

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

class TaskMonitorTest {

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
    @DisplayName("a running task shows on the progress bar and clears when it stops")
    void barFollowsTheTask() {
        f.core.butlers().follow(p);
        f.tick(30);
        assertTrue(f.platform.ui().progressBars.containsKey(p.id()));
        assertTrue(f.platform.ui().progressBars.get(p.id()).startsWith("Working"));

        f.core.butlers().stop(p);
        f.tick(20);
        assertFalse(f.platform.ui().progressBars.containsKey(p.id()));
    }

    @Test
    @DisplayName("queued orders run once he is idle")
    void queueAdvancesWhenIdle() throws InterruptedException {
        f.core.taskMonitor().enqueue(p, "follow");
        assertTrue(p.wasTold("that's 1 in hand"));
        assertEquals(1, f.core.taskMonitor().queueSize(p.id()));

        f.tick(30);                    // first idle sighting
        Thread.sleep(800);             // the queue waits three quarters of a second to be sure
        f.tick(30);

        assertEquals(0, f.core.taskMonitor().queueSize(p.id()));
        assertTrue(p.wasTold("Next: /jarvis follow"), String.join("\n", p.plainMessages()));
        assertEquals(1, f.core.butlers().getActiveTaskCount(), "the follow started");
    }

    @Test
    @DisplayName("clearing the queue tears up the list")
    void clearQueue() {
        f.core.taskMonitor().enqueue(p, "mine");
        f.core.taskMonitor().enqueue(p, "chop 3");
        f.core.taskMonitor().clearQueue(p);
        assertEquals(0, f.core.taskMonitor().queueSize(p.id()));
        assertTrue(p.wasTold("torn up"));
    }
}
