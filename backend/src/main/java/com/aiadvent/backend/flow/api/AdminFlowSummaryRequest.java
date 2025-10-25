package com.aiadvent.backend.flow.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.util.StringUtils;

public record AdminFlowSummaryRequest(
    @NotBlank String providerId,
    @NotBlank String modelId,
    @Size(max = 5) List<@NotBlank String> channels) {

  public List<String> normalizedChannels() {
    if (channels == null || channels.isEmpty()) {
      return List.of();
    }
    return channels.stream().filter(StringUtils::hasText).map(String::trim).toList();
  }
}
