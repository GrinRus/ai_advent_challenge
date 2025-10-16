package com.aiadvent.backend.chat.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChatProviderController.class)
class ChatProviderControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private ChatProviderService chatProviderService;

  @Test
  void listProvidersReturnsMetadata() throws Exception {
    ChatProvidersProperties.Provider openai = new ChatProvidersProperties.Provider();
    openai.setType(ChatProviderType.OPENAI);
    openai.setDisplayName("OpenAI");
    openai.setDefaultModel("gpt-4o-mini");
    openai.setTemperature(0.7);
    openai.setTopP(1.0);
    openai.setMaxTokens(1024);

    ChatProvidersProperties.Model gpt4oMini = new ChatProvidersProperties.Model();
    gpt4oMini.setDisplayName("GPT-4o Mini");
    gpt4oMini.setTier("budget");
    gpt4oMini.getPricing().setInputPer1KTokens(new BigDecimal("0.00015"));
    gpt4oMini.getPricing().setOutputPer1KTokens(new BigDecimal("0.0006"));
    openai.getModels().put("gpt-4o-mini", gpt4oMini);

    ChatProvidersProperties.Model gpt4o = new ChatProvidersProperties.Model();
    gpt4o.setDisplayName("GPT-4o");
    gpt4o.setTier("pro");
    gpt4o.getPricing().setInputPer1KTokens(new BigDecimal("0.0012"));
    gpt4o.getPricing().setOutputPer1KTokens(new BigDecimal("0.0032"));
    openai.getModels().put("gpt-4o", gpt4o);

    ChatProvidersProperties.Provider zhipu = new ChatProvidersProperties.Provider();
    zhipu.setType(ChatProviderType.ZHIPUAI);
    zhipu.setDisplayName("ZhiPu AI");
    zhipu.setDefaultModel("glm-4-6");
    zhipu.setTemperature(0.6);
    zhipu.setTopP(0.9);
    zhipu.setMaxTokens(2048);

    ChatProvidersProperties.Model glm46 = new ChatProvidersProperties.Model();
    glm46.setDisplayName("GLM-4.6");
    glm46.setTier("pro");
    glm46.getPricing().setInputPer1KTokens(new BigDecimal("0.0006"));
    glm46.getPricing().setOutputPer1KTokens(new BigDecimal("0.0022"));
    zhipu.getModels().put("glm-4-6", glm46);

    ChatProvidersProperties.Model glm4Air = new ChatProvidersProperties.Model();
    glm4Air.setDisplayName("GLM-4 Air");
    glm4Air.setTier("standard");
    glm4Air.getPricing().setInputPer1KTokens(new BigDecimal("0.0008"));
    glm4Air.getPricing().setOutputPer1KTokens(new BigDecimal("0.0008"));
    zhipu.getModels().put("glm-4-air", glm4Air);

    Map<String, ChatProvidersProperties.Provider> providers = new LinkedHashMap<>();
    providers.put("openai", openai);
    providers.put("zhipu", zhipu);

    when(chatProviderService.defaultProvider()).thenReturn("openai");
    when(chatProviderService.providers()).thenReturn(providers);

    mockMvc
        .perform(get("/api/llm/providers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultProvider").value("openai"))
        .andExpect(jsonPath("$.providers[0].id").value("openai"))
        .andExpect(jsonPath("$.providers[0].displayName").value("OpenAI"))
        .andExpect(jsonPath("$.providers[0].type").value("openai"))
        .andExpect(jsonPath("$.providers[0].defaultModel").value("gpt-4o-mini"))
        .andExpect(jsonPath("$.providers[0].temperature").value(0.7))
        .andExpect(jsonPath("$.providers[0].topP").value(1.0))
        .andExpect(jsonPath("$.providers[0].maxTokens").value(1024))
        .andExpect(jsonPath("$.providers[0].models[0].id").value("gpt-4o-mini"))
        .andExpect(jsonPath("$.providers[0].models[0].displayName").value("GPT-4o Mini"))
        .andExpect(jsonPath("$.providers[0].models[0].tier").value("budget"))
        .andExpect(jsonPath("$.providers[1].id").value("zhipu"))
        .andExpect(jsonPath("$.providers[1].defaultModel").value("glm-4-6"))
        .andExpect(jsonPath("$.providers[1].models[0].id").value("glm-4-6"))
        .andExpect(jsonPath("$.providers[1].models[1].id").value("glm-4-air"));
  }
}
