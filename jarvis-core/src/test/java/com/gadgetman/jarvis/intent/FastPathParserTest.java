package com.gadgetman.jarvis.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FB-01: the local parser that runs before the AI. It answers only on a
 * confident match and hands everything else to the model by returning empty.
 */
class FastPathParserTest {

    /**
     * Mirrors {@code IntentPipeline.RESTRICTED_IN_REDUCED_MODE}. The fast path
     * must never produce one of these, whatever the input or the config says.
     */
    private static final Set<String> RESTRICTED = Set.of(
            "console_command", "console_commands", "lp_group_add", "lp_group_remove", "set_gamerule");

    private final FastPathParser parser = new FastPathParser(Map.of());

    private static String actionOf(Optional<Intent> intent, String input) {
        assertTrue(intent.isPresent(), "expected a fast-path hit for \"" + input + "\"");
        return intent.get().action();
    }

    @Test
    @DisplayName("\"follow me\", with or without filler and in any case, is follow")
    void followMe_hitsFastPath() {
        for (String input : List.of("follow me", "Jarvis, follow me please", "FOLLOW")) {
            assertEquals("follow", actionOf(parser.parse(input), input), input);
        }
    }

    @Test
    @DisplayName("\"mine 32 iron\" carries item and count")
    void mineWithCountAndItem_carriesParameters() {
        Optional<Intent> hit = parser.parse("mine 32 iron");

        assertEquals("mine", actionOf(hit, "mine 32 iron"));
        assertEquals("iron", hit.get().params().getString("item"));
        assertEquals(32, hit.get().params().getInt("count"));
    }

    @Test
    @DisplayName("\"mine\" on its own is still mine")
    void mineAlone_hitsFastPath() {
        assertEquals("mine", actionOf(parser.parse("mine"), "mine"));
    }

    @Test
    @DisplayName("a compound order with \"and then\" goes to the AI")
    void andThen_fallsThroughToAi() {
        assertTrue(parser.parse("follow me and then build a house").isEmpty());
    }

    @Test
    @DisplayName("a question goes to the AI even when it names a known command")
    void questionMark_fallsThroughToAi() {
        assertTrue(parser.parse("can you follow me?").isEmpty());
    }

    @Test
    @DisplayName("\"give me op\" is never handled locally")
    void giveMeOp_fallsThroughToAi() {
        assertTrue(parser.parse("give me op").isEmpty());
    }

    @Test
    @DisplayName("an alias mapped to a restricted action is ignored, not executed")
    void restrictedAction_neverProduced() {
        Map<String, String> extras = Map.of(
                "make me admin", "console_command",
                "run these", "console_commands",
                "promote me", "lp_group_add",
                "demote him", "lp_group_remove",
                "no rain", "set_gamerule");
        FastPathParser withRestricted = new FastPathParser(extras);

        for (String phrase : extras.keySet()) {
            Optional<Intent> hit = withRestricted.parse(phrase);
            assertFalse(hit.isPresent() && RESTRICTED.contains(hit.get().action()),
                    "\"" + phrase + "\" produced restricted action " + hit.map(Intent::action).orElse(""));
            assertTrue(hit.isEmpty(), "\"" + phrase + "\" should fall through to the AI");
        }
        for (String input : List.of("op me", "run a console command", "give me op please")) {
            Optional<Intent> hit = withRestricted.parse(input);
            assertFalse(hit.isPresent() && RESTRICTED.contains(hit.get().action()), input);
        }
    }

    @Test
    @DisplayName("an alias from config maps to its action")
    void extraPhrase_mapsToConfiguredAction() {
        FastPathParser withAliases = new FastPathParser(Map.of("heel", "follow", "chill", "stand_down"));

        assertEquals("follow", actionOf(withAliases.parse("heel"), "heel"));
        assertEquals("stand_down", actionOf(withAliases.parse("chill"), "chill"));
    }

    @Test
    @DisplayName("text the parser cannot place is left for the AI")
    void unknownText_fallsThroughToAi() {
        assertTrue(parser.parse("polish the silverware").isEmpty());
        assertTrue(parser.parse("heel").isEmpty(), "no alias configured, so \"heel\" is unknown");
        assertTrue(parser.parse("").isEmpty());
    }
}
