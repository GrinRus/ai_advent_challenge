package com.aiadvent.backend.chat.controller;

import com.aiadvent.backend.chat.api.ChatProvidersResponse;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/llm/providers")
public class ChatProviderController {

  private final ChatProviderService chatProviderService;

  public ChatProviderController(ChatProviderService chatProviderService) {
    this.chatProviderService = chatProviderService;
  }

  @GetMapping
  public ChatProvidersResponse listProviders() {
    String defaultProvider = chatProviderService.defaultProvider();
    Map<String, ChatProvidersProperties.Provider> providers = chatProviderService.providers();

    List<ChatProvidersResponse.Provider> providerResponses =
        providers.entrySet().stream()
            .map(this::toProviderResponse)
            .toList();

    return new ChatProvidersResponse(defaultProvider, providerResponses);
  }

  private ChatProvidersResponse.Provider toProviderResponse(
      Map.Entry<String, ChatProvidersProperties.Provider> entry) {
    ChatProvidersProperties.Provider provider = entry.getValue();
    List<ChatProvidersResponse.Model> models =
        provider.getModels().entrySet().stream()
            .map(this::toModelResponse)
            .toList();

    return new ChatProvidersResponse.Provider(
        entry.getKey(),
        provider.getDisplayName(),
        provider.getType() != null ? provider.getType().name().toLowerCase() : null,
        provider.getDefaultModel(),
        provider.getTemperature(),
        provider.getTopP(),
        provider.getMaxTokens(),
        models);
  }

  private ChatProvidersResponse.Model toModelResponse(
      Map.Entry<String, ChatProvidersProperties.Model> entry) {
    ChatProvidersProperties.Model model = entry.getValue();
    ChatProvidersProperties.Pricing pricing = model.getPricing();
    BigDecimal inputCost = pricing != null ? pricing.getInputPer1KTokens() : null;
    BigDecimal outputCost = pricing != null ? pricing.getOutputPer1KTokens() : null;

    return new ChatProvidersResponse.Model(
        entry.getKey(), model.getDisplayName(), model.getTier(), inputCost, outputCost);
  }
}
