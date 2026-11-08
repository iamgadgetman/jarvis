package com.gadgetman.jarvis.building;

import com.gadgetman.jarvis.core.platform.Log;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The clean-up passes a script plan goes through: ladders kept, low hanging
 * decor trimmed, closed-off rooms opened, and item ids left out rather than
 * repaired. The fish market is the live build that prompted all four.
 */
class BuildCleanupTest {

    private static ScriptBuildPlanner planner;

    @BeforeAll
    static void start() {
        planner = new ScriptBuildPlanner(Log.of(Logger.getLogger("test")), 20000, 10000, 128, 128, 200000, null);
    }

    @AfterAll
    static void stop() {
        planner.shutdown();
    }

    private static Map<String, String> asMap(ScriptBuildPlanner.Result r) {
        Map<String, String> m = new LinkedHashMap<>();
        for (ScriptBuildPlanner.PlannedBlock b : r.blocks) m.put(b.dx + "," + b.dy + "," + b.dz, b.spec);
        return m;
    }

    private static String script(String name) throws Exception {
        try (InputStream in = BuildCleanupTest.class.getResourceAsStream("/build-scripts/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void fishMarketComesOutWalkableWithAClimbableLoft() throws Exception {
        ScriptBuildPlanner.Result r = planner.run(script("fish-market.js"));
        Map<String, String> m = asMap(r);

        // The loft ladder: four rungs at x=5, z=1. The script overwrote three.
        for (int y = 0; y <= 3; y++) {
            assertTrue(m.get("5," + y + ",1").startsWith("ladder"), "rung at y=" + y + " is " + m.get("5," + y + ",1"));
        }
        assertEquals(3, r.ladderKeeps.size(), r.ladderKeeps.toString());

        // Roots hung to y=1 (chest height); only the y=2 roots may stay.
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getValue().startsWith("hanging_roots")) {
                assertEquals("2", e.getKey().split(",")[1], "low roots left at " + e.getKey());
            }
        }
        assertFalse(r.hangingTrims.isEmpty());

        // The counter closed off the back of the shop; a path was opened...
        assertFalse(r.walkwayClears.isEmpty());
        assertTrue(r.walkwayClears.size() <= WalkwayClearer.MAX_TOTAL);
        // ...without touching a wall, and nothing is left closed off.
        for (String c : r.walkwayClears) {
            assertFalse(c.contains("planks") || c.contains("log") || c.contains("glass"), c);
        }
        assertTrue(WalkwayClearer.clear(new HashMap<>(m)).isEmpty(), "a second pass still found closed rooms");
    }

    @Test
    void aRoomWithAnOpenDoorwayIsLeftAlone() {
        Map<String, String> m = room();
        m.put("2,0,0", "air");
        m.put("2,1,0", "air");
        m.put("1,0,2", "barrel");
        assertTrue(WalkwayClearer.clear(m).isEmpty());
        assertEquals("barrel", m.get("1,0,2"));
    }

    @Test
    void aFurnishingAcrossTheOnlyWayInIsRemovedButNotTheWall() {
        // A room open at the front; a row of barrels shuts the back half off.
        Map<String, String> m = room();
        m.put("2,0,0", "air");
        m.put("2,1,0", "air");
        for (int x = 1; x <= 3; x++) m.put(x + ",0,2", "barrel");
        for (int x = 1; x <= 3; x++) m.put(x + ",1,2", "barrel");
        List<String> removed = WalkwayClearer.clear(m);
        assertEquals(2, removed.size(), removed.toString());
        for (String s : removed) assertTrue(s.startsWith("barrel"), s);
        assertTrue(WalkwayClearer.clear(m).isEmpty());
    }

    @Test
    void aSealedRoomIsNotBrokenInto() {
        // No opening at all: the only way in is through a wall, which is never cut.
        Map<String, String> m = room();
        assertTrue(WalkwayClearer.clear(m).isEmpty());
        for (Map.Entry<String, String> e : room().entrySet()) assertEquals(e.getValue(), m.get(e.getKey()));
    }

    @Test
    void hangingDecorStopsAtHeadHeight() {
        Map<String, String> m = new HashMap<>();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) m.put(x + ",-1," + z, "oak_planks");
        m.put("1,4,1", "oak_planks");
        m.put("1,3,1", "iron_chain[axis=y]");
        m.put("1,2,1", "lantern[hanging=true]");
        m.put("0,4,0", "oak_planks");
        m.put("0,3,0", "hanging_roots");
        m.put("0,2,0", "hanging_roots");
        m.put("0,1,0", "hanging_roots");
        m.put("2,1,2", "lantern[hanging=false]");
        List<String> trimmed = HangingTrimmer.trim(m);
        assertEquals(List.of("hanging_roots at 0,1,0"), trimmed);
        assertEquals("lantern[hanging=true]", m.get("1,2,1"));
        assertEquals("lantern[hanging=false]", m.get("2,1,2"));
    }

    @Test
    void anItemIdIsLeftOutWithoutARepair() throws Exception {
        ScriptBuildPlanner p = new ScriptBuildPlanner(Log.of(Logger.getLogger("test")), 1000, 10000, 64, 64, 10000,
                Set.of("stone", "air", "barrel"), Set.of("cod_bucket", "cod"));
        try {
            ScriptBuildPlanner.Result r = p.run("function buildCreation(x,y,z){"
                    + "fill(x,y,z,x+2,y,z+2,'stone'); setBlock(x+1,y+1,z+1,'cod_bucket'); setBlock(x,y+1,z,'barrel');}");
            assertEquals(Set.of("cod_bucket"), r.droppedItems);
            assertEquals(10, r.blocks.size());
            // An unknown id that is not an item still goes back for repair.
            ScriptBuildPlanner.ScriptException e = assertThrows(ScriptBuildPlanner.ScriptException.class,
                    () -> p.run("function buildCreation(x,y,z){ setBlock(x,y,z,'fishy_block'); }"));
            assertTrue(e.getMessage().contains("fishy_block"));
        } finally {
            p.shutdown();
        }
    }

    /** A closed plank room, 5 wide and 7 deep, walls 3 high, roofed, on a floor. */
    private static Map<String, String> room() {
        Map<String, String> m = new HashMap<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 7; z++) {
                m.put(x + ",-1," + z, "oak_planks");
                m.put(x + ",3," + z, "oak_planks");
                boolean edge = x == 0 || x == 4 || z == 0 || z == 6;
                for (int y = 0; y < 3; y++) if (edge) m.put(x + "," + y + "," + z, "oak_planks");
            }
        }
        return m;
    }
}
