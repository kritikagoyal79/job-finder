package org.example.jobfinder.portal.impl;

import org.example.jobfinder.config.JobFinderConfig;
import org.example.jobfinder.portal.browser.ChromeSessionFactory;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;

import java.nio.file.Path;
import java.util.List;

class DiagnoseLinkedInApplyPagesTest {
    @Test
    void diagnose() throws Exception {
        JobFinderConfig config = JobFinderConfig.load(Path.of("config/jobfinder.properties"));
        ChromeDriver driver = ChromeSessionFactory.open(config);
        try {
            driver.get("https://www.linkedin.com/jobs/view/4418226855/apply/");
            Thread.sleep(4000);
            System.out.println("=====DIAG=====");

            for (int page = 1; page <= 6; page++) {
                System.out.println("----- PAGE " + page + " -----");
                System.out.println("URL: " + driver.getCurrentUrl());
                List<WebElement> heading = driver.findElements(By.cssSelector("h3, h2"));
                for (WebElement h : heading) {
                    String t = h.getText().trim();
                    if (!t.isBlank()) System.out.println("HEADING: " + t);
                }
                // dump form field labels
                List<WebElement> labels = driver.findElements(By.cssSelector("label"));
                for (WebElement l : labels) {
                    String t = l.getText().trim();
                    if (!t.isBlank()) System.out.println("LABEL: " + t);
                }
                List<WebElement> legends = driver.findElements(By.cssSelector("legend, span.artdeco-text-input--label"));
                for (WebElement l : legends) {
                    String t = l.getText().trim();
                    if (!t.isBlank()) System.out.println("LEGEND: " + t);
                }

                List<WebElement> submitBtn = driver.findElements(By.xpath("//button[contains(@aria-label,'Submit application')]"));
                if (!submitBtn.isEmpty()) {
                    System.out.println(">>> SUBMIT BUTTON FOUND -- STOPPING HERE, NOT CLICKING <<<");
                    break;
                }
                List<WebElement> nextBtn = driver.findElements(By.xpath("//button[contains(@aria-label,'Continue to next step') or contains(@aria-label,'Review your application')]"));
                if (nextBtn.isEmpty()) {
                    System.out.println(">>> NO NEXT/REVIEW BUTTON FOUND -- STOPPING <<<");
                    break;
                }
                nextBtn.get(0).click();
                Thread.sleep(2000);
            }
            System.out.println("=====ENDDIAG=====");
        } finally {
            driver.quit();
        }
    }
}
