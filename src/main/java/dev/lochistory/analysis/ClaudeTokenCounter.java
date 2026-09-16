package dev.lochistory.analysis;

import dev.ctok.Ctok;
import dev.lochistory.model.CountingMetric;

/** Reconstructed Claude 4.8+ content counts, including Fable, without fixed message overhead. */
public final class ClaudeTokenCounter implements TokenCounter {
    private static final Ctok TOKENIZER = Ctok.forVersion("4.8");
    @Override public CountingMetric metric() { return CountingMetric.CLAUDE_TOKENS; }
    @Override public int count(String text) { return TOKENIZER.contentTokenCount(text); }
}
