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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort selectors based on Naukri's public (no-login-required) search results page.
 * Naukri changes its DOM periodically -- if search() returns zero results, inspect the live
 * page and adjust the By.cssSelector() values below.
 */
public final class NaukriPortal implements JobPortal {

    private static final Pattern JOB_ID_PATTERN = Pattern.compile("-(\\d+)(?:\\?|$)");

    @Override
    public String name() {
        return "naukri";
    }

    @Override
    public List<JobListing> search(SearchCriteria criteria, WebDriver driver) {
        List<JobListing> listings = new ArrayList<>();
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        Map<String, Integer> countPerLocation = new HashMap<>();

        for (String title : criteria.titles()) {
            for (String location : criteria.locations()) {
                String slugTitle = title.trim().toLowerCase().replaceAll("\\s+", "-");
                String slugLocation = location.trim().toLowerCase().replaceAll("\\s+", "-");
                String url = "https://www.naukri.com/" + slugTitle + "-jobs-in-" + slugLocation;
                driver.get(url);
                wait.until(d -> !d.findElements(By.cssSelector("div.cust-job-tuple")).isEmpty()
                        || !d.findElements(By.cssSelector("div.no-results")).isEmpty());

                List<WebElement> cards = driver.findElements(By.cssSelector("div.cust-job-tuple"));
                for (WebElement card : cards) {
                    if (countPerLocation.getOrDefault(location, 0) >= criteria.maxResultsPerLocation()) {
                        break;
                    }
                    try {
                        WebElement titleLink = card.findElement(By.cssSelector("a.title"));
                        String jobUrl = titleLink.getAttribute("href");
                        String externalId = extractExternalId(jobUrl);
                        String jobTitle = titleLink.getText();
                        String company = safeText(card, By.cssSelector("a.comp-name"));
                        String jobLocation = safeText(card, By.cssSelector("span.locWdth"));
                        String description = safeText(card, By.cssSelector("span.job-desc"));

                        listings.add(new JobListing("naukri", externalId, jobTitle, company, jobLocation, jobUrl, description));
                        countPerLocation.merge(location, 1, Integer::sum);
                    } catch (Exception e) {
                        // One malformed card shouldn't abort the whole search; skip it.
                    }
                }
            }
        }
        return listings;
    }

    @Override
    public ApplyResult apply(JobListing job, WebDriver driver, JobFinderConfig config) {
        driver.get(job.url());
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
            WebElement applyButton = wait.until(d -> {
                List<WebElement> buttons = d.findElements(By.id("apply-button"));
                return buttons.isEmpty() ? null : buttons.get(0);
            });
            applyButton.click();
            return new ApplyResult(false,
                    "Clicked Naukri Apply button; confirm submission requires manual completion / selector calibration.");
        } catch (Exception e) {
            return new ApplyResult(false, "Failed to locate/click Naukri apply button: " + e.getMessage());
        }
    }

    private String extractExternalId(String jobUrl) {
        Matcher matcher = JOB_ID_PATTERN.matcher(jobUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return jobUrl;
    }

    private String safeText(WebElement parent, By selector) {
        List<WebElement> elements = parent.findElements(selector);
        return elements.isEmpty() ? "" : elements.get(0).getText();
    }
}
