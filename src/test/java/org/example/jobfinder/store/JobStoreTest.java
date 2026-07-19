package org.example.jobfinder.store;

import org.example.jobfinder.match.JobListing;
import org.example.jobfinder.match.MatchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobStoreTest {

    private Path csvFile;
    private JobStore store;

    @BeforeEach
    void setUp() throws IOException {
        csvFile = Files.createTempFile("jobfinder-test", ".csv");
        Files.deleteIfExists(csvFile); // JobStore should create it itself on first write
        store = new JobStore(csvFile.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(csvFile);
    }

    private JobListing sampleJob() {
        return new JobListing("indeed", "abc123", "Java Engineer", "Acme", "Remote",
                "http://example.com/job/abc123", "Java Spring");
    }

    private JobListing jobWithCommasInFields() {
        return new JobListing("indeed", "xyz999", "Engineer, Backend", "Acme, Inc.", "Remote, Hybrid",
                "http://example.com/job/xyz999", "desc");
    }

    @Test
    void unknownJobIsNotKnown() {
        assertFalse(store.isKnown("indeed", "abc123"));
    }

    @Test
    void stagingAJobAboveThresholdMarksItStaged() {
        store.stageOrSkip(sampleJob(), new MatchResult(85, List.of("java", "spring")), 70);

        assertTrue(store.isKnown("indeed", "abc123"));
        List<JobRecord> staged = store.listStaged();
        assertEquals(1, staged.size());
        assertEquals(JobStatus.STAGED, staged.get(0).status());
        assertEquals(85, staged.get(0).matchScore());
    }

    @Test
    void stagingAJobBelowThresholdMarksItSkippedNotStaged() {
        store.stageOrSkip(sampleJob(), new MatchResult(40, List.of("java")), 70);

        assertTrue(store.isKnown("indeed", "abc123"));
        assertTrue(store.listStaged().isEmpty());
    }

    @Test
    void duplicateStageOrSkipDoesNotCreateDuplicateRow() {
        store.stageOrSkip(sampleJob(), new MatchResult(85, List.of("java")), 70);
        store.stageOrSkip(sampleJob(), new MatchResult(85, List.of("java")), 70);

        assertEquals(1, store.listStaged().size());
    }

    @Test
    void markAppliedTransitionsStatus() {
        store.stageOrSkip(sampleJob(), new MatchResult(85, List.of("java")), 70);
        int id = store.listStaged().get(0).id();

        store.markApplied(id);

        Optional<JobRecord> record = store.findById(id);
        assertTrue(record.isPresent());
        assertEquals(JobStatus.APPLIED, record.get().status());
        assertTrue(store.listStaged().isEmpty());
    }

    @Test
    void markFailedRecordsReason() {
        store.stageOrSkip(sampleJob(), new MatchResult(85, List.of("java")), 70);
        int id = store.listStaged().get(0).id();

        store.markFailed(id, "selector not found");

        Optional<JobRecord> record = store.findById(id);
        assertTrue(record.isPresent());
        assertEquals(JobStatus.FAILED, record.get().status());
        assertEquals("selector not found", record.get().failureReason());
    }

    @Test
    void fieldsContainingCommasSurviveReload() {
        store.stageOrSkip(jobWithCommasInFields(), new MatchResult(90, List.of("java", "spring")), 70);

        JobStore reopened = new JobStore(csvFile.toString());
        List<JobRecord> staged = reopened.listStaged();

        assertEquals(1, staged.size());
        assertEquals("Engineer, Backend", staged.get(0).title());
        assertEquals("Acme, Inc.", staged.get(0).company());
        assertEquals("java,spring", staged.get(0).matchedKeywords());
    }

    @Test
    void dedupPersistsAcrossReopeningTheSameFile() {
        store.stageOrSkip(sampleJob(), new MatchResult(85, List.of("java")), 70);

        JobStore reopened = new JobStore(csvFile.toString());

        assertTrue(reopened.isKnown("indeed", "abc123"));
        // Re-running stageOrSkip on a fresh instance for the same job must not duplicate it.
        reopened.stageOrSkip(sampleJob(), new MatchResult(85, List.of("java")), 70);
        assertEquals(1, reopened.listStaged().size());
    }

    @Test
    void idsIncrementAcrossReopeningTheSameFile() {
        store.stageOrSkip(sampleJob(), new MatchResult(85, List.of("java")), 70);

        JobStore reopened = new JobStore(csvFile.toString());
        reopened.stageOrSkip(jobWithCommasInFields(), new MatchResult(85, List.of("java")), 70);

        List<JobRecord> staged = reopened.listStaged();
        assertEquals(2, staged.size());
        assertTrue(staged.stream().anyMatch(r -> r.id() == 2));
    }
}
