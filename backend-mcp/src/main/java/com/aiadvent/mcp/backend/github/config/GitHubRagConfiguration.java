package com.aiadvent.mcp.backend.github.config;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.HeuristicRepoRagSearchReranker;
import com.aiadvent.mcp.backend.github.rag.RepoRagSearchReranker;
import com.aiadvent.mcp.backend.github.rag.RepoRagSymbolService;
import com.aiadvent.mcp.backend.github.rag.RepoRagToolConfiguration;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagDocumentMapper;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagDocumentRepository;
import javax.sql.DataSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIdType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.aiadvent.mcp.backend.github.rag.persistence")
@EntityScan(basePackages = "com.aiadvent.mcp.backend.github.rag.persistence")
@Import(RepoRagToolConfiguration.class)
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

  @Bean
  RepoRagSearchReranker repoRagSearchReranker(
      GitHubRagProperties properties,
      @Qualifier("repoRagSnippetCompressorChatClientBuilder")
          ObjectProvider<ChatClient.Builder> snippetCompressorBuilder,
      RepoRagDocumentRepository documentRepository,
      RepoRagDocumentMapper documentMapper,
      RepoRagSymbolService symbolService) {
    return new HeuristicRepoRagSearchReranker(
        properties, snippetCompressorBuilder, documentRepository, documentMapper, symbolService);
  }

  @Bean(name = "repoRagQueryTransformerChatClientBuilder")
  ChatClient.Builder repoRagQueryTransformerChatClientBuilder(
      OpenAiChatModel chatModel, GitHubRagProperties properties) {
    OpenAiChatOptions options =
        OpenAiChatOptions.builder()
            .model(properties.getQueryTransformers().getModel())
            .temperature(properties.getQueryTransformers().getTemperature())
            .build();
    return ChatClient.builder(chatModel).defaultOptions(options);
  }

  @Bean(name = "repoRagSnippetCompressorChatClientBuilder")
  ChatClient.Builder repoRagSnippetCompressorChatClientBuilder(
      OpenAiChatModel chatModel, GitHubRagProperties properties) {
    OpenAiChatOptions options =
        OpenAiChatOptions.builder()
            .model(properties.getPostProcessing().getLlmCompressionModel())
            .temperature(properties.getPostProcessing().getLlmCompressionTemperature())
            .build();
    return ChatClient.builder(chatModel).defaultOptions(options);
  }
}
