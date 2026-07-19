package org.example.jobfinder.store;

public record JobRecord(
        int id,
        String portal,
        String externalId,
        String url,
        String title,
        String company,
        String location,
        int matchScore,
        String matchedKeywords,
        JobStatus status,
        String stagedAt,
        String appliedAt,
        String failureReason
) {
}
