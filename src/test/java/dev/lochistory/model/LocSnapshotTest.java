package dev.lochistory.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocSnapshotTest {
    private final LocSnapshot snapshot = new LocSnapshot(
            new CommitInfo("0123456789", Instant.EPOCH, "Initial"),
            Map.of("src/main.cpp", new FileMetrics(20, 14),
                    "src/lib/math.cpp", new FileMetrics(35, 28),
                    "README.md", new FileMetrics(5, 4)));

    @Test
    void aggregatesProjectAndFolders() {
        assertEquals(60, snapshot.linesFor("", false));
        assertEquals(55, snapshot.linesFor("src", false));
        assertEquals(35, snapshot.linesFor("src/lib", false));
    }

    @Test
    void readsExactFile() {
        assertEquals(20, snapshot.linesFor("src/main.cpp", true));
        assertEquals(0, snapshot.linesFor("missing.cpp", true));
    }

    @Test
    void aggregatesRealLinesSeparately() {
        assertEquals(46, snapshot.linesFor("", false, true));
        assertEquals(42, snapshot.linesFor("src", false, true));
    }
    @Test
    void tokenStatisticsAndMetricComparisons() {
        var tokens = new LocSnapshot(snapshot.commit(), Map.of(
                "src/a", new FileMetrics(Map.of(CountingMetric.LOC, 4, CountingMetric.OPENAI_TOKENS, 10)),
                "src/b", new FileMetrics(Map.of(CountingMetric.LOC, 6, CountingMetric.OPENAI_TOKENS, 20))));
        assertEquals(30, tokens.linesFor("", false, CountingMetric.OPENAI_TOKENS));
        assertEquals(5, tokens.standardDeviation(CountingMetric.OPENAI_TOKENS));
        assertEquals(300, tokens.percentage(CountingMetric.OPENAI_TOKENS, CountingMetric.LOC));
        assertEquals(0, new LocSnapshot(snapshot.commit(), Map.of()).percentage(CountingMetric.RLOC, CountingMetric.LOC));
    }
}
