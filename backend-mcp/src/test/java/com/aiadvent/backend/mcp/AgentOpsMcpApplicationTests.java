package com.aiadvent.backend.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = AgentOpsMcpApplication.class, properties = "spring.profiles.active=agentops")
class AgentOpsMcpApplicationTests {

  @Test
  void contextLoads() {}
}
