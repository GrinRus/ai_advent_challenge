package com.aiadvent.backend.help.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.aiadvent.backend.help.domain.HelpMessage;
import com.aiadvent.backend.help.persistence.HelpMessageRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HelpServiceTest {

  @Mock private HelpMessageRepository repository;

  private HelpService helpService;

  @BeforeEach
  void setUp() {
    helpService = new HelpService(repository);
  }

  @Test
  void shouldReturnStoredMessageWhenPresent() {
    given(repository.findTopByOrderByIdDesc()).willReturn(Optional.of(new HelpMessage("stored")));

    assertThat(helpService.getHelpMessage()).isEqualTo("stored");
  }

  @Test
  void shouldFallbackToDefaultWhenNoMessageExists() {
    given(repository.findTopByOrderByIdDesc()).willReturn(Optional.empty());

    assertThat(helpService.getHelpMessage())
        .isEqualTo("AI Advent Challenge: explore available endpoints via /api/help.");
  }
}
