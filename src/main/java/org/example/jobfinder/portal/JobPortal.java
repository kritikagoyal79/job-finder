package org.example.jobfinder.portal;

import org.example.jobfinder.config.JobFinderConfig;
import org.example.jobfinder.match.JobListing;
import org.openqa.selenium.WebDriver;

import java.util.List;

public interface JobPortal {

    String name();

    List<JobListing> search(SearchCriteria criteria, WebDriver driver);

    ApplyResult apply(JobListing job, WebDriver driver, JobFinderConfig config);
}
