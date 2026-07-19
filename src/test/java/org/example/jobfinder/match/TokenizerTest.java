package org.example.jobfinder.match;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenizerTest {

    @Test
    void preservesSpecialTokensAndDropsStopwordsAndPunctuation() {
        Set<String> tokens = Tokenizer.normalize("C++ developer, Node.js, 5+ years experience!!");

        assertTrue(tokens.contains("c++"));
        assertTrue(tokens.contains("node.js"));
        assertTrue(tokens.contains("5+"));
        assertTrue(tokens.contains("developer"));
        assertFalse(tokens.contains("years"));
        assertFalse(tokens.contains("experience"));
    }

    @Test
    void dropsShortTokens() {
        Set<String> tokens = Tokenizer.normalize("a I of go Go");
        assertTrue(tokens.contains("go"));
        assertFalse(tokens.contains("a"));
        assertFalse(tokens.contains("i"));
        assertFalse(tokens.contains("of"));
    }

    @Test
    void blankInputReturnsEmptySet() {
        assertEquals(Set.of(), Tokenizer.normalize(""));
        assertEquals(Set.of(), Tokenizer.normalize(null));
    }

    @Test
    void isCaseInsensitive() {
        Set<String> tokens = Tokenizer.normalize("Java JAVA java");
        assertEquals(Set.of("java"), tokens);
    }
}
