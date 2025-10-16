package com.aiadvent.backend.chat.config;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.chat")
@Validated
public class ChatProvidersProperties {

  /**
   * Identifier of the provider used when the client does not explicitly request one.
   */
  @NotBlank private String defaultProvider;

  private Map<String, Provider> providers = new LinkedHashMap<>();

  public String getDefaultProvider() {
    return defaultProvider;
  }

  public void setDefaultProvider(String defaultProvider) {
    this.defaultProvider = defaultProvider;
  }

  public Map<String, Provider> getProviders() {
    return providers;
  }

  public void setProviders(Map<String, Provider> providers) {
    this.providers = providers;
  }

  public static class Provider {

    private ChatProviderType type;
    private String displayName;
    private String baseUrl;
    private String apiKey;
    private String completionsPath;
    private String embeddingsPath;
    private Duration timeout = Duration.ofSeconds(30);
    private Integer maxTokens;
    private Double temperature;
    private Double topP;
    private String defaultModel;
    private Map<String, Model> models = new LinkedHashMap<>();

    public ChatProviderType getType() {
      return type;
    }

    public void setType(ChatProviderType type) {
      this.type = type;
    }

    public String getDisplayName() {
      return displayName;
    }

    public void setDisplayName(String displayName) {
      this.displayName = displayName;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getCompletionsPath() {
      return completionsPath;
    }

    public void setCompletionsPath(String completionsPath) {
      this.completionsPath = completionsPath;
    }

    public String getEmbeddingsPath() {
      return embeddingsPath;
    }

    public void setEmbeddingsPath(String embeddingsPath) {
      this.embeddingsPath = embeddingsPath;
    }

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }

    public Integer getMaxTokens() {
      return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
      this.maxTokens = maxTokens;
    }

    public Double getTemperature() {
      return temperature;
    }

    public void setTemperature(Double temperature) {
      this.temperature = temperature;
    }

    public Double getTopP() {
      return topP;
    }

    public void setTopP(Double topP) {
      this.topP = topP;
    }

    public String getDefaultModel() {
      return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
      this.defaultModel = defaultModel;
    }

    public Map<String, Model> getModels() {
      return models;
    }

    public void setModels(Map<String, Model> models) {
      this.models = models;
    }
  }

  public static class Model {
    private String displayName;
    private String tier;
    private Pricing pricing = new Pricing();

    public String getDisplayName() {
      return displayName;
    }

    public void setDisplayName(String displayName) {
      this.displayName = displayName;
    }

    public String getTier() {
      return tier;
    }

    public void setTier(String tier) {
      this.tier = tier;
    }

    public Pricing getPricing() {
      return pricing;
    }

    public void setPricing(Pricing pricing) {
      this.pricing = pricing;
    }
  }

  public static class Pricing {
    private BigDecimal inputPer1KTokens = BigDecimal.ZERO;
    private BigDecimal outputPer1KTokens = BigDecimal.ZERO;

    public Pricing() {}

    public Pricing(
        @DefaultValue("0") BigDecimal inputPer1KTokens,
        @DefaultValue("0") BigDecimal outputPer1KTokens) {
      this.inputPer1KTokens = inputPer1KTokens;
      this.outputPer1KTokens = outputPer1KTokens;
    }

    public BigDecimal getInputPer1KTokens() {
      return inputPer1KTokens;
    }

    public void setInputPer1KTokens(BigDecimal inputPer1KTokens) {
      this.inputPer1KTokens = inputPer1KTokens;
    }

    public BigDecimal getOutputPer1KTokens() {
      return outputPer1KTokens;
    }

    public void setOutputPer1KTokens(BigDecimal outputPer1KTokens) {
      this.outputPer1KTokens = outputPer1KTokens;
    }
  }
}
