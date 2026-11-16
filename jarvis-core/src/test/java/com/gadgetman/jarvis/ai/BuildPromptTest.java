package com.gadgetman.jarvis.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** FB-12: rules from the GadgetCraft playtests are in both build prompts. */
class BuildPromptTest {

    @Test
    @DisplayName("the script prompt has the window, chair and cobweb rules")
    void scriptPromptRules() {
        String p = AIConnector.SCRIPT_SYSTEM_PROMPT;
        assertTrue(p.startsWith("You are an expert Minecraft architect"), "text block unchanged by the move");
        assertTrue(p.contains("Every window opening holds glass or a glass_pane"));
        assertTrue(p.contains("BESIDE the opening"));
        assertTrue(p.contains("A stair used as a chair stands alone"));
        assertTrue(p.contains("put one stair on top of another"));
        assertTrue(p.contains("No cobwebs unless the request asks for something abandoned"));
        assertTrue(p.contains("chair west of it:  setBlock(x-1, y, z, \"oak_stairs[facing=west]\")"),
                "playtest: both chairs had their backs to the table");
        assertTrue(p.contains("the top step sits at the SAME height as the upper floor"),
                "playtest: the cottage stairs stopped one step short");
        assertTrue(p.contains("`facing` is the direction\nyou walk to climb"), "staircase rule (7d94569) still there");
    }

    @Test
    @DisplayName("the JSON plan prompt has the same three rules")
    void planPromptRules() {
        String p = AIConnector.PLAN_SYSTEM_PROMPT;
        assertTrue(p.startsWith("You are an expert Minecraft architect"));
        assertTrue(p.contains("Every window opening holds glass or glass_pane"));
        assertTrue(p.contains("A stair used as a chair stands alone"));
        assertTrue(p.contains("No cobwebs unless"));
    }
}
