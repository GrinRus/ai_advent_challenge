package com.aiadvent.backend.chat.token;

import org.springframework.util.StringUtils;

public interface TokenUsageEstimator {

  Estimate estimate(EstimateRequest request);

  record EstimateRequest(
      String providerId, String modelId, String tokenizer, String prompt, String completion) {

    public EstimateRequest {
      tokenizer = StringUtils.hasText(tokenizer) ? tokenizer : null;
      prompt = StringUtils.hasText(prompt) ? prompt : null;
      completion = StringUtils.hasText(completion) ? completion : null;
    }

    public EstimateRequest withTokenizer(String newTokenizer) {
      return new EstimateRequest(providerId, modelId, newTokenizer, prompt, completion);
    }
  }

  record Estimate(
      int promptTokens, int completionTokens, int totalTokens, boolean promptCached, boolean completionCached) {

    public Estimate {
      totalTokens = Math.max(totalTokens, promptTokens + completionTokens);
    }

    public boolean hasUsage() {
      return totalTokens > 0;
    }
  }
}

