package com.aiadvent.mcp.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = McpApplication.class, properties = "spring.profiles.active=insight")
class InsightMcpApplicationTests {
    @Test
    void contextLoads() {
    }
}
