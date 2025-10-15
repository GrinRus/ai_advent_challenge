package com.aiadvent.backend.chat.service;

import com.aiadvent.backend.chat.domain.ChatRole;

public record ConversationMessage(ChatRole role, String content) {}
