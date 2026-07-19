package org.example.jobfinder.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public final class JobFinderConfig {

    private final Properties properties;

    private JobFinderConfig(Properties properties) {
        this.properties = properties;
    }

    public static JobFinderConfig load(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        return new JobFinderConfig(properties);
    }

    private String require(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required config key: " + key);
        }
        return value;
    }

    private List<String> splitList(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public List<String> searchTitles() {
        return splitList(require("search.titles"));
    }

    public List<String> searchLocations() {
        return splitList(require("search.locations"));
    }

    // Falls back to the general search.locations list if no search.locations.<portal> override
    // is set -- lets a portal with a narrower reach (e.g. an India-only job site) stay scoped to
    // relevant locations while a global portal searches a wider list.
    public List<String> searchLocations(String portalName) {
        String override = properties.getProperty("search.locations." + portalName);
        return override == null ? searchLocations() : splitList(override);
    }

    public int maxResultsPerLocation() {
        return Integer.parseInt(properties.getProperty("search.maxResultsPerLocation", "25"));
    }

    public int matchThresholdPercent() {
        return Integer.parseInt(properties.getProperty("match.thresholdPercent", "70"));
    }

    public List<String> enabledPortals() {
        return splitList(require("portals.enabled"));
    }

    public Path resumePath() {
        return Path.of(require("resume.path"));
    }

    public String chromeProfileDir() {
        return require("chrome.profileDir");
    }

    public String chromeProfileName() {
        return properties.getProperty("chrome.profileName", "Default");
    }

    public String storeCsvPath() {
        return properties.getProperty("store.csvPath", "data/jobfinder.csv");
    }

    // region is "in" or "eu" -- see config/jobfinder.properties for which set applies where
    // (India-based jobs use .in, Europe- and Japan-based jobs use .eu).
    public Path resumePath(String region) {
        return Path.of(require("resume.path." + region));
    }

    public String applyPhone(String region) {
        return require("apply.phone." + region);
    }

    public String applyCurrentLocation(String region) {
        return require("apply.currentLocation." + region);
    }

    public String applyNoticePeriod(String region) {
        return require("apply.noticePeriod." + region);
    }

    // Optional: unset keys return null rather than throwing, since not every Easy Apply
    // screening question this covers (relocation, visa sponsorship, language comfort) shows up
    // on every job -- LinkedInPortal only consults these when the matching question appears.
    public String optionalProperty(String key) {
        return properties.getProperty(key);
    }
}
