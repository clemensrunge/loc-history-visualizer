package dev.lochistory.analysis;

public record GitIgnoreRule(String source, String pattern) {
    public String displayText() {
        return source + ": " + pattern;
    }
}
