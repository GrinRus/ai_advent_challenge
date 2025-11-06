package com.aiadvent.mcp.backend.notes.config;

import com.aiadvent.mcp.backend.config.NotesBackendProperties;
import javax.sql.DataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIdType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.aiadvent.mcp.backend.notes.persistence")
@EntityScan(basePackages = "com.aiadvent.mcp.backend.notes.persistence")
public class NotesConfiguration {

  @Bean
  VectorStore notesVectorStore(
      DataSource dataSource, EmbeddingModel embeddingModel, NotesBackendProperties properties) {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    PgVectorStore.PgVectorStoreBuilder builder =
        PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .vectorTableName(properties.getStorage().getVectorTable())
            .dimensions(properties.getEmbedding().getDimensions())
            .distanceType(PgDistanceType.COSINE_DISTANCE)
            .idType(PgIdType.UUID)
            .indexType(PgIndexType.IVFFLAT)
            .initializeSchema(false)
            .vectorTableValidationsEnabled(properties.getStorage().isSchemaValidation());

    return builder.build();
  }
}
