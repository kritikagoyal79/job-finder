package org.example.jobfinder.resume;

import org.example.jobfinder.match.Tokenizer;

import java.util.Set;

public record ResumeProfile(String rawText, Set<String> skills) {

    public static ResumeProfile of(String skillsText) {
        return new ResumeProfile(skillsText, Tokenizer.normalize(skillsText));
    }
}
