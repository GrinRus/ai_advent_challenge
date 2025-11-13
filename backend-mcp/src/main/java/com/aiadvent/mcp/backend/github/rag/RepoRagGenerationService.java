package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

@Service
public class RepoRagGenerationService {

  private final GitHubRagProperties properties;
  private final String promptTemplateRaw;
  private final String emptyTemplateRaw;
  private final String summaryTemplateRaw;

  public RepoRagGenerationService(GitHubRagProperties properties, ResourceLoader resourceLoader) {
    this.properties = Objects.requireNonNull(properties, "properties");
    GitHubRagProperties.Generation generation = properties.getGeneration();
    this.promptTemplateRaw = loadTemplate(resourceLoader, generation.getPromptTemplate());
    this.emptyTemplateRaw = loadTemplate(resourceLoader, generation.getEmptyContextTemplate());
    this.summaryTemplateRaw = loadTemplate(resourceLoader, generation.getSummaryTemplate());
  }

  public GenerationResult generate(GenerationCommand command) {
    Objects.requireNonNull(command, "command");
    boolean contextMissing = command.documents().isEmpty();
    String formattedContext = formatDocuments(command.documents());
    List<String> modules = new ArrayList<>();
    Query rawAugmented = null;
    Query summaryAugmented = null;
    if (command.responseChannel().includeRaw()) {
      rawAugmented =
          augment(promptTemplateRaw, emptyTemplateRaw, formattedContext, command);
      modules.add("generation.contextual-augmenter");
    }
    if (command.responseChannel().includeSummary()) {
      summaryAugmented =
          augment(summaryTemplateRaw, emptyTemplateRaw, formattedContext, command);
      modules.add("generation.summary");
    }
    if (contextMissing) {
      modules.add("generation.empty-context");
    }
    String rawAugmentedText = rawAugmented != null ? rawAugmented.text() : null;
    String summaryAugmentedText = summaryAugmented != null ? summaryAugmented.text() : null;
    String fallback = rawAugmentedText != null ? rawAugmentedText : summaryAugmentedText;
    if (fallback == null) {
      fallback = "";
    }
    String rawPrompt = rawAugmentedText != null ? rawAugmentedText : fallback;
    String summaryPrompt = summaryAugmentedText != null ? summaryAugmentedText : fallback;
    String noResultsReason =
        contextMissing ? properties.getGeneration().getNoResultsReason() : null;
    return new GenerationResult(
        rawPrompt,
        summaryPrompt,
        contextMissing,
        noResultsReason,
        modules,
        summaryAugmentedText,
        rawAugmentedText);
  }

  private Query augment(
      String templateRaw,
      String emptyRaw,
      String formattedContext,
      GenerationCommand command) {
    String promptTemplate = resolveTemplate(templateRaw, command);
    String emptyTemplate = resolveTemplate(emptyRaw, command);
    ContextualQueryAugmenter augmenter =
        ContextualQueryAugmenter.builder()
            .promptTemplate(new PromptTemplate(promptTemplate))
            .emptyContextPromptTemplate(new PromptTemplate(emptyTemplate))
            .allowEmptyContext(command.allowEmptyContext())
            .documentFormatter(docs -> formattedContext)
            .build();
    return augmenter.augment(command.query(), command.documents());
  }

  private String resolveTemplate(String template, GenerationCommand command) {
    return template
        .replace("{{repoOwner}}", command.repoOwner())
        .replace("{{repoName}}", command.repoName())
        .replace("{{locale}}", command.locale())
        .replace("{{emptyMessage}}", properties.getGeneration().getEmptyContextMessage());
  }

  private String formatDocuments(List<Document> documents) {
    if (documents.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    int limit = Math.min(documents.size(), 5);
    for (int i = 0; i < limit; i++) {
      Document document = documents.get(i);
      Map<String, Object> metadata = document.getMetadata();
      String path = metadata != null ? (String) metadata.getOrDefault("file_path", "") : "";
      builder
          .append(i + 1)
          .append(". ")
          .append(path)
          .append("\n")
          .append(document.getText())
          .append("\n\n");
    }
    return builder.toString().trim();
  }

  private String loadTemplate(ResourceLoader loader, String location) {
    try {
      Resource resource = loader.getResource(location);
      try (InputStream input = resource.getInputStream()) {
        return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
      }
    } catch (IOException ex) {
      throw new IllegalStateException("Unable to load template: " + location, ex);
    }
  }

  public record GenerationCommand(
      Query query,
      List<Document> documents,
      String repoOwner,
      String repoName,
      String locale,
      boolean allowEmptyContext,
      RepoRagResponseChannel responseChannel) {}

  public record GenerationResult(
      String rawAugmentedPrompt,
      String summaryAugmentedPrompt,
      boolean contextMissing,
      String noResultsReason,
      List<String> appliedModules,
      String summary,
      String rawAnswer) {}
}
