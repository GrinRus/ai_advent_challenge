package com.aiadvent.backend.chat.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingResult;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import com.knuddels.jtokkit.api.ModelType;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultTokenUsageEstimatorTest {

  @Test
  void estimatesTokensWithDefaultTokenizer() {
    EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);

    TokenUsageEstimator estimator =
        new DefaultTokenUsageEstimator(
            registry, TokenUsageCache.noOp(), "cl100k_base", "chat:usage");

    String prompt = "Hello Spring AI!";
    String completion = "Привет, мир.";

    TokenUsageEstimator.Estimate estimate =
        estimator.estimate(new TokenUsageEstimator.EstimateRequest("openai", "gpt-4o", null, prompt, completion));

    assertThat(estimate.promptTokens()).isEqualTo(encoding.countTokensOrdinary(prompt));
    assertThat(estimate.completionTokens()).isEqualTo(encoding.countTokensOrdinary(completion));
    assertThat(estimate.totalTokens())
        .isEqualTo(estimate.promptTokens() + estimate.completionTokens());
    assertThat(estimate.promptCached()).isFalse();
    assertThat(estimate.completionCached()).isFalse();
  }

  @Test
  void reusesCacheOnRepeatedRequests() {
    CountingEncoding encoding = new CountingEncoding();
    InMemoryEncodingRegistry registry = new InMemoryEncodingRegistry(encoding);
    InMemoryTokenUsageCache cache = new InMemoryTokenUsageCache();

    TokenUsageEstimator estimator =
        new DefaultTokenUsageEstimator(registry, cache, "custom", "chat:usage");

    TokenUsageEstimator.EstimateRequest request =
        new TokenUsageEstimator.EstimateRequest("stub", "model", "custom", "prompt text", "reply text");

    TokenUsageEstimator.Estimate first = estimator.estimate(request);
    TokenUsageEstimator.Estimate second = estimator.estimate(request);

    assertThat(first.promptCached()).isFalse();
    assertThat(first.completionCached()).isFalse();
    assertThat(second.promptCached()).isTrue();
    assertThat(second.completionCached()).isTrue();
    assertThat(encoding.ordinaryCalls()).isEqualTo(2);
    assertThat(encoding.strictCalls()).isZero();
  }

  @Test
  void fallsBackToStrictCountingWhenOrdinaryFails() {
    FailingEncoding encoding = new FailingEncoding();
    InMemoryEncodingRegistry registry = new InMemoryEncodingRegistry(encoding);

    TokenUsageEstimator estimator =
        new DefaultTokenUsageEstimator(registry, TokenUsageCache.noOp(), "custom", "chat:usage");

    TokenUsageEstimator.Estimate estimate =
        estimator.estimate(
            new TokenUsageEstimator.EstimateRequest("stub", "model", "custom", "text", "more text"));

    assertThat(estimate.promptTokens()).isEqualTo("text".length());
    assertThat(estimate.completionTokens()).isEqualTo("more text".length());
    assertThat(encoding.strictCalls()).isEqualTo(2);
  }

  @Test
  void throwsExceptionForUnknownTokenizer() {
    TokenUsageEstimator estimator =
        new DefaultTokenUsageEstimator(
            new EmptyEncodingRegistry(), TokenUsageCache.noOp(), "unknown", "chat:usage");

    assertThatThrownBy(
            () ->
                estimator.estimate(
                    new TokenUsageEstimator.EstimateRequest("stub", "model", "mystery", "question", "answer")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown tokenizer 'mystery'");
  }

  private static final class InMemoryTokenUsageCache implements TokenUsageCache {
    private final Map<String, Integer> values = new HashMap<>();

    @Override
    public Integer get(String key) {
      return values.get(key);
    }

    @Override
    public void put(String key, int value) {
      values.put(key, value);
    }
  }

  private static final class InMemoryEncodingRegistry implements EncodingRegistry {
    private final Encoding encoding;

    private InMemoryEncodingRegistry(Encoding encoding) {
      this.encoding = encoding;
    }

    @Override
    public Optional<Encoding> getEncoding(String encodingName) {
      return Optional.of(encoding);
    }

    @Override
    public Encoding getEncoding(EncodingType encodingType) {
      return encoding;
    }

    @Override
    public Optional<Encoding> getEncodingForModel(String modelName) {
      return Optional.of(encoding);
    }

    @Override
    public Encoding getEncodingForModel(ModelType modelType) {
      return encoding;
    }

    @Override
    public EncodingRegistry registerGptBytePairEncoding(com.knuddels.jtokkit.api.GptBytePairEncodingParams parameters) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EncodingRegistry registerCustomEncoding(Encoding encoding) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class EmptyEncodingRegistry implements EncodingRegistry {

    @Override
    public Optional<Encoding> getEncoding(String encodingName) {
      return Optional.empty();
    }

    @Override
    public Encoding getEncoding(EncodingType encodingType) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Encoding> getEncodingForModel(String modelName) {
      return Optional.empty();
    }

    @Override
    public Encoding getEncodingForModel(ModelType modelType) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EncodingRegistry registerGptBytePairEncoding(com.knuddels.jtokkit.api.GptBytePairEncodingParams parameters) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EncodingRegistry registerCustomEncoding(Encoding encoding) {
      throw new UnsupportedOperationException();
    }
  }

  private static class CountingEncoding implements Encoding {
    private final AtomicInteger ordinaryCalls = new AtomicInteger();
    private final AtomicInteger strictCalls = new AtomicInteger();

    @Override
    public IntArrayList encode(String text) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EncodingResult encode(String text, int maxTokens) {
      throw new UnsupportedOperationException();
    }

    @Override
    public IntArrayList encodeOrdinary(String text) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EncodingResult encodeOrdinary(String text, int maxTokens) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int countTokens(String text) {
      strictCalls.incrementAndGet();
      return text.length();
    }

    @Override
    public int countTokensOrdinary(String text) {
      ordinaryCalls.incrementAndGet();
      return text.length();
    }

    @Override
    public String decode(IntArrayList tokens) {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] decodeBytes(IntArrayList tokens) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getName() {
      return "test-encoding";
    }

    int ordinaryCalls() {
      return ordinaryCalls.get();
    }

    int strictCalls() {
      return strictCalls.get();
    }
  }

  private static final class FailingEncoding extends CountingEncoding {
    @Override
    public int countTokensOrdinary(String text) {
      throw new UnsupportedOperationException("ordinary tokenization not available");
    }
  }
}
