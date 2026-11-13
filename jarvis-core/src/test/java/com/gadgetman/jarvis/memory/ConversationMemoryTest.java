package com.gadgetman.jarvis.memory;

import com.gadgetman.jarvis.DatabaseManager;
import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** FB-04: per-player conversation memory, condensed by the light model as it grows. */
class ConversationMemoryTest {

    private Fixture f;
    private final List<String> asked = new ArrayList<>();

    @AfterEach
    void stop() {
        if (f != null) f.close();
    }

    private ConversationMemory memory(String conversationYaml, ConversationMemory.Summarizer s) throws IOException {
        f = new Fixture(Fixture.DEFAULT_CONFIG.replace("memory:\n  enabled: false",
                "memory:\n  enabled: false\n  conversation:\n" + conversationYaml));
        return new ConversationMemory(f.platform, f.core.database(), s);
    }

    private ConversationMemory.Summarizer fake(String reply) {
        return transcript -> {
            asked.add(transcript);
            return reply;
        };
    }

    private void say(UUID who, int n) {
        DatabaseManager db = f.core.database();
        for (int i = 1; i <= n; i++) db.logChatInteraction(who.toString(), "line " + i, "reply " + i, "chat");
    }

    private int count(String sql, UUID who) throws Exception {
        try (Connection c = f.core.database().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, who.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    @DisplayName("5 turns: contextFor lists all 5 in order, no summary")
    void fewTurnsVerbatim() throws IOException {
        ConversationMemory m = memory("    recent-turns: 8\n", fake("x"));
        UUID p = UUID.randomUUID();
        say(p, 5);
        String ctx = m.contextFor(p, false);
        assertFalse(ctx.contains("Earlier with this player"), ctx);
        int last = -1;
        for (int i = 1; i <= 5; i++) {
            int at = ctx.indexOf("Player: line " + i + "\n");
            assertTrue(at > last, "line " + i + " in order:\n" + ctx);
            last = at;
        }
        assertTrue(ctx.contains("Jarvis said: reply 3"), ctx);
    }

    @Test
    @DisplayName("31 turns, summarize-after 30: one summary call; then the summary plus the last 8")
    void summarizesOnceThenKeepsRecent() throws IOException {
        ConversationMemory m = memory("    recent-turns: 8\n    summarize-after: 30\n", fake("Base is at the big oak by the river."));
        UUID p = UUID.randomUUID();
        say(p, 31);
        assertTrue(m.summarizeIfNeeded(p));
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).contains("Player: line 1\n") && asked.get(0).contains("Player: line 23\n"), asked.get(0));
        assertFalse(asked.get(0).contains("Player: line 24\n"), "the last 8 stay verbatim:\n" + asked.get(0));

        String ctx = m.contextFor(p, false);
        assertTrue(ctx.contains("Earlier with this player: Base is at the big oak by the river."), ctx);
        assertTrue(ctx.contains("Player: line 31") && ctx.contains("Player: line 24"), ctx);
        assertFalse(ctx.contains("Player: line 23\n"), ctx);

        assertFalse(m.summarizeIfNeeded(p), "nothing new to condense");
        assertEquals(1, asked.size());
    }

    @Test
    @DisplayName("the summarizer fails: the old summary is kept, turns stay unsummarized, nothing thrown")
    void summarizerFailureKeepsOld() throws Exception {
        ConversationMemory m = memory("    recent-turns: 2\n    summarize-after: 3\n", fake("First notes."));
        UUID p = UUID.randomUUID();
        say(p, 4);
        assertTrue(m.summarizeIfNeeded(p));
        long covered = m.coveredUntil(p);

        ConversationMemory broken = new ConversationMemory(f.platform, f.core.database(),
                t -> { throw new java.io.IOException("connection refused"); });
        say(p, 6);
        assertDoesNotThrow(() -> broken.summarizeIfNeeded(p));
        assertEquals("First notes.", broken.summary(p));
        assertEquals(covered, broken.coveredUntil(p));
    }

