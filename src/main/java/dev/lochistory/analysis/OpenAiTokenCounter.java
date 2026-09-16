package dev.lochistory.analysis;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import dev.lochistory.model.CountingMetric;

public final class OpenAiTokenCounter implements TokenCounter {
    private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
    @Override public CountingMetric metric() { return CountingMetric.OPENAI_TOKENS; }
    @Override public int count(String text) { return encoding.countTokensOrdinary(text); }
}
