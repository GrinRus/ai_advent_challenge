package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatProvidersResponse(String defaultProvider, List<Provider> providers) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Provider(
      String id,
      String displayName,
      String type,
      String defaultModel,
      Double temperature,
      Double topP,
      Integer maxTokens,
      List<Model> models) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Model(
      String id,
      String displayName,
      String tier,
      BigDecimal inputPer1KTokens,
      BigDecimal outputPer1KTokens,
      Integer contextWindow,
      Integer maxOutputTokens,
      Boolean syncEnabled,
      Boolean streamingEnabled,
      Boolean structuredEnabled,
      String currency) {}
}
