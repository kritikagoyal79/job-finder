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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * NOT enabled by default (see config/jobfinder.properties.example). Not yet verified against
 * the live site -- these selectors are a best-effort starting point. Run `search` manually,
 * watch the browser, and adjust the By.cssSelector() values below against Wellfound's actual
 * DOM before relying on this portal.
 */
public final class WellfoundPortal implements JobPortal {

    @Override
    public String name() {
        return "wellfound";
    }

    @Override
    public List<JobListing> search(SearchCriteria criteria, WebDriver driver) {
        List<JobListing> listings = new ArrayList<>();
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

        for (String title : criteria.titles()) {
            String url = "https://wellfound.com/jobs?query=" + URLEncoder.encode(title, StandardCharsets.UTF_8);
            driver.get(url);
            wait.until(d -> !d.findElements(By.cssSelector("div[data-test='StartupResult']")).isEmpty()
                    || !d.findElements(By.cssSelector("div.no-results")).isEmpty());

            List<WebElement> cards = driver.findElements(By.cssSelector("a[data-test='JobSearchResult']"));
            for (WebElement card : cards) {
                if (listings.size() >= criteria.maxResultsPerLocation()) {
                    break;
                }
                try {
                    String jobUrl = card.getAttribute("href");
                    String externalId = jobUrl.substring(jobUrl.lastIndexOf('/') + 1);
                    String jobTitle = safeText(card, By.cssSelector("[data-test='JobSearchResult-title']"));
                    String company = safeText(card, By.cssSelector("[data-test='JobSearchResult-company']"));
                    String jobLocation = safeText(card, By.cssSelector("[data-test='JobSearchResult-location']"));

                    listings.add(new JobListing("wellfound", externalId, jobTitle, company, jobLocation, jobUrl, jobTitle));
                } catch (Exception e) {
                    // One malformed card shouldn't abort the whole search; skip it.
                }
            }
        }
        return listings;
    }

    @Override
    public ApplyResult apply(JobListing job, WebDriver driver, JobFinderConfig config) {
        driver.get(job.url());
        return new ApplyResult(false, "Wellfound apply flow not yet implemented/calibrated against the live site.");
    }

    private String safeText(WebElement parent, By selector) {
        List<WebElement> elements = parent.findElements(selector);
        return elements.isEmpty() ? "" : elements.get(0).getText();
    }
}
