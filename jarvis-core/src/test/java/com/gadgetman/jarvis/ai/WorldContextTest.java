package com.gadgetman.jarvis.ai;

import com.gadgetman.jarvis.core.config.YamlConfig;
import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.FakeWorld.FakeEntity;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldContextTest {

    private Fixture f;
    private FakeOwner steve;

    @BeforeEach
    void boot() throws IOException {
        f = new Fixture();
        steve = f.player("Steve");
    }

    @AfterEach
    void stop() {
        f.close();
    }

    private String render(int level) {
        WorldContext.Settings s = new WorldContext.Settings(level, 16, 32, "knowledge.yml");
        return WorldContext.capture(steve, f.world, null, null, s,
                f.platform.players().online(), id -> id.contains("bread")).render();
    }

    @Test
    @DisplayName("level 0 renders nothing")
    void levelZero() {
        assertEquals("", render(0));
    }

    @Test
    @DisplayName("level 1 has health and the held item, but no biome or weather")
    void levelOne() {
        steve.health = 6;
        steve.heldItem = Item.of("minecraft:iron_sword");
        String out = render(1);
        assertTrue(out.contains("6/20"), out);
        assertTrue(out.contains("Iron sword"), out);
        assertFalse(out.toLowerCase().contains("biome"), out);
        assertFalse(out.toLowerCase().contains("weather"), out);
    }

    @Test
    @DisplayName("an empty hand reads as holding nothing")
    void emptyHand() {
        assertTrue(render(1).contains("holding nothing"), render(1));
    }

    @Test
    @DisplayName("level 2 adds weather, time and biome")
    void levelTwo() {
        f.world.raining = true;
        String out = render(2);
        assertTrue(out.toLowerCase().contains("biome"), out);
        assertTrue(out.toLowerCase().contains("rain"), out);
        assertTrue(out.toLowerCase().contains("time"), out);
        assertTrue(out.contains("Jarvis: not summoned"), out);
        assertFalse(out.toLowerCase().contains("hostile"), "nearby mobs are level 3: " + out);
    }

    @Test
    @DisplayName("level 3 counts hostile mobs by type with the nearest distance")
    void levelThree() {
        f.world.spawnEntity(FakeEntity.hostile("minecraft:zombie", new Vec3(6.5, 64, 0.5)));
        f.world.spawnEntity(FakeEntity.hostile("minecraft:zombie", new Vec3(10.5, 64, 0.5)));
        f.world.spawnEntity(FakeEntity.hostile("minecraft:zombie", new Vec3(40.5, 64, 0.5))); // out of range
        String out = render(3);
        assertTrue(out.contains("2 zombies, nearest 6m"), out);
    }

    @Test
    @DisplayName("level 3 names other players nearby, not the speaker")
    void nearbyPlayers() {
        FakeOwner alex = f.player("Alex");
        alex.pos = new Vec3(10.5, 64, 0.5);
        FakeOwner far = f.player("Faraway");
        far.pos = new Vec3(500.5, 64, 0.5);
        String out = render(3);
        assertTrue(out.contains("Alex"), out);
        assertFalse(out.contains("Faraway"), out);
        assertFalse(out.contains("Players nearby: Steve"), out);
    }

    @Test
    @DisplayName("time of day reads as words")
    void timeWords() {
        assertEquals("dawn", WorldContext.timeOfDay(0));
        assertEquals("day", WorldContext.timeOfDay(6000));
        assertEquals("dusk", WorldContext.timeOfDay(12000));
        assertEquals("night", WorldContext.timeOfDay(13000));
        assertEquals("night", WorldContext.timeOfDay(18000));
        assertEquals("dawn", WorldContext.timeOfDay(23500));
        assertEquals("night", WorldContext.timeOfDay(24000 * 3 + 13000), "full time wraps");
    }

    @Test
    @DisplayName("level 3 with five mob types stays under 600 characters")
    void staysShort() {
        String[] types = {"zombie", "skeleton", "creeper", "spider", "witch"};
        for (int i = 0; i < types.length; i++) {
            for (int n = 0; n < 3; n++) {
                f.world.spawnEntity(FakeEntity.hostile("minecraft:" + types[i], new Vec3(2.5 + i, 64, 2.5 + n)));
            }
        }
        steve.heldItem = Item.of("minecraft:diamond_pickaxe");
        String out = render(3);
        assertTrue(out.length() < 600, out.length() + " chars: " + out);
        assertTrue(out.contains("3 witches"), out);
    }

    @Test
    @DisplayName("settings default to level 2, 16 and 32 blocks, knowledge.yml")
    void settingsDefaults() {
        WorldContext.Settings s = WorldContext.Settings.from(YamlConfig.empty());
        assertEquals(2, s.level());
        assertEquals(16, s.hostileRadius());
        assertEquals(32, s.playerRadius());
        assertEquals("knowledge.yml", s.knowledgeFile());
    }

    @Test
    @DisplayName("an out-of-range level is clamped to 0–3")
    void levelClamped() {
        assertEquals(3, WorldContext.Settings.from(YamlConfig.parse("ai: {context: {level: 9}}")).level());
        assertEquals(0, WorldContext.Settings.from(YamlConfig.parse("ai: {context: {level: -1}}")).level());
    }

    @Test
    @DisplayName("the pipeline's context carries the snapshot")
    void pipelineUsesIt() {
        steve.health = 6;
        String ctx = f.core.intents().buildContext(steve);
        assertTrue(ctx.contains("6/20"), ctx);
        assertTrue(ctx.contains("Jarvis: not summoned"), ctx);
    }

    @Test
    @DisplayName("with Jarvis out, his line reports his task, tool and useful cargo")
    void jarvisLine() {
        FakeOwner owner = f.summoned("Owner");
        var b = f.core.butlers().butler(owner);
        b.setHeldItem(Item.of("minecraft:iron_pickaxe"));
        b.addToInventory(Item.of("minecraft:torch", 12));
        b.addToInventory(Item.of("minecraft:bread", 5));
        b.addToInventory(Item.of("minecraft:dirt", 40));
        WorldContext.Settings s = new WorldContext.Settings(2, 16, 32, "knowledge.yml");
        String out = WorldContext.capture(owner, f.world, b, "Mining", s,
                f.platform.players().online(), id -> id.contains("bread")).render();
        assertTrue(out.contains("Jarvis: at your side"), out);
        assertTrue(out.contains("Mining"), out);
        assertTrue(out.contains("Iron pickaxe"), out);
        assertTrue(out.contains("12 torch"), out);
        assertTrue(out.contains("5 bread"), out);
        assertFalse(out.contains("dirt"), "only highlights, not every stack: " + out);
    }
}
