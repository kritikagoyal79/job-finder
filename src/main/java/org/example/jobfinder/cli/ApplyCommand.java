package org.example.jobfinder.cli;

import org.example.jobfinder.config.JobFinderConfig;
import org.example.jobfinder.match.JobListing;
import org.example.jobfinder.portal.ApplyResult;
import org.example.jobfinder.portal.JobPortal;
import org.example.jobfinder.portal.PortalRegistry;
import org.example.jobfinder.portal.browser.ChromeSessionFactory;
import org.example.jobfinder.store.JobRecord;
import org.example.jobfinder.store.JobStore;
import org.openqa.selenium.chrome.ChromeDriver;

import java.util.List;
import java.util.Optional;

public final class ApplyCommand {

    public static void run(JobFinderConfig config, List<Integer> ids) throws Exception {
        if (ids.isEmpty()) {
            System.out.println("No job IDs given. Usage: apply --ids <id1,id2,...>");
            return;
        }

        JobStore store = new JobStore(config.storeCsvPath());
        ChromeDriver driver = ChromeSessionFactory.open(config);
        try {
            for (int id : ids) {
                Optional<JobRecord> maybeRecord = store.findById(id);
                if (maybeRecord.isEmpty()) {
                    System.out.println("No staged job with id " + id + "; skipping.");
                    continue;
                }
                JobRecord record = maybeRecord.get();
                JobPortal portal = PortalRegistry.resolveOne(record.portal());
                JobListing job = new JobListing(record.portal(), record.externalId(), record.title(),
                        record.company(), record.location(), record.url(), "");

                System.out.println("Applying to [" + id + "] " + record.title() + " @ " + record.company() + "...");
                ApplyResult result = portal.apply(job, driver, config);
                if (result.success()) {
                    store.markApplied(id);
                    System.out.println("  Applied.");
                } else {
                    store.markFailed(id, result.message());
                    System.out.println("  Not submitted: " + result.message());
                }
            }
        } finally {
            driver.quit();
        }
    }
}
