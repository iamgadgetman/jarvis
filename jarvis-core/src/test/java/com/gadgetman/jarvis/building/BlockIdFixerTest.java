package com.gadgetman.jarvis.building;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BlockIdFixerTest {
    private final BlockIdFixer fixer = new BlockIdFixer(Set.of(
            "iron_chain", "copper_chain", "white_bed", "red_bed", "bricks", "stone_bricks",
            "polished_blackstone_bricks", "oak_door", "iron_door", "iron_bars",
            "quartz_pillar", "diorite", "polished_diorite", "dark_oak_planks"));

    @Test
    void namesSeenFailingLiveBuildsAreCorrected() {
        assertEquals("iron_chain", fixer.fix("chain"));
        assertEquals("white_bed", fixer.fix("bed"));
    }

    @Test
    void pluralAndPolishedNearMisses() {
        assertEquals("bricks", fixer.fix("brick"));
        assertEquals("stone_bricks", fixer.fix("stone_brick"));
        assertEquals("polished_blackstone_bricks", fixer.fix("blackstone_bricks"));
    }

    @Test
    void doorDefaultsToOneAPlayerCanOpen() {
        assertEquals("oak_door", fixer.fix("door"));
        assertEquals("iron_bars", fixer.fix("bars"));
    }

    @Test
    void aChangeOfMaterialIsLeftToTheModel() {
        assertNull(fixer.fix("diorite_pillar"));
        assertNull(fixer.fix("dark_oak_bed"));
        assertNull(fixer.fix("unobtainium"));
    }

    @Test
    void statesAreKept() {
        assertEquals("iron_chain[axis=y]", fixer.fixSpec("minecraft:chain[axis=y]", "chain"));
        assertNull(fixer.fixSpec("diorite_pillar[axis=y]", "diorite_pillar"));
    }
}
