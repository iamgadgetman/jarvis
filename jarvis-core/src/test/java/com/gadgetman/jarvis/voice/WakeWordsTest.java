package com.gadgetman.jarvis.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WakeWordsTest {

    private final WakeWords wake = new WakeWords(List.of("hey jarvis", "jarvis", "computer"), 2, 4);

    @Test
    @DisplayName("the longest phrase wins and both its words go")
    void longestPhraseFirst() {
        assertEquals("build a shelter.", wake.strip("Hey Jarvis, build a shelter."), "the order keeps its own punctuation");
        assertEquals("mine diamonds", wake.strip("jarvis mine diamonds"));
    }

    @Test
    @DisplayName("filler before the name is dropped, within the scan window")
    void fillerBeforeTheName() {
        assertEquals("build a panic shelter", wake.strip("Again, a Jarvis build a panic shelter"));
        assertNull(wake.strip("one two three four five jarvis come"), "too deep into the sentence");
    }

    @Test
    @DisplayName("a mangled long name still lands; a short word is never fuzzed")
    void fuzzOnLongWordsOnly() {
        assertEquals("come here", wake.strip("Jarvus come here"));
        assertEquals("come here", wake.strip("computed come here"), "one letter off an eight-letter word is within fuzz");
        assertNull(new WakeWords(List.of("hey"), 2, 4).strip("hay come here"), "three letters: exact only");
    }

    @Test
    @DisplayName("not addressed to him")
    void notAddressed() {
        assertNull(wake.strip("what a lovely day"));
        assertNull(wake.strip(""));
        assertNull(wake.strip(null));
    }

    @Test
    @DisplayName("recogniser tags are not orders")
    void cleansTags() {
        assertEquals("", WakeWords.clean("[BLANK_AUDIO]"));
        assertEquals("mine diamonds", WakeWords.clean(" (wind blowing) mine diamonds *sighs* "));
    }
}
