package com.aiadvent.mcp.backend.rbac.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.mcp.backend.McpApplication;
import com.aiadvent.mcp.backend.PostgresTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.EnabledIf;

@DataJpaTest
@Import({AuditLogRepositoryTest.TestConfig.class})
@ActiveProfiles("rbac")
@EnabledIf(
        expression = "#{T(com.aiadvent.mcp.backend.PostgresTestContainer).dockerAvailable()}",
        reason = "Docker is required for Postgres-backed tests",
        loadContext = false)
class AuditLogRepositoryTest {

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.register(registry);
        registry.add(
                "spring.liquibase.change-log",
                () -> "classpath:db/changelog/rbac/db.changelog-rbac-001.yaml");
        registry.add("spring.liquibase.contexts", () -> "rbac");
    }

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldSaveAndRetrieveAuditLogEntry() {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setActorNamespace("github");
        entity.setActorReference("user123");
        entity.setAction("ACCESS");
        entity.setResult("ALLOWED");
        entity.setTargetResource("/api/resource");
        entity.setHttpMethod("GET");
        entity.setHttpPath("/api/resource");

        AuditLogEntity saved = auditLogRepository.save(entity);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTimestamp()).isNotNull();
        assertThat(saved.getActorNamespace()).isEqualTo("github");
        assertThat(saved.getActorReference()).isEqualTo("user123");
        assertThat(saved.getAction()).isEqualTo("ACCESS");
        assertThat(saved.getResult()).isEqualTo("ALLOWED");
    }

    @Test
    void shouldSaveAuditLogWithMetadata() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("ipAddress", "192.168.1.1");
        metadata.put("userAgent", "Mozilla/5.0");

        AuditLogEntity entity = new AuditLogEntity();
        entity.setActorNamespace("local");
        entity.setActorReference("admin");
        entity.setAction("GRANT");
        entity.setResult("SUCCESS");
        entity.setTargetProfileNamespace("github");
        entity.setTargetProfileReference("target-user");
        entity.setMetadata(metadata);

        AuditLogEntity saved = auditLogRepository.save(entity);

        assertThat(saved.getMetadata()).isNotNull();
        assertThat(saved.getMetadata().get("ipAddress").asText()).isEqualTo("192.168.1.1");
        assertThat(saved.getMetadata().get("userAgent").asText()).isEqualTo("Mozilla/5.0");
    }

    @Test
    void shouldFindByActorNamespaceAndActorReference() {
        // Create first entry
        AuditLogEntity entity1 = new AuditLogEntity();
        entity1.setActorNamespace("github");
        entity1.setActorReference("user123");
        entity1.setAction("ACCESS");
        entity1.setResult("ALLOWED");
        auditLogRepository.save(entity1);

        // Create second entry for same actor
        AuditLogEntity entity2 = new AuditLogEntity();
        entity2.setActorNamespace("github");
        entity2.setActorReference("user123");
        entity2.setAction("DENIED");
        entity2.setResult("DENIED");
        auditLogRepository.save(entity2);

        // Create entry for different actor
        AuditLogEntity entity3 = new AuditLogEntity();
        entity3.setActorNamespace("github");
        entity3.setActorReference("other-user");
        entity3.setAction("ACCESS");
        entity3.setResult("ALLOWED");
        auditLogRepository.save(entity3);

        Page<AuditLogEntity> results = auditLogRepository.findByActorNamespaceAndActorReference(
                "github", "user123", PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(2);
        assertThat(results.getContent())
                .extracting(AuditLogEntity::getActorReference)
                .containsOnly("user123");
    }

    @Test
    void shouldFindByTimestampBetween() {
        Instant now = Instant.now();
        Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);
        Instant twoHoursAgo = now.minus(2, ChronoUnit.HOURS);
        Instant thirtyMinutesAgo = now.minus(30, ChronoUnit.MINUTES);

        // Create entry with explicit timestamp (2 hours ago)
        AuditLogEntity entity1 = new AuditLogEntity();
        entity1.setActorNamespace("github");
        entity1.setActorReference("user1");
        entity1.setAction("ACCESS");
        entity1.setResult("ALLOWED");
        auditLogRepository.save(entity1);

        // Create another entry
        AuditLogEntity entity2 = new AuditLogEntity();
        entity2.setActorNamespace("github");
        entity2.setActorReference("user2");
        entity2.setAction("ACCESS");
        entity2.setResult("ALLOWED");
        auditLogRepository.save(entity2);

        Page<AuditLogEntity> results = auditLogRepository.findByTimestampBetween(
                twoHoursAgo, now, PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(2);
    }

    @Test
    void shouldReturnEmptyPageWhenNoMatches() {
        Page<AuditLogEntity> results = auditLogRepository.findByActorNamespaceAndActorReference(
                "nonexistent", "user", PageRequest.of(0, 10));

        assertThat(results.getContent()).isEmpty();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
