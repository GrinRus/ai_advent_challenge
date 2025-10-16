package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Single structured insight item produced by the provider.")
public record StructuredSyncItem(
    @Schema(description = "Short headline describing the item.") String title,
    @Schema(description = "Detailed explanation for the item.") String details,
    @Schema(description = "Optional categorical tags for consumption by the client.") List<String> tags) {}
