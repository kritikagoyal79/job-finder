package org.example.jobfinder.store;

import org.example.jobfinder.match.JobListing;
import org.example.jobfinder.match.MatchResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A flat CSV file used as the dedup/state "memory" so the same job is never staged or applied
 * to twice across runs. One row per job, keyed on (portal, external_id). Loaded fully into
 * memory on construction and rewritten in full on every mutation -- appropriate for a
 * single-user, low-volume personal tool where simplicity matters more than write throughput.
 */
public final class JobStore {

    private static final List<String> HEADER = List.of(
            "id", "portal", "external_id", "url", "title", "company", "location",
            "match_score", "matched_keywords", "status", "staged_at", "applied_at", "failure_reason");

    private final Path csvPath;
    private final List<JobRecord> records = new ArrayList<>();
    private int nextId;

    public JobStore(String path) {
        this.csvPath = Path.of(path);
        if (Files.exists(csvPath)) {
            try {
                List<String> lines = Files.readAllLines(csvPath);
                for (int i = 1; i < lines.size(); i++) {
                    if (!lines.get(i).isBlank()) {
                        records.add(parseRow(lines.get(i)));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read job store at " + path, e);
            }
        }
        this.nextId = records.stream().mapToInt(JobRecord::id).max().orElse(0) + 1;
    }

    public boolean isKnown(String portal, String externalId) {
        return records.stream().anyMatch(r -> r.portal().equals(portal) && r.externalId().equals(externalId));
    }

    public void stageOrSkip(JobListing job, MatchResult result, int thresholdPercent) {
        if (isKnown(job.portal(), job.externalId())) {
            return;
        }
        JobStatus status = result.score() >= thresholdPercent ? JobStatus.STAGED : JobStatus.SKIPPED;
        JobRecord record = new JobRecord(
                nextId++,
                job.portal(),
                job.externalId(),
                job.url(),
                job.title(),
                nullToEmpty(job.company()),
                nullToEmpty(job.location()),
                result.score(),
                String.join(",", result.matchedKeywords()),
                status,
                Instant.now().toString(),
                "",
                "");
        records.add(record);
        persist();
    }

    public List<JobRecord> listStaged() {
        return records.stream()
                .filter(r -> r.status() == JobStatus.STAGED)
                .sorted(Comparator.comparingInt(JobRecord::matchScore).reversed())
                .toList();
    }

    public Optional<JobRecord> findById(int id) {
        return records.stream().filter(r -> r.id() == id).findFirst();
    }

    public void markApplied(int id) {
        replace(id, r -> new JobRecord(r.id(), r.portal(), r.externalId(), r.url(), r.title(), r.company(),
                r.location(), r.matchScore(), r.matchedKeywords(), JobStatus.APPLIED, r.stagedAt(),
                Instant.now().toString(), r.failureReason()));
    }

    public void markFailed(int id, String reason) {
        // escape() quotes embedded newlines rather than stripping them, which keeps the write
        // itself valid CSV -- but the constructor reads the file with Files.readAllLines(), which
        // splits on newlines *before* any quote-aware parsing happens, so a multi-line reason
        // (e.g. a raw Selenium exception message, which routinely spans several lines) corrupts
        // every row read after it. Collapsing to one line here is simpler than making the reader
        // quote-aware for a field that only ever holds a short human-readable message.
        String singleLineReason = reason == null ? "" : reason.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
        replace(id, r -> new JobRecord(r.id(), r.portal(), r.externalId(), r.url(), r.title(), r.company(),
                r.location(), r.matchScore(), r.matchedKeywords(), JobStatus.FAILED, r.stagedAt(),
                r.appliedAt(), singleLineReason));
    }

    private void replace(int id, java.util.function.UnaryOperator<JobRecord> updater) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).id() == id) {
                records.set(i, updater.apply(records.get(i)));
                persist();
                return;
            }
        }
    }

    private void persist() {
        try {
            if (csvPath.getParent() != null) {
                Files.createDirectories(csvPath.getParent());
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.join(",", HEADER)).append('\n');
            for (JobRecord r : records) {
                sb.append(toRow(r)).append('\n');
            }
            Files.writeString(csvPath, sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write job store at " + csvPath, e);
        }
    }

    private String toRow(JobRecord r) {
        return String.join(",",
                escape(String.valueOf(r.id())),
                escape(r.portal()),
                escape(r.externalId()),
                escape(r.url()),
                escape(r.title()),
                escape(r.company()),
                escape(r.location()),
                escape(String.valueOf(r.matchScore())),
                escape(r.matchedKeywords()),
                escape(r.status().name()),
                escape(r.stagedAt()),
                escape(r.appliedAt()),
                escape(r.failureReason()));
    }

    private JobRecord parseRow(String line) {
        List<String> f = parseCsvLine(line);
        return new JobRecord(
                Integer.parseInt(f.get(0)),
                f.get(1),
                f.get(2),
                f.get(3),
                f.get(4),
                f.get(5),
                f.get(6),
                Integer.parseInt(f.get(7)),
                f.get(8),
                JobStatus.valueOf(f.get(9)),
                f.get(10),
                f.get(11),
                f.get(12));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String escape(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
