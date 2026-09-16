package dev.lochistory.model;

public enum CountingMetric {
    LOC("LOC"), RLOC("RLOC"), OPENAI_TOKENS("OpenAI tokens"), CLAUDE_TOKENS("Claude 4.8+ tokens");

    private final String label;
    CountingMetric(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
