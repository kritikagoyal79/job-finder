package org.example.jobfinder.match;

import org.example.jobfinder.resume.ResumeProfile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MatchScorer {

    // Portal search-result pages often surface only a title + one-line snippet rather than the
    // full job description. With few description tokens, the overlap coefficient below can hit
    // 100% off just 1-2 generic words. Dampen the skill score when the description is this thin.
    private static final int MIN_DESCRIPTION_TOKENS_FOR_FULL_CONFIDENCE = 8;

    private MatchScorer() {
    }

    public static MatchResult score(ResumeProfile resume, JobListing job, List<String> desiredTitles) {
        Set<String> skillTokens = resume.skills();
        Set<String> descriptionTokens = Tokenizer.normalize(job.descriptionText());
        Set<String> jobTitleTokens = Tokenizer.normalize(job.title());

        Set<String> skillOverlap = new HashSet<>(skillTokens);
        skillOverlap.retainAll(descriptionTokens);
        int skillDenominator = Math.min(skillTokens.size(), descriptionTokens.size());
        double rawSkillScore = skillDenominator == 0 ? 0 : 100.0 * skillOverlap.size() / skillDenominator;
        double confidence = Math.min(1.0, (double) descriptionTokens.size() / MIN_DESCRIPTION_TOKENS_FOR_FULL_CONFIDENCE);
        double skillScore = rawSkillScore * confidence;

        TitleMatch titleMatch = bestTitleMatch(desiredTitles, jobTitleTokens);

        int combined = (int) Math.round((skillScore + titleMatch.score()) / 2.0);

        Set<String> matched = new HashSet<>(skillOverlap);
        matched.addAll(titleMatch.overlap());

        return new MatchResult(combined, matched.stream().sorted().toList());
    }

    // Best coverage of any single desired title's words by the job title, e.g. desired
    // "Java Developer" against job title "Senior Java Developer" -> both words present -> 100.
    private static TitleMatch bestTitleMatch(List<String> desiredTitles, Set<String> jobTitleTokens) {
        double bestScore = 0;
        Set<String> bestOverlap = Set.of();
        for (String desiredTitle : desiredTitles) {
            Set<String> desiredTokens = Tokenizer.normalize(desiredTitle);
            if (desiredTokens.isEmpty()) {
                continue;
            }
            Set<String> overlap = new HashSet<>(desiredTokens);
            overlap.retainAll(jobTitleTokens);
            double coverage = 100.0 * overlap.size() / desiredTokens.size();
            if (coverage > bestScore) {
                bestScore = coverage;
                bestOverlap = overlap;
            }
        }
        return new TitleMatch(bestScore, bestOverlap);
    }

    private record TitleMatch(double score, Set<String> overlap) {
    }
}
