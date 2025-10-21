package com.aiadvent.backend.chat.token;

import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.ModelType;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

public class DefaultTokenUsageEstimator implements TokenUsageEstimator {

  private static final Logger log = LoggerFactory.getLogger(DefaultTokenUsageEstimator.class);
  private static final String PROMPT_SEGMENT = "prompt";
  private static final String COMPLETION_SEGMENT = "completion";

  private final EncodingRegistry encodingRegistry;
  private final TokenUsageCache cache;
  private final String defaultTokenizer;
  private final String cachePrefix;

  public DefaultTokenUsageEstimator(
      EncodingRegistry encodingRegistry,
      TokenUsageCache cache,
      String defaultTokenizer,
      String cachePrefix) {
    this.encodingRegistry = encodingRegistry;
    this.cache = cache != null ? cache : TokenUsageCache.noOp();
    this.defaultTokenizer = StringUtils.hasText(defaultTokenizer) ? defaultTokenizer : "cl100k_base";
    this.cachePrefix = StringUtils.hasText(cachePrefix) ? cachePrefix : "chat:usage";
  }

  @Override
  public Estimate estimate(EstimateRequest request) {
    if (request == null) {
      return new Estimate(0, 0, 0, false, false);
    }

    String tokenizerName =
        StringUtils.hasText(request.tokenizer()) ? request.tokenizer().trim() : defaultTokenizer;
    Encoding encoding = resolveEncoding(tokenizerName);

    TokenComputation promptComputation =
        computeTokenCount(encoding, tokenizerName, PROMPT_SEGMENT, request.prompt());
    TokenComputation completionComputation =
        computeTokenCount(encoding, tokenizerName, COMPLETION_SEGMENT, request.completion());

    int promptTokens = promptComputation.tokens();
    int completionTokens = completionComputation.tokens();
    int totalTokens = promptTokens + completionTokens;

    if (log.isDebugEnabled()) {
      log.debug(
          "Estimated tokens for provider='{}', model='{}', tokenizer='{}': prompt={}, completion={}, total={}",
          request.providerId(),
          request.modelId(),
          tokenizerName,
          promptTokens,
          completionTokens,
          totalTokens);
    }

    return new Estimate(
        promptTokens,
        completionTokens,
        totalTokens,
        promptComputation.cacheHit(),
        completionComputation.cacheHit());
  }

  private TokenComputation computeTokenCount(
      Encoding encoding, String tokenizerName, String segment, String text) {
    if (!StringUtils.hasText(text)) {
      return TokenComputation.EMPTY;
    }

    String cacheKey = buildCacheKey(tokenizerName, segment, text);
    Integer cachedValue = cache.get(cacheKey);
    if (cachedValue != null) {
      return TokenComputation.fromCache(cachedValue);
    }

    int tokens;
    try {
      tokens = encoding.countTokensOrdinary(text);
    } catch (RuntimeException ordinaryFailure) {
      log.debug("Falling back to strict token counting due to {}", ordinaryFailure.getMessage());
      try {
        tokens = encoding.countTokens(text);
      } catch (RuntimeException strictFailure) {
        log.warn("Failed to estimate tokens for {} segment via tokenizer {}", segment, tokenizerName, strictFailure);
        return TokenComputation.EMPTY;
      }
    }
    cache.put(cacheKey, tokens);
    return TokenComputation.computed(tokens);
  }

  private String buildCacheKey(String tokenizerName, String segment, String text) {
    String digest =
        DigestUtils.md5DigestAsHex(text.getBytes(StandardCharsets.UTF_8));
    return cachePrefix + ":" + tokenizerName + ":" + segment + ":" + digest;
  }

  private Encoding resolveEncoding(String tokenizerName) {
    return encodingRegistry
        .getEncodingForModel(tokenizerName)
        .orElseGet(
            () ->
                ModelType.fromName(tokenizerName)
                    .map(encodingRegistry::getEncodingForModel)
                    .orElseGet(() -> resolveEncodingByTypeOrName(tokenizerName)));
  }

  private Encoding resolveEncodingByTypeOrName(String tokenizerName) {
    return EncodingType.fromName(tokenizerName)
        .map(encodingRegistry::getEncoding)
        .orElseGet(
            () ->
                encodingRegistry
                    .getEncoding(tokenizerName)
                    .orElseThrow(
                        () ->
                            new IllegalArgumentException(
                                "Unknown tokenizer '" + tokenizerName + "', configure a supported tokenizer")));
  }

  private record TokenComputation(int tokens, boolean cacheHit) {
    private static final TokenComputation EMPTY = new TokenComputation(0, false);

    private static TokenComputation fromCache(int tokens) {
      return new TokenComputation(tokens, true);
    }

    private static TokenComputation computed(int tokens) {
      return new TokenComputation(tokens, false);
    }
  }
}
