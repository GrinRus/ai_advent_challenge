package com.aiadvent.mcp.backend.notes.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "note_entry")
public class NoteEntity {

  @Id
  @GeneratedValue
  @UuidGenerator
  private UUID id;

  @Column(name = "title", length = 160, nullable = false)
  private String title;

  @Column(name = "content", nullable = false, columnDefinition = "text")
  private String content;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "tags", columnDefinition = "jsonb")
  private JsonNode tags;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", columnDefinition = "jsonb")
  private JsonNode metadata;

  @Column(name = "user_namespace", length = 32, nullable = false)
  private String userNamespace;

  @Column(name = "user_reference", length = 128, nullable = false)
  private String userReference;

  @Column(name = "source_channel", length = 32)
  private String sourceChannel;

  @Column(name = "content_hash", length = 64, nullable = false)
  private String contentHash;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public UUID getId() {
    return id;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public JsonNode getTags() {
    return tags;
  }

  public void setTags(JsonNode tags) {
    this.tags = tags;
  }

  public JsonNode getMetadata() {
    return metadata;
  }

  public void setMetadata(JsonNode metadata) {
    this.metadata = metadata;
  }

  public String getUserNamespace() {
    return userNamespace;
  }

  public void setUserNamespace(String userNamespace) {
    this.userNamespace = userNamespace;
  }

  public String getUserReference() {
    return userReference;
  }

  public void setUserReference(String userReference) {
    this.userReference = userReference;
  }

  public String getSourceChannel() {
    return sourceChannel;
  }

  public void setSourceChannel(String sourceChannel) {
    this.sourceChannel = sourceChannel;
  }

  public String getContentHash() {
    return contentHash;
  }

  public void setContentHash(String contentHash) {
    this.contentHash = contentHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }
}
