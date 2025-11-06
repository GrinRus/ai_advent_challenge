package com.aiadvent.backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.chat.api.ChatInteractionMode;
import com.aiadvent.backend.chat.config.ChatResearchProperties;
import com.aiadvent.backend.chat.config.ChatResearchProperties.ToolBindingProperties;
import com.aiadvent.backend.flow.tool.persistence.ToolDefinitionRepository;
import com.aiadvent.backend.flow.tool.service.McpToolBindingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;

class ChatResearchToolBindingServiceTest {

  @SuppressWarnings("unchecked")
  @Test
  void withRequestOverridesPropagatesOverridesToMcpResolution() throws Exception {
    McpToolBindingService mcpToolBindingService = mock(McpToolBindingService.class);
    ToolDefinitionRepository toolDefinitionRepository = mock(ToolDefinitionRepository.class);
    ChatResearchProperties properties = new ChatResearchProperties();
    ToolBindingProperties binding = new ToolBindingProperties();
    binding.setCode("notes.save_note");
    properties.setTools(List.of(binding));

    ChatResearchToolBindingService service =
        new ChatResearchToolBindingService(
            mcpToolBindingService, new ObjectMapper(), properties, toolDefinitionRepository);

    ToolCallback toolCallback = mock(ToolCallback.class);
    when(mcpToolBindingService.resolveCallbacks(anyList(), anyString(), anyList(), anyMap()))
        .thenReturn(List.of(new McpToolBindingService.ResolvedTool("notes.save_note", toolCallback)));

    ObjectMapper mapper = new ObjectMapper();
    Map<String, JsonNode> overrides =
        Map.of("notes.save_note", mapper.createObjectNode().put("userNamespace", "telegram"));

    try (AutoCloseable ignored = service.withRequestOverrides(overrides)) {
      service.resolve(ChatInteractionMode.RESEARCH, "collect notes", List.of("notes.save_note"));
    }

    ArgumentCaptor<Map<String, JsonNode>> captor = ArgumentCaptor.forClass(Map.class);
    verify(mcpToolBindingService)
        .resolveCallbacks(anyList(), anyString(), anyList(), captor.capture());

    assertThat(captor.getValue()).containsKey("notes.save_note");
  }
}
