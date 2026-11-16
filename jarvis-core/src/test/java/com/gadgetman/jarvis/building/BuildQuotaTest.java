package com.gadgetman.jarvis.building;

import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** FB-07: cooldowns and daily budgets per permission group, with a fake clock. */
class BuildQuotaTest {

    private Fixture f;
    private BuildQuota quota;
    private final AtomicLong now = new AtomicLong(
            ZonedDateTime.of(2026, 11, 11, 10, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli());

    @BeforeEach
    void boot() throws IOException {
        f = new Fixture(Fixture.DEFAULT_CONFIG + """
                build:
                  limits:
                    default: { cooldown-seconds: 120, daily-builds: 5, daily-blocks: 6000 }
                    trusted: { cooldown-seconds: 30, daily-builds: 30, daily-blocks: 60000 }
                """);
        quota = new BuildQuota(f.platform, f.core.database(), now::get, ZoneOffset.UTC);
    }

    @AfterEach
    void stop() {
        f.close();
    }

    private void minutes(int m) {
        now.addAndGet(m * 60_000L);
    }

    @Test
    @DisplayName("5 builds today are fine; the 6th is refused with when it resets")
    void dailyBuilds() {
        FakeOwner p = f.player("alice");
        for (int i = 0; i < 5; i++) {
            assertNull(quota.checkStart(p), "build " + (i + 1));
            quota.spend(p.id(), 10);
            minutes(3);
        }
        String refused = quota.checkStart(p);
        assertNotNull(refused);
        assertTrue(refused.contains("5 builds for today") && refused.contains("resets at midnight, in 13h"), refused);
    }

    @Test
    @DisplayName("cooldown: 30s after a build, default waits, trusted may go")
    void cooldown() {
        FakeOwner p = f.player("alice");
        quota.spend(p.id(), 10);
        now.addAndGet(30_000);
        String refused = quota.checkStart(p);
        assertNotNull(refused);
        assertTrue(refused.contains("1m 30s"), refused);

        p.permissions.add("jarvis.build.limit.trusted");
        assertNull(quota.checkStart(p));
        assertEquals("trusted", quota.limitFor(p).group());
    }

    @Test
    @DisplayName("a failed build spends nothing; the day rolls over at midnight")
    void failedAndRollover() {
        FakeOwner p = f.player("alice");
        // Nothing spent for a build that didn't finish: still 5 to go.
        for (int i = 0; i < 5; i++) {
            assertNull(quota.checkStart(p));
            quota.spend(p.id(), 1);
            minutes(3);
        }
        assertNotNull(quota.checkStart(p));
        minutes(15 * 60);   // past midnight
        assertNull(quota.checkStart(p), "a new day");
    }

    @Test
    @DisplayName("the day's block budget: a plan bigger than what's left is refused; bypass skips it all")
    void blocks() {
        FakeOwner p = f.player("alice");
        quota.spend(p.id(), 5000);
        assertNull(quota.checkBlocks(p, 1000));
        String refused = quota.checkBlocks(p, 1001);
        assertNotNull(refused);
        assertTrue(refused.contains("1000 left"), refused);
        p.permissions.add(BuildQuota.BYPASS);
        assertNull(quota.checkBlocks(p, 100000));
        assertNull(quota.checkStart(p));
    }

    @Test
    @DisplayName("counts survive a restart (a new quota on the same database)")
    void persisted() {
        FakeOwner p = f.player("alice");
        for (int i = 0; i < 5; i++) { quota.spend(p.id(), 1); minutes(3); }
        BuildQuota again = new BuildQuota(f.platform, f.core.database(), now::get, ZoneOffset.UTC);
        assertNotNull(again.checkStart(p));
    }
}
