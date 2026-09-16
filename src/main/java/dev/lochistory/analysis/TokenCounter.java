package dev.lochistory.analysis;

import dev.lochistory.model.CountingMetric;

/** Provider-specific tokenization of complete source text, without chat request overhead. */
public interface TokenCounter {
    CountingMetric metric();
    int count(String text);
}
