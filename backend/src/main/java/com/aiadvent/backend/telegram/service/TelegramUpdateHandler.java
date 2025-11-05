package com.aiadvent.backend.telegram.service;

import org.telegram.telegrambots.meta.api.objects.Update;

public interface TelegramUpdateHandler {

  void handle(Update update);
}

