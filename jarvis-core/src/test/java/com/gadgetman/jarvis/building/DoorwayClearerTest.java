package com.gadgetman.jarvis.building;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DoorwayClearerTest {

    private static Map<String, String> door(String facing) {
        Map<String, String> m = new HashMap<>();
        m.put("7,0,7", "spruce_door[facing=" + facing + ",half=lower]");
        m.put("7,1,7", "spruce_door[facing=" + facing + ",half=upper]");
        return m;
    }

    @Test
    void theFishMarketDoorIsCleared() {
        // As built live: a potted fern hung from a fence, in front of a south-facing door.
        Map<String, String> m = door("south");
        m.put("7,1,6", "potted_fern");
        m.put("7,2,6", "spruce_fence");   // above head height: left alone
        m.put("7,0,8", "blue_carpet");    // walkable: left alone
        List<String> cleared = DoorwayClearer.clear(m);
        assertEquals(List.of("potted_fern at 7,1,6"), cleared);
        assertEquals("air", m.get("7,1,6"));
        assertEquals("spruce_fence", m.get("7,2,6"));
        assertEquals("blue_carpet", m.get("7,0,8"));
    }

    @Test
    void bothSidesAlongTheFacingAxisAreChecked() {
        Map<String, String> m = door("east");
        m.put("8,0,7", "barrel[facing=up]");
        m.put("6,1,7", "lantern[hanging=true]");
        m.put("7,0,8", "barrel[facing=up]"); // beside the door, not in front: left alone
        assertEquals(2, DoorwayClearer.clear(m).size());
        assertEquals("air", m.get("8,0,7"));
        assertEquals("air", m.get("6,1,7"));
        assertEquals("barrel[facing=up]", m.get("7,0,8"));
    }

    @Test
    void buildingBlocksStairsAndStepsAreNeverRemoved() {
        Map<String, String> m = door("north");
        m.put("7,0,6", "oak_stairs[facing=north]");
        m.put("7,1,6", "stone_bricks");
        m.put("7,0,8", "oak_slab[type=bottom]");
        m.put("7,1,8", "glass_pane");
        assertTrue(DoorwayClearer.clear(m).isEmpty());
    }

    @Test
    void aTableTopSlabIsInTheWay() {
        Map<String, String> m = door("north");
        m.put("7,0,6", "spruce_slab[type=top]");
        assertEquals(1, DoorwayClearer.clear(m).size());
    }
}
