package com.aiadvent.backend.chat.service;

import com.aiadvent.backend.chat.api.SessionUsageResponse;
import com.aiadvent.backend.chat.api.StructuredSyncUsageStats;
import com.aiadvent.backend.chat.api.UsageCostDetails;
import com.aiadvent.backend.chat.domain.ChatMessage;
import com.aiadvent.backend.chat.domain.ChatRole;
import com.aiadvent.backend.chat.domain.ChatSession;
import com.aiadvent.backend.chat.persistence.ChatMessageRepository;
import com.aiadvent.backend.chat.persistence.ChatSessionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SessionUsageService {

  private static final int COST_SCALE = 8;

  private final ChatSessionRepository chatSessionRepository;
  private final ChatMessageRepository chatMessageRepository;

  public SessionUsageService(
      ChatSessionRepository chatSessionRepository,
      ChatMessageRepository chatMessageRepository) {
    this.chatSessionRepository = chatSessionRepository;
    this.chatMessageRepository = chatMessageRepository;
  }

  @Transactional(readOnly = true)
  public SessionUsageResponse summarize(UUID sessionId) {
    ChatSession session =
        chatSessionRepository
            .findById(sessionId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Chat session not found: " + sessionId));

    List<ChatMessage> messages =
        chatMessageRepository.findBySessionOrderBySequenceNumberAsc(session);
    List<SessionUsageResponse.MessageUsageBreakdown> breakdowns = new ArrayList<>();
    TotalsAccumulator totals = new TotalsAccumulator();

    for (ChatMessage message : messages) {
      StructuredSyncUsageStats usage = toUsageStats(message);
      UsageCostDetails cost = toCostDetails(message);
      breakdowns.add(
          new SessionUsageResponse.MessageUsageBreakdown(
              message.getId(),
              message.getSequenceNumber(),
              message.getRole() != null ? message.getRole().name() : null,
              message.getProvider(),
              message.getModel(),
              usage,
              cost,
              message.getCreatedAt()));
      totals.accumulate(message);
    }

    SessionUsageResponse.UsageTotals totalsRecord = totals.toTotals();
    return new SessionUsageResponse(sessionId, breakdowns, totalsRecord);
  }

  private StructuredSyncUsageStats toUsageStats(ChatMessage message) {
    Integer promptTokens = message.getPromptTokens();
    Integer completionTokens = message.getCompletionTokens();
    Integer totalTokens = message.getTotalTokens();
    if (promptTokens == null && completionTokens == null && totalTokens == null) {
      return null;
    }
    return new StructuredSyncUsageStats(promptTokens, completionTokens, totalTokens);
  }

  private UsageCostDetails toCostDetails(ChatMessage message) {
    BigDecimal input = message.getInputCost();
    BigDecimal output = message.getOutputCost();
    if (input == null && output == null) {
      return null;
    }
    BigDecimal total = null;
    if (input != null) {
      input = input.setScale(COST_SCALE, RoundingMode.HALF_UP);
    }
    if (output != null) {
      output = output.setScale(COST_SCALE, RoundingMode.HALF_UP);
    }
    if (input != null || output != null) {
      BigDecimal inputValue = input != null ? input : BigDecimal.ZERO;
      BigDecimal outputValue = output != null ? output : BigDecimal.ZERO;
      total = inputValue.add(outputValue).setScale(COST_SCALE, RoundingMode.HALF_UP);
    }
    return new UsageCostDetails(input, output, total, message.getCurrency());
  }

  private static final class TotalsAccumulator {
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private BigDecimal inputCost = BigDecimal.ZERO;
    private BigDecimal outputCost = BigDecimal.ZERO;
    private boolean hasInputCost;
    private boolean hasOutputCost;
    private final Set<String> currencies = new HashSet<>();

    void accumulate(ChatMessage message) {
      if (message.getRole() == ChatRole.ASSISTANT) {
        if (message.getPromptTokens() != null) {
          promptTokens += message.getPromptTokens();
        }
        if (message.getCompletionTokens() != null) {
          completionTokens += message.getCompletionTokens();
        }
        if (message.getTotalTokens() != null) {
          totalTokens += message.getTotalTokens();
        }
        if (message.getInputCost() != null) {
          inputCost = inputCost.add(message.getInputCost());
          hasInputCost = true;
        }
        if (message.getOutputCost() != null) {
          outputCost = outputCost.add(message.getOutputCost());
          hasOutputCost = true;
        }
        if (message.getCurrency() != null) {
          currencies.add(message.getCurrency());
        }
      }
    }

    SessionUsageResponse.UsageTotals toTotals() {
      StructuredSyncUsageStats usage = null;
      if (promptTokens > 0 || completionTokens > 0 || totalTokens > 0) {
        usage = new StructuredSyncUsageStats(promptTokens, completionTokens, totalTokens);
      }

      UsageCostDetails cost = null;
      if (hasInputCost || hasOutputCost) {
        BigDecimal input = hasInputCost ? inputCost.setScale(COST_SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal output = hasOutputCost ? outputCost.setScale(COST_SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal total = null;
        if (input != null || output != null) {
          BigDecimal inputValue = input != null ? input : BigDecimal.ZERO;
          BigDecimal outputValue = output != null ? output : BigDecimal.ZERO;
          total = inputValue.add(outputValue).setScale(COST_SCALE, RoundingMode.HALF_UP);
        }
        String currency = currencies.size() == 1 ? currencies.iterator().next() : null;
        cost = new UsageCostDetails(input, output, total, currency);
      }

      if (usage == null && cost == null) {
        return null;
      }
      return new SessionUsageResponse.UsageTotals(usage, cost);
    }
  }
}
