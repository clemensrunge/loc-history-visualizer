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
}
