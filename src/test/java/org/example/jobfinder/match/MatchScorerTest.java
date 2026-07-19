package org.example.jobfinder.match;

import org.example.jobfinder.resume.ResumeProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchScorerTest {

    @Test
    void strongSkillAndTitleMatchScoresHigh() {
        ResumeProfile resume = ResumeProfile.of("java spring kubernetes kafka aws docker microservices redis");
        JobListing job = new JobListing("test", "1", "Senior Java Developer", "Acme", "Remote", "http://x",
                "We need an engineer skilled in java spring kubernetes kafka aws docker microservices redis "
                        + "for backend systems");

        MatchResult result = MatchScorer.score(resume, job, List.of("Java Developer"));

        assertEquals(100, result.score());
    }

    @Test
    void titleMatchAloneDoesNotScoreHundred() {
        // Regression test for the bug this replaced: a thin/empty job description used to score
        // 100% purely because the title words ("software", "engineer") also appeared in the
        // resume's raw text. Skills must independently overlap with the description to score high.
        ResumeProfile resume = ResumeProfile.of("java spring kubernetes kafka aws docker microservices redis");
        JobListing job = new JobListing("test", "1", "Software Engineer", "Acme", "Remote", "http://x", "");

        MatchResult result = MatchScorer.score(resume, job, List.of("Software Engineer"));

        assertEquals(50, result.score());
    }

    @Test
    void noOverlapScoresZero() {
        ResumeProfile resume = ResumeProfile.of("java spring kubernetes");
        JobListing job = new JobListing("test", "1", "Pastry Chef", "Acme", "Remote", "http://x",
                "Cooking baking pastry");

        MatchResult result = MatchScorer.score(resume, job, List.of("Software Engineer"));

        assertEquals(0, result.score());
    }

    @Test
    void thinDescriptionDampensSkillScoreEvenWithFullOverlap() {
        // descriptionTokens = {python, django} (2 tokens) -> confidence = 2/8 -> skillScore = 25
        // titleScore = 100 (full coverage) -> combined = round((25 + 100) / 2) = 63
        ResumeProfile resume = ResumeProfile.of("python django");
        JobListing job = new JobListing("test", "1", "Python Developer", "Acme", "Remote", "http://x",
                "python django");

        MatchResult result = MatchScorer.score(resume, job, List.of("Python Developer"));

        assertEquals(63, result.score());
    }

    @Test
    void matchedKeywordsIncludeBothSkillAndTitleOverlap() {
        ResumeProfile resume = ResumeProfile.of("java spring kubernetes kafka aws docker microservices redis");
        JobListing job = new JobListing("test", "1", "Java Developer", "Acme", "Remote", "http://x",
                "backend role using java spring kubernetes kafka aws docker microservices redis daily");

        MatchResult result = MatchScorer.score(resume, job, List.of("Java Developer"));

        assertTrue(result.matchedKeywords().contains("java"));
        assertTrue(result.matchedKeywords().contains("developer"));
    }
}
