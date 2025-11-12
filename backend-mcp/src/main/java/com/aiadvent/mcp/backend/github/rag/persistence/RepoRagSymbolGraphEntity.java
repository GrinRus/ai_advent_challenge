package com.aiadvent.mcp.backend.github.rag.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "repo_rag_symbol_graph")
public class RepoRagSymbolGraphEntity {

  @Id
  @GeneratedValue
  @UuidGenerator
  private UUID id;

  @Column(name = "namespace", length = 256, nullable = false)
  private String namespace;

  @Column(name = "file_path", nullable = false, columnDefinition = "text")
  private String filePath;

  @Column(name = "chunk_index", nullable = false)
  private int chunkIndex;

  @Column(name = "chunk_hash", length = 64, nullable = false)
  private String chunkHash;

  @Column(name = "symbol_fqn", nullable = false, columnDefinition = "text")
  private String symbolFqn;

  @Column(name = "symbol_kind", length = 64)
  private String symbolKind;

  @Column(name = "referenced_symbol_fqn", columnDefinition = "text")
  private String referencedSymbolFqn;

  @Column(name = "relation", length = 32, nullable = false)
  private String relation;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public UUID getId() {
    return id;
  }

  public String getNamespace() {
    return namespace;
  }

  public void setNamespace(String namespace) {
    this.namespace = namespace;
  }

  public String getFilePath() {
    return filePath;
  }

  public void setFilePath(String filePath) {
    this.filePath = filePath;
  }

  public int getChunkIndex() {
    return chunkIndex;
  }

  public void setChunkIndex(int chunkIndex) {
    this.chunkIndex = chunkIndex;
  }

  public String getChunkHash() {
    return chunkHash;
  }

  public void setChunkHash(String chunkHash) {
    this.chunkHash = chunkHash;
  }

  public String getSymbolFqn() {
    return symbolFqn;
  }

  public void setSymbolFqn(String symbolFqn) {
    this.symbolFqn = symbolFqn;
  }

  public String getSymbolKind() {
    return symbolKind;
  }

  public void setSymbolKind(String symbolKind) {
    this.symbolKind = symbolKind;
  }

  public String getReferencedSymbolFqn() {
    return referencedSymbolFqn;
  }

  public void setReferencedSymbolFqn(String referencedSymbolFqn) {
    this.referencedSymbolFqn = referencedSymbolFqn;
  }

  public String getRelation() {
    return relation;
  }

  public void setRelation(String relation) {
    this.relation = relation;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
