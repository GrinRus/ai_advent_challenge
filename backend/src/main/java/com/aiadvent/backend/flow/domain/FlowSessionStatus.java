package com.aiadvent.backend.flow.domain;

public enum FlowSessionStatus {
  PENDING,
  WAITING_STEP_APPROVAL,
  RUNNING,
  PAUSED,
  COMPLETED,
  FAILED,
  ABORTED,
  CANCELLED
}
