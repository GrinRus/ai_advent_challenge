package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Cost breakdown for a single chat interaction.")
public record UsageCostDetails(
    @Schema(description = "Cost attributed to prompt tokens.") BigDecimal input,
    @Schema(description = "Cost attributed to completion tokens.") BigDecimal output,
    @Schema(description = "Total cost (input + output).") BigDecimal total,
    @Schema(description = "Billing currency, e.g. USD.", example = "USD") String currency) {}
