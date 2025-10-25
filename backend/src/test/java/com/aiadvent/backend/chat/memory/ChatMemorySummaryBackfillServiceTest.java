package com.aiadvent.backend.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.chat.config.ChatMemoryProperties;
import com.aiadvent.backend.chat.config.ChatMemoryProperties.BackfillProperties;
import com.aiadvent.backend.chat.memory.ChatMemorySummaryBackfillService.CandidateSession;
import com.aiadvent.backend.chat.memory.ChatMemorySummarizerService.SummarizerModelInfo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ChatMemorySummaryBackfillServiceTest {

  @Mock private NamedParameterJdbcTemplate jdbcTemplate;
  @Mock private ChatMemorySummarizerService summarizerService;

  private ChatMemorySummaryBackfillService backfillService;
  private ChatMemoryProperties properties;
  private AutoCloseable mocks;

  @BeforeEach
  void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    properties = new ChatMemoryProperties();
    properties.getSummarization().setEnabled(true);
    BackfillProperties config = properties.getSummarization().getBackfill();
    config.setEnabled(true);
    config.setMinMessages(40);
    config.setBatchSize(5);
    properties.setWindowSize(4);

    backfillService = new ChatMemorySummaryBackfillService(properties, jdbcTemplate, summarizerService);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }

  @Test
  void backfillSkipsWhenSummarizerModelMissing() {
    when(summarizerService.isEnabled()).thenReturn(true);
    when(summarizerService.summarizerModel()).thenReturn(Optional.empty());

    int processed = backfillService.backfillBatch();

    assertThat(processed).isZero();
    verify(jdbcTemplate, never())
        .query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
  }

  @Test
  void backfillProcessesEligibleSessions() {
    UUID eligible = UUID.randomUUID();
    when(summarizerService.isEnabled()).thenReturn(true);
    when(summarizerService.summarizerModel())
        .thenReturn(Optional.of(new SummarizerModelInfo("openai", "gpt-4o-mini")));

    when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of(new CandidateSession(eligible, 80, 80, 0)));

    int processed = backfillService.backfillBatch();

    assertThat(processed).isEqualTo(1);
    verify(summarizerService).forceSummarize(eligible, "openai", "gpt-4o-mini");
  }
}
