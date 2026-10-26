package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.world.Ids;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Blocks.oresNamed: what "mine tin" asks for in a modded registry. */
class OreNamesTest {

    private static final Set<String> REGISTRY = Set.of(
            Ids.DIAMOND_ORE, Ids.DEEPSLATE_DIAMOND_ORE, Ids.STONE,
            "alltheores:tin_ore", "alltheores:deepslate_tin_ore", "alltheores:raw_tin_block",
            "allthemodium:allthemodium_ore", "allthemodium:allthemodium_slate_ore",
            "mekanism:osmium_ore");

    @Test
    @DisplayName("a vanilla keyword keeps its fixed set")
    void vanillaKeyword() {
        assertEquals(Set.of(Ids.DIAMOND_ORE, Ids.DEEPSLATE_DIAMOND_ORE), Blocks.oresNamed("diamonds", REGISTRY));
    }

    @Test
    @DisplayName("a modded ore is found by its word, deepslate twin included, blocks that are not ores left out")
    void moddedWord() {
        assertEquals(Set.of("alltheores:tin_ore", "alltheores:deepslate_tin_ore"), Blocks.oresNamed("tin", REGISTRY));
        assertEquals(Set.of("allthemodium:allthemodium_ore", "allthemodium:allthemodium_slate_ore"),
                Blocks.oresNamed("some allthemodium please", REGISTRY));
        assertEquals(Set.of("mekanism:osmium_ore"), Blocks.oresNamed("osmium ores", REGISTRY));
    }

    @Test
    @DisplayName("a plural and a filler word still find the ore")
    void plural() {
        assertEquals(Set.of("mekanism:osmium_ore"), Blocks.oresNamed("osmiums", REGISTRY));
    }

    @Test
    @DisplayName("words that name no ore mean any ore")
    void noOre() {
        assertNull(Blocks.oresNamed("ore", REGISTRY));
        assertNull(Blocks.oresNamed("whatever you find", REGISTRY));
    }
}
