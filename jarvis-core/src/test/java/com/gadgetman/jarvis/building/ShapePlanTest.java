package com.gadgetman.jarvis.building;

import org.json.JSONArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapePlanTest {

    private static Map<String, String> expand(String ops) {
        ShapePlan.Result r = ShapePlan.expand(new JSONArray(ops), 5000);
        Map<String, String> at = new HashMap<>();
        for (ShapePlan.Block b : r.blocks()) at.put(b.x() + "," + b.y() + "," + b.z(), b.spec());
        return at;
    }

    @Test
    @DisplayName("fill, walls and hollow enumerate the box they describe")
    void boxes() {
        assertEquals(27, expand("[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"stone\"}]").size());
        Map<String, String> walls = expand("[{\"op\":\"walls\",\"from\":[0,0,0],\"to\":[3,2,3],\"block\":\"oak_planks\"}]");
        assertEquals(12 * 3, walls.size(), "a 4x4 ring, three high");
        assertNull(walls.get("1,0,1"), "walls leave the floor alone");
        assertEquals("minecraft:oak_planks", walls.get("0,1,2"));
        Map<String, String> hollow = expand("[{\"op\":\"hollow\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"stone\"}]");
        assertEquals(26, hollow.size(), "a 3x3x3 shell has one block missing: the middle");
        assertNull(hollow.get("1,1,1"));
    }

    @Test
    @DisplayName("later ops overwrite: a doorway is a wall, then a door with both halves")
    void doorOverwritesTheWall() {
        Map<String, String> at = expand("[{\"op\":\"walls\",\"from\":[0,0,0],\"to\":[4,3,4],\"block\":\"stone_bricks\"},"
                + "{\"op\":\"door\",\"at\":[2,0,0],\"facing\":\"south\",\"block\":\"oak_door\"}]");
        assertEquals("minecraft:oak_door[facing=south,half=lower,hinge=left]", at.get("2,0,0"));
        assertEquals("minecraft:oak_door[facing=south,half=upper,hinge=left]", at.get("2,1,0"));
        assertEquals("minecraft:stone_bricks", at.get("2,2,0"), "the wall continues above the door");
    }

    @Test
    @DisplayName("a door or a window asked for one block off the wall moves into the wall")
    void fittingsSnapIntoTheWall() {
        // Wall along z=0; the model put the door at z=-1 and a pane at z=1.
        ShapePlan.Result r = ShapePlan.expand(new JSONArray(
                "[{\"op\":\"walls\",\"from\":[0,0,0],\"to\":[6,3,6],\"block\":\"oak_planks\"},"
                + "{\"op\":\"door\",\"at\":[3,0,-1],\"facing\":\"north\"},"
                + "{\"op\":\"set\",\"at\":[1,1,1],\"block\":\"glass_pane\"},"
                + "{\"op\":\"set\",\"at\":[3,1,3],\"block\":\"crafting_table\"}]"), 5000);
        Map<String, String> at = new HashMap<>();
        for (ShapePlan.Block b : r.blocks()) at.put(b.x() + "," + b.y() + "," + b.z(), b.spec());

        assertEquals("minecraft:oak_door[facing=north,half=lower,hinge=left]", at.get("3,0,0"));
        assertEquals("minecraft:oak_door[facing=north,half=upper,hinge=left]", at.get("3,1,0"));
        assertNull(at.get("3,0,-1"), "nothing left outside the wall");
        assertEquals("minecraft:glass_pane", at.get("1,1,0"));
        assertNull(at.get("1,1,1"));
        assertEquals("minecraft:crafting_table", at.get("3,1,3"), "furniture is not snapped anywhere");
        assertEquals(2, r.warnings().size(), String.join(" | ", r.warnings()));
    }

    @Test
    @DisplayName("a bed's head sits one block along its facing")
    void bedHasTwoParts() {
        Map<String, String> at = expand("[{\"op\":\"bed\",\"at\":[1,0,1],\"facing\":\"east\"}]");
        assertEquals("minecraft:red_bed[facing=east,part=foot]", at.get("1,0,1"));
        assertEquals("minecraft:red_bed[facing=east,part=head]", at.get("2,0,1"));
    }

    @Test
    @DisplayName("a roof steps in from both eaves to a ridge, faces up-slope, and closes its gables")
    void roofProfile() {
        // A 5-wide (x) by 7-deep (z) footprint at y=4; the ridge runs along x.
        Map<String, String> at = expand("[{\"op\":\"roof\",\"from\":[0,4,0],\"to\":[4,4,6],\"ridge\":\"x\",\"block\":\"oak_stairs\",\"gable\":\"oak_planks\"}]");
        assertEquals("minecraft:oak_stairs[facing=south]", at.get("2,4,0"), "north eave faces south, up the slope");
        assertEquals("minecraft:oak_stairs[facing=north]", at.get("2,4,6"));
        assertEquals("minecraft:oak_stairs[facing=south]", at.get("2,5,1"), "next course one in and one up");
        assertEquals("minecraft:oak_stairs[facing=south]", at.get("2,6,2"));
        assertEquals("minecraft:oak_planks", at.get("2,7,3"), "ridge at the centre row");
        assertEquals("minecraft:oak_stairs[facing=south]", at.get("-1,4,0"), "eaves overhang the ends by one");
        assertEquals("minecraft:oak_planks", at.get("0,4,3"), "gable triangle closed at the end wall");
        assertEquals("minecraft:oak_planks", at.get("4,5,3"));
        assertNull(at.get("2,4,3"), "nothing floats under the ridge");
    }

    @Test
    @DisplayName("an even-depth roof gets a two-block ridge, not a gap")
    void evenRoofHasNoGap() {
        Map<String, String> at = expand("[{\"op\":\"roof\",\"from\":[0,4,0],\"to\":[9,4,7],\"block\":\"spruce_stairs\",\"gable\":\"spruce_planks\"}]");
        assertEquals("minecraft:spruce_planks", at.get("3,7,3"));
        assertEquals("minecraft:spruce_planks", at.get("3,7,4"));
        assertEquals("minecraft:spruce_stairs[facing=north]", at.get("3,6,5"));
    }

    @Test
    @DisplayName("an unknown op or a missing corner is reported and skipped; the rest still builds")
    void badOpsAreSkipped() {
        ShapePlan.Result r = ShapePlan.expand(new JSONArray("[{\"op\":\"teleport\"},"
                + "{\"op\":\"fill\",\"to\":[1,1,1],\"block\":\"stone\"},"
                + "{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[0,0,500],\"block\":\"stone\"},"
                + "{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"minecraft:chest[facing=west]\"}]"), 5000);
        assertEquals(3, r.warnings().size(), String.join(" | ", r.warnings()));
        assertEquals(1, r.blocks().size());
        assertEquals("minecraft:chest[facing=west]", r.blocks().get(0).spec());
        assertFalse(r.truncated());
    }

    @Test
    @DisplayName("the block cap stops a runaway plan")
    void capStopsExpansion() {
        ShapePlan.Result r = ShapePlan.expand(new JSONArray("[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[20,20,20],\"block\":\"stone\"}]"), 100);
        assertTrue(r.truncated());
        assertEquals(100, r.blocks().size());
    }
}
