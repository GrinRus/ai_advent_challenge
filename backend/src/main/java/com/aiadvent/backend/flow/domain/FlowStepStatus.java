package com.aiadvent.backend.flow.domain;

public enum FlowStepStatus {
  PENDING,
  WAITING_APPROVAL,
  WAITING_USER_INPUT,
  RUNNING,
  COMPLETED,
  FAILED,
  SKIPPED,
  CANCELLED
}
