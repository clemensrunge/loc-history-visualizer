package dev.lochistory.model;

import java.time.Instant;

public record CommitInfo(String hash, Instant time, String subject) {
    public String shortHash() {
        return hash.length() <= 8 ? hash : hash.substring(0, 8);
    }
}
