package com.aiadvent.backend.flow.api;

import java.util.List;

public record FlowInteractionListResponse(
    List<FlowInteractionItemResponse> active, List<FlowInteractionItemResponse> history) {}
