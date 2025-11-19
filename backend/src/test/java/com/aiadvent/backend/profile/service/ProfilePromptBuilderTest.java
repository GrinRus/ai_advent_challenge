package com.aiadvent.backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.profile.domain.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfilePromptBuilderTest {

  private final ProfilePromptBuilder builder = new ProfilePromptBuilder();

  @Test
  void addsMetadataSectionWhenObjectHasValues() {
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("title", "Master");
    metadata.put("age", 900);
    metadata.put("darkSide", false);
    metadata.set("companions", JsonNodeFactory.instance.arrayNode().add("R2"));

    String snippet = builder.buildPersonaSnippet(createProfile(metadata));

    assertThat(snippet)
        .contains("Additional profile metadata: ")
        .contains("title=Master")
        .contains("age=900")
        .contains("darkSide=false")
        .contains("companions=[\"R2\"]");
  }

  @Test
  void skipsMetadataSectionWhenObjectEmpty() {
    JsonNode emptyMetadata = JsonNodeFactory.instance.objectNode();

    String snippet = builder.buildPersonaSnippet(createProfile(emptyMetadata));

    assertThat(snippet).doesNotContain("Additional profile metadata:");
  }

  private UserProfileDocument createProfile(JsonNode metadata) {
    return new UserProfileDocument(
        UUID.randomUUID(),
        "ns",
        "ref",
        "Master Yoda",
        "en",
        "Dagobah",
        UserProfile.CommunicationMode.VOICE,
        List.of("train padawans"),
        List.of("fear"),
        JsonNodeFactory.instance.objectNode(),
        metadata,
        Collections.emptyList(),
        Collections.emptyList(),
        List.of("user"),
        Instant.parse("2024-01-01T00:00:00Z"),
        1L);
  }
}
