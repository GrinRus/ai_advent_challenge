package com.aiadvent.mcp.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = McpApplication.class,
    properties = {
        "spring.profiles.active=notes",
        "spring.autoconfigure.exclude="
    })
@ActiveProfiles("notes")
class NotesMcpApplicationTests {

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.register(registry);
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
