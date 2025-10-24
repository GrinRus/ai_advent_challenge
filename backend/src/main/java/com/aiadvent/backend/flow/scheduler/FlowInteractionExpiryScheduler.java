package com.aiadvent.backend.flow.scheduler;

import com.aiadvent.backend.flow.service.FlowInteractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FlowInteractionExpiryScheduler {

  private static final Logger log = LoggerFactory.getLogger(FlowInteractionExpiryScheduler.class);

  private final FlowInteractionService flowInteractionService;

  public FlowInteractionExpiryScheduler(FlowInteractionService flowInteractionService) {
    this.flowInteractionService = flowInteractionService;
  }

  @Scheduled(fixedDelayString = "${app.flow.interaction.expiry-check-delay:PT1M}")
  public void expireOverdueInteractions() {
    int processed = flowInteractionService.expireOverdueRequests();
    if (processed > 0) {
      log.info("Expired {} overdue interaction requests", processed);
    }
  }
}
