package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Metadata about the provider and model that processed the request.")
public record StructuredSyncProvider(
    @Schema(description = "Provider type identifier.", example = "ZHIPUAI") String type,
    @Schema(description = "Model identifier used for the response.", example = "glm-4.6") String model) {}
