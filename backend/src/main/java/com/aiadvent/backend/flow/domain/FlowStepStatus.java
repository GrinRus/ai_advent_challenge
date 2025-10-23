package com.aiadvent.backend.flow.domain;

public enum FlowStepStatus {
  PENDING,
  WAITING_APPROVAL,
  RUNNING,
  COMPLETED,
  FAILED,
  SKIPPED,
  CANCELLED
}
