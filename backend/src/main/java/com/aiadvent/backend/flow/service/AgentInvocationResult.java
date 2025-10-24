package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import java.util.List;

public record AgentInvocationResult(
    String content,
    UsageCostEstimate usageCost,
    List<FlowMemoryVersion> memoryUpdates,
    ChatProviderSelection providerSelection,
    ChatRequestOverrides appliedOverrides,
    String systemPrompt,
    List<String> memorySnapshots,
    String userMessage) {}
