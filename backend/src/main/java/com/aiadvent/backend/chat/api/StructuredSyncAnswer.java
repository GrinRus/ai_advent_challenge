package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Primary structured payload returned by the provider.")
public record StructuredSyncAnswer(
    @Schema(description = "Human-readable summary of the response.") String summary,
    @Schema(description = "Collection of structured items, in display order.")
        List<StructuredSyncItem> items,
    @Schema(description = "Confidence score provided by the model (0.0 - 1.0).") BigDecimal confidence) {}
