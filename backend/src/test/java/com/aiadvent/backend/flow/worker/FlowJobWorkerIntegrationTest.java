package com.aiadvent.backend.flow.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.persistence.AgentDefinitionRepository;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.service.AgentOrchestratorService;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "spring.ai.openai.api-key=test-token",
      "spring.ai.openai.base-url=http://localhost",
      "app.chat.default-provider=stub"
    })
@ActiveProfiles("test")
class FlowJobWorkerIntegrationTest extends PostgresTestContainer {

  @Autowired private FlowJobWorker flowJobWorker;
  @Autowired private AgentOrchestratorService orchestratorService;
  @Autowired private FlowSessionRepository flowSessionRepository;
  @Autowired private FlowDefinitionRepository flowDefinitionRepository;
  @Autowired private AgentDefinitionRepository agentDefinitionRepository;
  @Autowired private AgentVersionRepository agentVersionRepository;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void clean() {
    flowSessionRepository.deleteAll();
    flowDefinitionRepository.deleteAll();
    agentVersionRepository.deleteAll();
    agentDefinitionRepository.deleteAll();
  }

  @Test
  void pollQueueProcessesJobAndUpdatesMetrics() throws Exception {
    AgentVersion agentVersion = persistAgent();
    FlowDefinition definition = persistFlowDefinition(agentVersion.getId());

    FlowSession session =
        orchestratorService.start(definition.getId(), objectMapper.nullNode(), objectMapper.nullNode(), null);

    flowJobWorker.pollQueue();

    FlowSession reloaded = waitForCompletion(session.getId());
    assertThat(reloaded.getStatus()).isEqualTo(FlowSessionStatus.COMPLETED);

    double processedCount =
        meterRegistry.counter("flow.job.poll.count", "result", "processed").count();
    assertThat(processedCount).isGreaterThanOrEqualTo(1d);
  }

  private FlowSession waitForCompletion(UUID sessionId) throws InterruptedException {
    FlowSession session = null;
    for (int attempt = 0; attempt < 20; attempt++) {
      Thread.sleep(100);
      session = flowSessionRepository.findById(sessionId).orElse(null);
      if (session != null && session.getStatus() == FlowSessionStatus.COMPLETED) {
        break;
      }
    }
    if (session == null) {
      throw new IllegalStateException("Flow session not found: " + sessionId);
    }
    return session;
  }

  private AgentVersion persistAgent() {
    AgentDefinition definition = new AgentDefinition("worker-agent", "Worker Agent", null, true);
    agentDefinitionRepository.save(definition);
    AgentVersion version =
        new AgentVersion(
            definition,
            1,
            AgentVersionStatus.PUBLISHED,
            com.aiadvent.backend.chat.config.ChatProviderType.OPENAI,
            "openai",
            "gpt-4o-mini");
    return agentVersionRepository.save(version);
  }

  private FlowDefinition persistFlowDefinition(UUID agentVersionId) {
    ObjectMapper mapper = objectMapper;
    var root = mapper.createObjectNode();
    root.put("startStepId", "step-1");
    var steps = root.putArray("steps");
    var step = steps.addObject();
    step.put("id", "step-1");
    step.put("name", "Initial step");
    step.put("agentVersionId", agentVersionId.toString());
    step.put("prompt", "solve task");

    FlowDefinition definition =
        new FlowDefinition("worker-flow", 1, FlowDefinitionStatus.PUBLISHED, true, root);
    return flowDefinitionRepository.save(definition);
  }
}
