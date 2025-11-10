package com.aiadvent.mcp.backend.github.config;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
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
@EnableJpaRepositories(basePackages = "com.aiadvent.mcp.backend.github.rag.persistence")
@EntityScan(basePackages = "com.aiadvent.mcp.backend.github.rag.persistence")
public class GitHubRagConfiguration {

  @Bean(name = "repoRagVectorStore")
  VectorStore repoRagVectorStore(
      DataSource dataSource, EmbeddingModel embeddingModel, GitHubRagProperties properties) {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    PgVectorStore.PgVectorStoreBuilder builder =
        PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .vectorTableName("repo_rag_vector_store")
            .dimensions(properties.getEmbedding().getDimensions())
            .distanceType(PgDistanceType.COSINE_DISTANCE)
            .idType(PgIdType.UUID)
            .indexType(PgIndexType.IVFFLAT)
            .initializeSchema(false)
            .vectorTableValidationsEnabled(true);
    return builder.build();
  }
}
