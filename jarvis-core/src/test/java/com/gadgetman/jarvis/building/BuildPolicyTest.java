package com.gadgetman.jarvis.building;

import com.gadgetman.jarvis.core.world.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** FB-07: what an AI build may not do. */
class BuildPolicyTest {

    private static final BuildPolicy.Limits LIMITS =
            new BuildPolicy.Limits(Set.of("tnt", "lava", "command_block"), 4000, 48, 64);
    private static final BlockPos PLAYER = new BlockPos(0, 64, 0);

    /** A row of n blocks along x from (x0, 64, 0). */
    private static List<BlockPos> row(int x0, int n) {
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new BlockPos(x0 + i % 40, 64 + i / 1600, (i / 40) % 40));
        return out;
    }

    private static List<String> all(String id, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(id);
        return out;
    }

    private static List<String> check(List<BlockPos> pos, List<String> ids) {
        return BuildPolicy.check(LIMITS, pos, ids, PLAYER, -64, 319);
    }

    @Test
    @DisplayName("a plan with TNT names TNT; a clean plan passes")
    void blocked() {
        List<BlockPos> pos = row(0, 10);
        List<String> ids = all("minecraft:stone_bricks", 10);
        assertEquals(List.of(), check(pos, ids));
        ids.set(3, "minecraft:tnt");
        ids.set(7, "tnt");
        ids.set(8, "minecraft:command_block[facing=up]");
        List<String> v = check(pos, ids);
        assertTrue(v.contains("the plan uses 2 tnt; tnt is blocked"), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.contains("command block is blocked")), v.toString());
    }

    @Test
    @DisplayName("4001 blocks is refused, 4000 is fine")
    void blockCount() {
        assertEquals(List.of(), check(row(0, 4000), all("stone", 4000)));
        List<String> v = check(row(0, 4001), all("stone", 4001));
        assertEquals(List.of("the plan is 4001 blocks; the limit is 4000"), v);
    }

    @Test
    @DisplayName("an edge of 49 is refused, 48 is fine")
    void size() {
        List<BlockPos> ok = List.of(new BlockPos(0, 64, 0), new BlockPos(47, 64, 0));
        assertEquals(List.of(), check(ok, all("stone", 2)));
        List<BlockPos> wide = List.of(new BlockPos(0, 64, 0), new BlockPos(48, 64, 0));
        List<String> v = check(wide, all("stone", 2));
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("49x1x1") && v.get(0).contains("48"), v.toString());
    }

    @Test
    @DisplayName("a build 70 blocks away is refused; one that starts at the player's feet is not 'far'")
    void distance() {
        List<String> v = check(List.of(new BlockPos(70, 64, 0), new BlockPos(72, 64, 0)), all("stone", 2));
        assertEquals(List.of("the build is 70 blocks away; the limit is 64"), v);
        // 47 out on both axes: the far corner is 66 away, the build is not.
        assertEquals(List.of(), check(List.of(new BlockPos(0, 64, 0), new BlockPos(47, 64, 47)), all("stone", 2)));
    }

    @Test
    @DisplayName("past the world's height limits is refused")
    void height() {
        List<String> v = BuildPolicy.check(LIMITS, List.of(new BlockPos(0, 318, 0), new BlockPos(0, 320, 0)),
                all("stone", 2), new BlockPos(0, 300, 0), -64, 319);
        assertTrue(v.stream().anyMatch(s -> s.contains("height limits")), v.toString());
    }

    @Test
    @DisplayName("bare ids from specs")
    void bareId() {
        assertEquals("oak_stairs", BuildPolicy.bareId("minecraft:oak_stairs[facing=north]"));
        assertEquals("tnt", BuildPolicy.bareId(" TNT "));
    }
}
