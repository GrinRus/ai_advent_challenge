package com.aiadvent.mcp.backend.rbac.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    void shouldContainAdminRole() {
        assertThat(Role.values()).contains(Role.ADMIN);
    }

    @Test
    void shouldContainUserRole() {
        assertThat(Role.values()).contains(Role.USER);
    }

    @Test
    void shouldContainViewerRole() {
        assertThat(Role.values()).contains(Role.VIEWER);
    }

    @Test
    void shouldHaveExactlyThreeRoles() {
        assertThat(Role.values()).hasSize(3);
    }
}
