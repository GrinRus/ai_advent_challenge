package com.aiadvent.backend.flow.api;

public record FlowValidationIssue(String code, String message, String path, String stepId) {}
