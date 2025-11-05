package com.aiadvent.backend.telegram.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

@Component
public class TelegramChatStateStore {

  private final Map<Long, TelegramChatState> states = new ConcurrentHashMap<>();

  public TelegramChatState getOrCreate(long chatId, Supplier<TelegramChatState> factory) {
    return states.computeIfAbsent(chatId, ignored -> factory.get());
  }

  public Optional<TelegramChatState> find(long chatId) {
    return Optional.ofNullable(states.get(chatId));
  }

  public TelegramChatState compute(
      long chatId, Supplier<TelegramChatState> factory, UnaryOperator<TelegramChatState> updater) {
    return states.compute(
        chatId,
        (id, current) -> {
          TelegramChatState base = current != null ? current : factory.get();
          return updater.apply(base);
        });
  }

  public void reset(long chatId) {
    states.remove(chatId);
  }
}

