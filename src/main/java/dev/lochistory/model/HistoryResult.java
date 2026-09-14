package dev.lochistory.model;

import java.util.List;

public record HistoryResult(String branch, List<LocSnapshot> snapshots) {
    public HistoryResult {
        snapshots = List.copyOf(snapshots);
    }
}
