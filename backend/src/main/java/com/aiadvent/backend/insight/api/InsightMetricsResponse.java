package com.aiadvent.backend.insight.api;

import com.aiadvent.backend.insight.InsightSessionType;
import java.time.Instant;
import java.util.UUID;

public record InsightMetricsResponse(
    InsightSessionType type,
    UUID sessionId,
    String status,
    Instant startedAt,
    Instant completedAt,
    Instant lastUpdatedAt,
    int stepsCompleted,
    int stepsFailed,
    int retriesScheduled,
    double totalCostUsd,
    long promptTokens,
    long completionTokens) {}
