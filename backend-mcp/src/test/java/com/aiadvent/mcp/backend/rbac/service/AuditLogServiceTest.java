package com.aiadvent.mcp.backend.rbac.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.rbac.domain.ProfileIdentity;
import com.aiadvent.mcp.backend.rbac.domain.Role;
import com.aiadvent.mcp.backend.rbac.persistence.AuditLogEntity;
import com.aiadvent.mcp.backend.rbac.persistence.AuditLogRepository;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private ExecutorService executor;
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        auditLogService = new AuditLogService(auditLogRepository, executor);
    }

    @AfterEach
    void tearDown() {
        auditLogService.shutdown();
        executor.shutdown();
    }

    @Test
    void logAccess_shouldSaveAuditEntry_whenAllowed() throws InterruptedException {
        // Given
        ProfileIdentity identity = new ProfileIdentity("github", "user123");
        Set<Role> roles = Set.of(Role.USER, Role.VIEWER);
        CountDownLatch latch = new CountDownLatch(1);

        when(auditLogRepository.save(any(AuditLogEntity.class))).thenAnswer(inv -> {
            latch.countDown();
            return inv.getArgument(0);
        });

        // When
        auditLogService.logAccess(identity, "ACCESS", "/api/test", "POST", "/api/test", true, roles);

        // Then
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertEquals(true, completed, "Audit log should be saved asynchronously");

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository, times(1)).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertNotNull(saved);
        assertEquals("github", saved.getActorNamespace());
        assertEquals("user123", saved.getActorReference());
        assertEquals("ACCESS", saved.getAction());
        assertEquals("ALLOWED", saved.getResult());
        assertEquals("POST", saved.getHttpMethod());
        assertEquals("/api/test", saved.getHttpPath());
    }

    @Test
    void logAccess_shouldSaveAuditEntry_whenDenied() throws InterruptedException {
        // Given
        ProfileIdentity identity = new ProfileIdentity("local", "viewer1");
        Set<Role> roles = Set.of(Role.VIEWER);
        CountDownLatch latch = new CountDownLatch(1);

        when(auditLogRepository.save(any(AuditLogEntity.class))).thenAnswer(inv -> {
            latch.countDown();
            return inv.getArgument(0);
        });

        // When
        auditLogService.logAccess(identity, "ACCESS", "/api/admin", "DELETE", "/api/admin", false, roles);

        // Then
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertEquals(true, completed, "Audit log should be saved asynchronously");

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository, times(1)).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertNotNull(saved);
        assertEquals("local", saved.getActorNamespace());
        assertEquals("viewer1", saved.getActorReference());
        assertEquals("DENIED", saved.getResult());
    }

    @Test
    void logDenied_shouldSaveAuditEntry_withReason() throws InterruptedException {
        // Given
        ProfileIdentity identity = new ProfileIdentity("test", "user456");
        Set<Role> roles = Set.of(Role.VIEWER);
        String reason = "Insufficient permissions. Required roles: [ADMIN]";
        CountDownLatch latch = new CountDownLatch(1);

        when(auditLogRepository.save(any(AuditLogEntity.class))).thenAnswer(inv -> {
            latch.countDown();
            return inv.getArgument(0);
        });

        // When
        auditLogService.logDenied(identity, "ACCESS", "/api/admin", "POST", "/api/admin", reason, roles);

        // Then
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertEquals(true, completed, "Audit log should be saved asynchronously");

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository, times(1)).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertNotNull(saved);
        assertEquals("test", saved.getActorNamespace());
        assertEquals("user456", saved.getActorReference());
        assertEquals("DENIED", saved.getResult());
    }

    @Test
    void logRoleChange_shouldSaveAuditEntry_forGrant() throws InterruptedException {
        // Given
        ProfileIdentity actor = new ProfileIdentity("admin", "admin1");
        ProfileIdentity target = new ProfileIdentity("local", "user789");
        CountDownLatch latch = new CountDownLatch(1);

        when(auditLogRepository.save(any(AuditLogEntity.class))).thenAnswer(inv -> {
            latch.countDown();
            return inv.getArgument(0);
        });

        // When
        auditLogService.logRoleChange(actor, target, "GRANT", Role.ADMIN, true);

        // Then
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertEquals(true, completed, "Audit log should be saved asynchronously");

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository, times(1)).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertNotNull(saved);
        assertEquals("admin", saved.getActorNamespace());
        assertEquals("local", saved.getTargetProfileNamespace());
        assertEquals("user789", saved.getTargetProfileReference());
        assertEquals("GRANT", saved.getAction());
        assertEquals("SUCCESS", saved.getResult());
    }

    @Test
    void logRoleChange_shouldSaveAuditEntry_forRevoke() throws InterruptedException {
        // Given
        ProfileIdentity actor = new ProfileIdentity("github", "admin2");
        ProfileIdentity target = new ProfileIdentity("local", "user999");
        CountDownLatch latch = new CountDownLatch(1);

        when(auditLogRepository.save(any(AuditLogEntity.class))).thenAnswer(inv -> {
            latch.countDown();
            return inv.getArgument(0);
        });

        // When
        auditLogService.logRoleChange(actor, target, "REVOKE", Role.USER, false);

        // Then
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertEquals(true, completed, "Audit log should be saved asynchronously");

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository, times(1)).save(captor.capture());

        AuditLogEntity saved = captor.getValue();
        assertNotNull(saved);
        assertEquals("REVOKE", saved.getAction());
        assertEquals("FAILED", saved.getResult());
    }

    @Test
    void logAccess_shouldHandleNullRoles() throws InterruptedException {
        // Given
        ProfileIdentity identity = new ProfileIdentity("test", "user1");
        CountDownLatch latch = new CountDownLatch(1);

        when(auditLogRepository.save(any(AuditLogEntity.class))).thenAnswer(inv -> {
            latch.countDown();
            return inv.getArgument(0);
        });

        // When
        auditLogService.logAccess(identity, "ACCESS", "/api/test", "GET", "/api/test", true, null);

        // Then
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertEquals(true, completed, "Audit log should handle null roles gracefully");

        verify(auditLogRepository, times(1)).save(any(AuditLogEntity.class));
    }
}
