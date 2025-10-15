package com.aiadvent.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.aiadvent.backend.support.PostgresTestContainer;

@SpringBootTest
class BackendApplicationTests extends PostgresTestContainer {

  @Test
  void contextLoads() {}
}
