package com.aiadvent.mcp.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    classes = McpApplication.class,
    properties = {
        "spring.profiles.active=notes",
        "spring.autoconfigure.exclude="
    })
@ActiveProfiles("notes")
class NotesMcpApplicationTests {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("pgvector/pgvector:pg15").withReuse(true);

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add(
                "spring.liquibase.change-log",
                () -> "classpath:db/changelog/notes/db.changelog-master.yaml");
        registry.add("spring.liquibase.contexts", () -> "notes");
        registry.add("notes.storage.vector-table", () -> "note_vector_store");
        registry.add("notes.embedding.model", () -> "text-embedding-3-small");
        registry.add("notes.embedding.dimensions", () -> 1536);
    }

    @Test
    void contextLoads() {
    }
}
