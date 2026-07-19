package org.example.jobfinder.cli;

import org.example.jobfinder.config.JobFinderConfig;
import org.example.jobfinder.match.JobListing;
import org.example.jobfinder.match.MatchResult;
import org.example.jobfinder.match.MatchScorer;
import org.example.jobfinder.portal.JobPortal;
import org.example.jobfinder.portal.PortalRegistry;
import org.example.jobfinder.portal.SearchCriteria;
import org.example.jobfinder.portal.browser.ChromeSessionFactory;
import org.example.jobfinder.resume.ResumeParser;
import org.example.jobfinder.resume.ResumeProfile;
import org.example.jobfinder.store.JobStore;
import org.openqa.selenium.chrome.ChromeDriver;

import java.util.List;

public final class SearchCommand {

    public static void run(JobFinderConfig config) throws Exception {
        ResumeProfile resume = ResumeParser.parse(config.resumePath());
        List<JobPortal> portals = PortalRegistry.resolve(config.enabledPortals());
        int threshold = config.matchThresholdPercent();

        JobStore store = new JobStore(config.storeCsvPath());
        ChromeDriver driver = ChromeSessionFactory.open(config);
        try {
            for (JobPortal portal : portals) {
                System.out.println("Searching " + portal.name() + "...");
                SearchCriteria criteria = new SearchCriteria(
                        config.searchTitles(), config.searchLocations(portal.name()), config.maxResultsPerLocation());
                List<JobListing> results = portal.search(criteria, driver);

                int staged = 0;
                int skipped = 0;
                int alreadyKnown = 0;
                for (JobListing job : results) {
                    if (store.isKnown(job.portal(), job.externalId())) {
                        alreadyKnown++;
                        continue;
                    }
                    MatchResult match = MatchScorer.score(resume, job, config.searchTitles());
                    store.stageOrSkip(job, match, threshold);
                    if (match.score() >= threshold) {
                        staged++;
                    } else {
                        skipped++;
                    }
                }
                System.out.printf("  %s: %d found, %d staged, %d below threshold, %d already seen%n",
                        portal.name(), results.size(), staged, skipped, alreadyKnown);
            }
        } finally {
            driver.quit();
        }
        System.out.println("Done. Run `list` to review staged jobs before applying.");
    }
}
