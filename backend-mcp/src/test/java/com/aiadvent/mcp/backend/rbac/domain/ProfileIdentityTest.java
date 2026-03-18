package com.aiadvent.mcp.backend.rbac.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProfileIdentityTest {

    @Test
    void shouldCreateProfileIdentityWithValidValues() {
        ProfileIdentity identity = new ProfileIdentity("github", "user123");

        assertThat(identity.namespace()).isEqualTo("github");
        assertThat(identity.reference()).isEqualTo("user123");
    }

    @Test
    void shouldThrowExceptionWhenNamespaceIsNull() {
        assertThatThrownBy(() -> new ProfileIdentity(null, "user123"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("namespace");
    }

    @Test
    void shouldThrowExceptionWhenReferenceIsNull() {
        assertThatThrownBy(() -> new ProfileIdentity("github", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reference");
    }

    @Test
    void shouldThrowExceptionWhenNamespaceIsBlank() {
        assertThatThrownBy(() -> new ProfileIdentity("", "user123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace");
    }

    @Test
    void shouldThrowExceptionWhenReferenceIsBlank() {
        assertThatThrownBy(() -> new ProfileIdentity("github", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference");
    }

    @Test
    void shouldThrowExceptionWhenNamespaceIsWhitespace() {
        assertThatThrownBy(() -> new ProfileIdentity("   ", "user123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace");
    }

    @Test
    void shouldThrowExceptionWhenReferenceIsWhitespace() {
        assertThatThrownBy(() -> new ProfileIdentity("github", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference");
    }

    @Test
    void shouldFormatToStringAsNamespaceColonReference() {
        ProfileIdentity identity = new ProfileIdentity("github", "user123");

        assertThat(identity.toString()).isEqualTo("github:user123");
    }

    @Test
    void shouldHandleDifferentNamespacesInToString() {
        ProfileIdentity identity = new ProfileIdentity("local", "admin");

        assertThat(identity.toString()).isEqualTo("local:admin");
    }
}
