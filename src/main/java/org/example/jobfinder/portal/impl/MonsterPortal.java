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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NOT enabled by default -- Monster has aggressive anti-bot detection similar to LinkedIn.
 * Treat this as experimental: run `search` manually first and watch the browser window for
 * challenge pages.
 */
public final class MonsterPortal implements JobPortal {

    @Override
    public String name() {
        return "monster";
    }

    @Override
    public List<JobListing> search(SearchCriteria criteria, WebDriver driver) {
        List<JobListing> listings = new ArrayList<>();
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        Map<String, Integer> countPerLocation = new HashMap<>();

        for (String title : criteria.titles()) {
            for (String location : criteria.locations()) {
                String url = "https://www.monster.com/jobs/search?q="
                        + URLEncoder.encode(title, StandardCharsets.UTF_8)
                        + "&where=" + URLEncoder.encode(location, StandardCharsets.UTF_8);
                driver.get(url);
                wait.until(d -> !d.findElements(By.cssSelector("section.card-content")).isEmpty()
                        || !d.findElements(By.cssSelector("div.no-results")).isEmpty());

                List<WebElement> cards = driver.findElements(By.cssSelector("section.card-content"));
                for (WebElement card : cards) {
                    if (countPerLocation.getOrDefault(location, 0) >= criteria.maxResultsPerLocation()) {
                        break;
                    }
                    try {
                        WebElement titleLink = card.findElement(By.cssSelector("a.title"));
                        String jobUrl = titleLink.getAttribute("href");
                        String externalId = jobUrl.substring(jobUrl.lastIndexOf('/') + 1);
                        String jobTitle = titleLink.getText();
                        String company = safeText(card, By.cssSelector("div.company span"));
                        String jobLocation = safeText(card, By.cssSelector("div.location span"));

                        listings.add(new JobListing("monster", externalId, jobTitle, company, jobLocation, jobUrl, jobTitle));
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
        return new ApplyResult(false, "Monster apply flow not yet implemented/calibrated against the live site.");
    }

    private String safeText(WebElement parent, By selector) {
        List<WebElement> elements = parent.findElements(selector);
        return elements.isEmpty() ? "" : elements.get(0).getText();
    }
}