    @Test
    @DisplayName("forget wipes one player's turns, summary and events; another player keeps theirs")
    void forgetIsPerPlayer() throws Exception {
        ConversationMemory m = memory("    recent-turns: 2\n    summarize-after: 3\n", fake("Notes."));
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        say(alice, 5);
        say(bob, 5);
        m.summarizeIfNeeded(alice);
        m.summarizeIfNeeded(bob);
        m.recordEvent(alice, "Died (lava)");
        m.recordEvent(bob, "Died (creeper)");

        m.forget(alice);
        assertEquals(0, count("SELECT COUNT(*) FROM chat_interactions WHERE player_id = ?", alice));
        assertEquals(0, count("SELECT COUNT(*) FROM conversation_summaries WHERE player_id = ?", alice));
        assertEquals(0, count("SELECT COUNT(*) FROM conversation_events WHERE player_id = ?", alice));
        assertEquals(5, count("SELECT COUNT(*) FROM chat_interactions WHERE player_id = ?", bob));
        assertEquals("Notes.", m.summary(bob));
        assertTrue(m.view(bob).events().contains("Died (creeper)"));
        assertTrue(m.view(alice).isEmpty());
    }

    @Test
    @DisplayName("prune: summarized turns older than keep-days go, the summary stays; unsummarized old turns stay")
    void prune() throws Exception {
        ConversationMemory m = memory("    recent-turns: 1\n    summarize-after: 2\n    keep-days: 90\n", fake("Notes."));
        UUID p = UUID.randomUUID();
        long old = System.currentTimeMillis() - 91L * 24 * 60 * 60 * 1000;
        try (Connection c = f.core.database().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO chat_interactions "
                     + "(player_id, player_message, ai_response, action_taken, timestamp) VALUES (?, ?, ?, 'chat', ?)")) {
            for (int i = 0; i < 4; i++) {
                ps.setString(1, p.toString());
                ps.setString(2, "old " + i);
                ps.setString(3, "r");
                ps.setLong(4, old + i);
                ps.executeUpdate();
            }
        }
        assertTrue(m.summarizeIfNeeded(p), "the first 3 condensed, the newest kept verbatim");
        m.prune(System.currentTimeMillis());
        assertEquals(1, count("SELECT COUNT(*) FROM chat_interactions WHERE player_id = ?", p),
                "only the turn the summary doesn't cover is left");
        assertEquals("Notes.", m.summary(p));
    }

    @Test
    @DisplayName("enabled: false: contextFor is empty and nothing is condensed or recorded")
    void disabled() throws Exception {
        ConversationMemory m = memory("    enabled: false\n    summarize-after: 1\n", fake("x"));
        UUID p = UUID.randomUUID();
        say(p, 5);
        assertEquals("", m.contextFor(p, false));
        assertFalse(m.summarizeIfNeeded(p));
        m.recordEvent(p, "Died");
        assertEquals(0, count("SELECT COUNT(*) FROM conversation_events WHERE player_id = ?", p));
        assertTrue(asked.isEmpty());
    }

    @Test
    @DisplayName("events: death and portal lines, shown in the prompt under Lately")
    void events() throws IOException {
        ConversationMemory m = memory("", fake("x"));
        UUID p = UUID.randomUUID();
        m.recordEvent(p, "Died (fell from a high place) at 10 64 -5 in world");
        String ctx = m.contextFor(p, false);
        assertTrue(ctx.contains("Lately:\n  - Died (fell from a high place) at 10 64 -5 in world"), ctx);
    }

    @Test
    @DisplayName("/jarvis memory forget asks first; Confirm wipes it")
    void forgetCommand() throws IOException {
        f = new Fixture();
        FakeOwner p = f.player("alice");
        f.core.database().logChatInteraction(p.id().toString(), "my base is at the big oak", "Noted.", "chat");
        f.core.commands().jarvis(p, Optional.of(p), List.of("memory"), false);
        assertTrue(p.wasTold("my base is at the big oak"), String.join("\n", p.plainMessages()));

        f.core.commands().jarvis(p, Optional.of(p), List.of("memory", "forget"), false);
        assertTrue(p.wasTold("can't be undone"), String.join("\n", p.plainMessages()));
        assertFalse(f.core.conversation().view(p.id()).isEmpty(), "nothing wiped before Confirm");
        f.core.commands().jarvis(p, Optional.of(p), List.of("confirm"), false);
        assertTrue(f.core.conversation().view(p.id()).isEmpty());
        assertTrue(p.wasTold("Forgotten"), String.join("\n", p.plainMessages()));
    }
}
