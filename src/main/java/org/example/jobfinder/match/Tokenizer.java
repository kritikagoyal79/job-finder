package org.example.jobfinder.match;

import java.util.HashSet;
import java.util.Set;

public final class Tokenizer {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "or", "is", "are", "in", "of", "to", "a", "an", "with", "for", "on", "at",
            "by", "from", "as", "be", "this", "that", "it", "its", "we", "you", "your", "our", "will",
            "can", "have", "has", "had", "not", "but", "if", "into", "than", "then", "so", "such",
            "who", "which", "what", "their", "they", "these", "those", "any", "all", "etc",
            "experience", "role", "opportunity", "responsibilities", "requirements", "skills",
            "team", "environment", "candidate", "join", "including", "years", "strong", "proven",
            "track", "record", "working", "work", "ability", "knowledge", "using", "build", "built",
            "develop", "developed"
    );

    private Tokenizer() {
    }

    public static Set<String> normalize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String cleaned = text.toLowerCase().replaceAll("[^a-z0-9+#.\\s]", " ");
        Set<String> tokens = new HashSet<>();
        for (String raw : cleaned.split("\\s+")) {
            String token = raw.replaceAll("^\\.+|\\.+$", "");
            if (token.length() < 2 || STOPWORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }
}
