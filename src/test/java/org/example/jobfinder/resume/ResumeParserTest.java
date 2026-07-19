package org.example.jobfinder.resume;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeParserTest {

    private static final Path RESUME_PATH =
            Path.of("src/main/resources/Kritika_Goyal_Resume_IN.pdf");

    @Test
    void extractsNonEmptyTextWithKnownTokens() throws Exception {
        ResumeProfile profile = ResumeParser.parse(RESUME_PATH);

        assertFalse(profile.rawText().isBlank());
        String lower = profile.rawText().toLowerCase();
        assertTrue(lower.contains("kubernetes"));
        assertTrue(lower.contains("spring boot"));
    }

    @Test
    void skillsIncludeKeySkillsButNotWorkHistoryNoise() throws Exception {
        ResumeProfile profile = ResumeParser.parse(RESUME_PATH);

        assertTrue(profile.skills().contains("kubernetes"));
        assertTrue(profile.skills().contains("kafka"));
        // "zalando" and "berlin" only appear in the Work Experience section (employer/location),
        // not the Skills section -- they shouldn't be treated as skills to match against.
        assertFalse(profile.skills().contains("zalando"));
        assertFalse(profile.skills().contains("berlin"));
    }
}
