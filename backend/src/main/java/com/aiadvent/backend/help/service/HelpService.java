package com.aiadvent.backend.help.service;

import com.aiadvent.backend.help.persistence.HelpMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class HelpService {

  private static final String DEFAULT_MESSAGE =
      "AI Advent Challenge: explore available endpoints via /api/help.";

  private final HelpMessageRepository helpMessageRepository;

  public HelpService(HelpMessageRepository helpMessageRepository) {
    this.helpMessageRepository = helpMessageRepository;
  }

  public String getHelpMessage() {
    log.debug("Fetching help message from repository");
    return helpMessageRepository.findTopByOrderByIdDesc().map(m -> m.getMessage()).orElse(DEFAULT_MESSAGE);
  }
}
