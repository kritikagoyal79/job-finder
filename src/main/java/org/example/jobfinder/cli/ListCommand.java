package org.example.jobfinder.cli;

import org.example.jobfinder.config.JobFinderConfig;
import org.example.jobfinder.store.JobRecord;
import org.example.jobfinder.store.JobStore;

import java.util.List;

public final class ListCommand {

    public static void run(JobFinderConfig config) {
        JobStore store = new JobStore(config.storeCsvPath());
        List<JobRecord> staged = store.listStaged();
        if (staged.isEmpty()) {
            System.out.println("No staged jobs. Run `search` first.");
            return;
        }
        for (JobRecord job : staged) {
            System.out.printf("[%d] %d%% %s @ %s (%s) - %s%n",
                    job.id(), job.matchScore(), job.title(), job.company(), job.portal(), job.url());
            System.out.println("      matched: " + job.matchedKeywords());
        }
        System.out.println();
        System.out.println("Approve specific jobs with: apply --ids <id1,id2,...>");
    }
}
