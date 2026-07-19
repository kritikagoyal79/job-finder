package org.example.jobfinder.portal.impl;

import org.example.jobfinder.config.JobFinderConfig;
import org.example.jobfinder.match.JobListing;
import org.example.jobfinder.portal.ApplyResult;
import org.example.jobfinder.portal.JobPortal;
import org.example.jobfinder.portal.SearchCriteria;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * NOT enabled by default (see config/jobfinder.properties.example). Instahyre is largely a
 * recruiter-curated matching model rather than a traditional keyword-search UX: candidates get
 * "opportunities" surfaced to them on their logged-in dashboard rather than searching openly.
 * This implementation reads whatever opportunity cards are already on the candidate dashboard
 * (search criteria titles/locations are not applied server-side here) -- it needs to be reworked
 * once you've inspected your actual dashboard layout while logged in.
 */
public final class InstahyrePortal implements JobPortal {

    @Override
    public String name() {
        return "instahyre";
    }

    @Override
    public List<JobListing> search(SearchCriteria criteria, WebDriver driver) {
        List<JobListing> listings = new ArrayList<>();
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

        driver.get("https://www.instahyre.com/candidate/opportunities/");
        wait.until(d -> !d.findElements(By.cssSelector("div.opportunity-card")).isEmpty()
                || !d.findElements(By.cssSelector("div.no-opportunities")).isEmpty());

        List<WebElement> cards = driver.findElements(By.cssSelector("div.opportunity-card"));
        for (WebElement card : cards) {
            if (listings.size() >= criteria.maxResultsPerLocation()) {
                break;
            }
            try {
                WebElement titleLink = card.findElement(By.cssSelector("a.job-title"));
                String jobUrl = titleLink.getAttribute("href");
                String externalId = jobUrl.substring(jobUrl.lastIndexOf('/') + 1);
                String jobTitle = titleLink.getText();
                String company = safeText(card, By.cssSelector("span.company-name"));
                String description = safeText(card, By.cssSelector("div.job-description"));

                listings.add(new JobListing("instahyre", externalId, jobTitle, company, "", jobUrl, description));
            } catch (Exception e) {
                // One malformed card shouldn't abort the whole search; skip it.
            }
        }
        return listings;
    }

    @Override
    public ApplyResult apply(JobListing job, WebDriver driver, JobFinderConfig config) {
        driver.get(job.url());
        return new ApplyResult(false, "Instahyre apply flow not yet implemented/calibrated against the live site.");
    }

    private String safeText(WebElement parent, By selector) {
        List<WebElement> elements = parent.findElements(selector);
        return elements.isEmpty() ? "" : elements.get(0).getText();
    }
}
