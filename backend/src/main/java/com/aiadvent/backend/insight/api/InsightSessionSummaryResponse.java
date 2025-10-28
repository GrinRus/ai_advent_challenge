package com.aiadvent.backend.insight.api;

import com.aiadvent.backend.insight.InsightSessionType;
import java.time.Instant;
import java.util.UUID;

public record InsightSessionSummaryResponse(
    InsightSessionType type,
    UUID sessionId,
    String title,
    Instant createdAt,
    Instant lastActivityAt,
    String status,
    UUID flowDefinitionId,
    Integer flowDefinitionVersion,
    UUID chatSessionId,
    Long messageCount,
    Long stepCount,
    String summaryPreview) {}
