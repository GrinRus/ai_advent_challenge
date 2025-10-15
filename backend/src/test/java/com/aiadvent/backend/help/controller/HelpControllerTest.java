package com.aiadvent.backend.help.controller;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.help.service.HelpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;

@WebMvcTest(HelpController.class)
class HelpControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private HelpService helpService;

  @Test
  void shouldReturnHelpMessage() throws Exception {
    given(helpService.getHelpMessage()).willReturn("Test message");

    mockMvc
        .perform(get("/api/help"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message", equalTo("Test message")));
  }
}
