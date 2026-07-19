package org.example.jobfinder.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobFinderConfigTest {

    private Path configFile;

    private JobFinderConfig writeAndLoad(String contents) throws IOException {
        configFile = Files.createTempFile("jobfinder-config-test", ".properties");
        Files.writeString(configFile, contents);
        return JobFinderConfig.load(configFile);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (configFile != null) {
            Files.deleteIfExists(configFile);
        }
    }

    @Test
    void parsesListAndScalarValues() throws IOException {
        JobFinderConfig config = writeAndLoad("""
                search.titles=Software Engineer, Backend Engineer
                search.locations=Bangalore, Remote
                search.maxResultsPerLocation=10
                match.thresholdPercent=75
                portals.enabled=indeed, naukri
                resume.path=resume.pdf
                chrome.profileDir=C:/profile
                chrome.profileName=Work
                store.csvPath=data/test.csv
                """);

        assertEquals(List.of("Software Engineer", "Backend Engineer"), config.searchTitles());
        assertEquals(List.of("Bangalore", "Remote"), config.searchLocations());
        assertEquals(10, config.maxResultsPerLocation());
        assertEquals(75, config.matchThresholdPercent());
        assertEquals(List.of("indeed", "naukri"), config.enabledPortals());
        assertEquals(Path.of("resume.pdf"), config.resumePath());
        assertEquals("C:/profile", config.chromeProfileDir());
        assertEquals("Work", config.chromeProfileName());
        assertEquals("data/test.csv", config.storeCsvPath());
    }

    @Test
    void usesDefaultsWhenOptionalKeysMissing() throws IOException {
        JobFinderConfig config = writeAndLoad("""
                search.titles=Engineer
                search.locations=Remote
                portals.enabled=indeed
                resume.path=resume.pdf
                chrome.profileDir=C:/profile
                """);

        assertEquals(25, config.maxResultsPerLocation());
        assertEquals(70, config.matchThresholdPercent());
        assertEquals("Default", config.chromeProfileName());
        assertEquals("data/jobfinder.csv", config.storeCsvPath());
    }

    @Test
    void missingRequiredKeyThrows() throws IOException {
        JobFinderConfig config = writeAndLoad("search.titles=Engineer\n");

        assertThrows(IllegalStateException.class, config::searchLocations);
    }

    @Test
    void portalSpecificLocationsOverrideFallBackToGeneralList() throws IOException {
        JobFinderConfig config = writeAndLoad("""
                search.titles=Engineer
                search.locations=Bangalore, Remote
                search.locations.linkedin=Bangalore, Remote, Germany, Japan
                portals.enabled=naukri, linkedin
                resume.path=resume.pdf
                chrome.profileDir=C:/profile
                """);

        assertEquals(List.of("Bangalore", "Remote", "Germany", "Japan"), config.searchLocations("linkedin"));
        assertEquals(List.of("Bangalore", "Remote"), config.searchLocations("naukri"));
    }
}
