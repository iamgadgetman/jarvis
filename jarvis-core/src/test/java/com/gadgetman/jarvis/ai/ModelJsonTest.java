package com.gadgetman.jarvis.ai;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelJsonTest {

    private static final String PLAN = "{\"dimensions\":{\"width\":2,\"height\":1,\"length\":1},"
            + "\"blocks\":[{\"x\":0,\"y\":0,\"z\":0,\"material\":\"minecraft:stone\"},"
            + "{\"x\":1,\"y\":0,\"z\":0,\"material\":\"minecraft:oak_planks\"}]}";

    @Test
    @DisplayName("a bare object, a fenced one and one wrapped in prose all come out the same")
    void extractsTheObject() {
        assertEquals(PLAN, ModelJson.extractObject(PLAN));
        assertEquals(PLAN, ModelJson.extractObject("```json\n" + PLAN + "\n```"));
        assertEquals(PLAN, ModelJson.extractObject("Here is your cottage:\n" + PLAN + "\nEnjoy!"));
        assertEquals("{\"d\":\"a } inside\"}", ModelJson.extractObject("x {\"d\":\"a } inside\"} y"));
        assertEquals("", ModelJson.extractObject(null));
        assertEquals("no json here", ModelJson.extractObject("no json here"));
    }

    @Test
    @DisplayName("a reply cut off by an output cap keeps the blocks that arrived whole")
    void salvagesATruncatedBlockList() {
        String cut = "```json\n" + PLAN.substring(0, PLAN.indexOf("minecraft:oak_planks") + 5);
        assertTrue(cut.endsWith("minec"), cut);

        List<JSONObject> blocks = ModelJson.salvageArray(cut, "blocks");

        assertEquals(1, blocks.size());
        assertEquals("minecraft:stone", blocks.get(0).getString("material"));
    }

    @Test
    @DisplayName("a reply with no block list at all salvages nothing")
    void salvagesNothingFromGarbage() {
        assertTrue(ModelJson.salvageArray("I cannot design that, sorry.", "blocks").isEmpty());
        assertTrue(ModelJson.salvageArray("{\"blocks\": \"none\"}", "blocks").isEmpty());
    }

    @Test
    @DisplayName("a malformed element in the middle is skipped, not fatal")
    void skipsABrokenElement() {
        String text = "{\"blocks\":[{\"x\":0,\"y\":0,\"z\":0,\"material\":\"minecraft:stone\"},"
                + "{\"x\":1,,},"
                + "{\"x\":2,\"y\":0,\"z\":0,\"material\":\"minecraft:glass\"}";

        List<JSONObject> blocks = ModelJson.salvageArray(text, "blocks");

        assertEquals(2, blocks.size());
        assertEquals(2, blocks.get(1).getInt("x"));
    }
}
