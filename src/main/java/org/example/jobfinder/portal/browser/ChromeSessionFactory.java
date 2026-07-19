package org.example.jobfinder.portal.browser;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.example.jobfinder.config.JobFinderConfig;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

public final class ChromeSessionFactory {

    private ChromeSessionFactory() {
    }

    public static ChromeDriver open(JobFinderConfig config) {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--user-data-dir=" + config.chromeProfileDir());
        options.addArguments("--profile-directory=" + config.chromeProfileName());
        options.addArguments("--start-maximized");

        return new ChromeDriver(options);
    }
}
