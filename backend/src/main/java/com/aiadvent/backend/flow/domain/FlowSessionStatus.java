package com.aiadvent.backend.flow.domain;

public enum FlowSessionStatus {
  PENDING,
  WAITING_STEP_APPROVAL,
  WAITING_USER_INPUT,
  RUNNING,
  PAUSED,
  COMPLETED,
  FAILED,
  ABORTED,
  CANCELLED
}
