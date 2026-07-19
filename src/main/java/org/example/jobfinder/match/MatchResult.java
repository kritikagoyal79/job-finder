package org.example.jobfinder.match;

import java.util.List;

public record MatchResult(int score, List<String> matchedKeywords) {
}
