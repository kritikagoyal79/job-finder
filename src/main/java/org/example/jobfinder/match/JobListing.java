package org.example.jobfinder.match;

public record JobListing(
        String portal,
        String externalId,
        String title,
        String company,
        String location,
        String url,
        String descriptionText
) {
}
