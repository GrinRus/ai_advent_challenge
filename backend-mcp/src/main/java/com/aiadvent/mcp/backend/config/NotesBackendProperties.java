package com.aiadvent.mcp.backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notes")
public class NotesBackendProperties {

  private Embedding embedding = new Embedding();
  private Search search = new Search();
  private Storage storage = new Storage();
  private Validation validation = new Validation();

  public Embedding getEmbedding() {
    return embedding;
  }

  public void setEmbedding(Embedding embedding) {
    this.embedding = embedding;
  }

  public Search getSearch() {
    return search;
  }

  public void setSearch(Search search) {
    this.search = search;
  }

  public Storage getStorage() {
    return storage;
  }

  public void setStorage(Storage storage) {
    this.storage = storage;
  }

  public Validation getValidation() {
    return validation;
  }

  public void setValidation(Validation validation) {
    this.validation = validation;
  }

  public static class Embedding {

    @NotBlank private String model = "text-embedding-3-small";

    @NotNull
    @Min(1)
    private Integer dimensions = 1536;

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public Integer getDimensions() {
      return dimensions;
    }

    public void setDimensions(Integer dimensions) {
      this.dimensions = dimensions;
    }
  }

  public static class Search {

    @Min(1)
    @Max(50)
    private int defaultTopK = 5;

    @Min(0)
    private double minScore = 0.55;

    public int getDefaultTopK() {
      return defaultTopK;
    }

    public void setDefaultTopK(int defaultTopK) {
      this.defaultTopK = defaultTopK;
    }

    public double getMinScore() {
      return minScore;
    }

    public void setMinScore(double minScore) {
      this.minScore = minScore;
    }
  }

  public static class Storage {

    @NotBlank private String vectorTable = "note_vector_store";

    private boolean schemaValidation = true;

    public String getVectorTable() {
      return vectorTable;
    }

    public void setVectorTable(String vectorTable) {
      this.vectorTable = vectorTable;
    }

    public boolean isSchemaValidation() {
      return schemaValidation;
    }

    public void setSchemaValidation(boolean schemaValidation) {
      this.schemaValidation = schemaValidation;
    }
  }

  public static class Validation {

    @Min(1)
    @Max(160)
    private int maxTitleLength = 160;

    @Min(1)
    @Max(4000)
    private int maxContentLength = 4000;

    @Min(0)
    @Max(50)
    private int maxTags = 25;

    public int getMaxTitleLength() {
      return maxTitleLength;
    }

    public void setMaxTitleLength(int maxTitleLength) {
      this.maxTitleLength = maxTitleLength;
    }

    public int getMaxContentLength() {
      return maxContentLength;
    }

    public void setMaxContentLength(int maxContentLength) {
      this.maxContentLength = maxContentLength;
    }

    public int getMaxTags() {
      return maxTags;
    }

    public void setMaxTags(int maxTags) {
      this.maxTags = maxTags;
    }
  }
}
