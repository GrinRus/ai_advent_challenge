package com.aiadvent.mcp.backend.notes.tool;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class NotesToolConfiguration {

  @Bean
  ToolCallbackProvider notesToolCallbackProvider(NotesTools notesTools) {
    return MethodToolCallbackProvider.builder().toolObjects(notesTools).build();
  }
}
