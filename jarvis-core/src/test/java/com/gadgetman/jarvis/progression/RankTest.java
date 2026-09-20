package com.gadgetman.jarvis.progression;

import com.gadgetman.jarvis.core.world.Ids;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankTest {

    @Test
    void toolsFollowTheRankMetal() {
        assertEquals(Ids.IRON_SWORD, Rank.HIRED.toolFor(Rank.ToolKind.SWORD));
        assertEquals(Ids.DIAMOND_AXE, Rank.RELIABLE.toolFor(Rank.ToolKind.AXE));
        assertEquals(Ids.NETHERITE_HOE, Rank.PEERLESS.toolFor(Rank.ToolKind.HOE));
        assertEquals(Ids.TRIDENT, Rank.WITHOUT_EQUAL.toolFor(Rank.ToolKind.TRIDENT));
        assertEquals(Ids.BOW, Rank.HIRED.toolFor(Rank.ToolKind.BOW));
        assertEquals(Ids.FISHING_ROD, Rank.VALUED.toolFor(Rank.ToolKind.ROD));
    }

    @Test
    void ladderIsMonotonic() {
        assertEquals(Rank.HIRED, Rank.forService(0));
        assertEquals(Rank.ACQUAINTED, Rank.forService(25));
        assertEquals(Rank.WITHOUT_EQUAL, Rank.forService(99_999));
        assertNull(Rank.top().next());
        assertEquals(Rank.ACQUAINTED, Rank.HIRED.next());
    }

    @Test
    void promotionLineNamesWhatChanged() {
        assertEquals("an iron kit, unbreakable", Rank.HIRED.whatIsNew());
        assertEquals("Efficiency I", Rank.ACQUAINTED.whatIsNew());
        assertTrue(Rank.RELIABLE.whatIsNew().startsWith("a diamond kit"));
        String top = Rank.WITHOUT_EQUAL.whatIsNew();
        assertTrue(top.contains("Fire aspect II"));
        assertTrue(top.contains("a trident, for fighting in water"));
    }

    @Test
    void lookupByNameOrNumber() {
        assertEquals(Rank.PEERLESS, Rank.byNameOrNumber("peerless"));
        assertEquals(Rank.PEERLESS, Rank.byNameOrNumber("9"));
        assertEquals(Rank.WITHOUT_EQUAL, Rank.byNameOrNumber("Without Equal"));
        assertNull(Rank.byNameOrNumber("42"));
        assertNull(Rank.byNameOrNumber(""));
    }
}
