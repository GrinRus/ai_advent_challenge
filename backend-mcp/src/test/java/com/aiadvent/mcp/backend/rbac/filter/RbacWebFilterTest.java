package com.aiadvent.mcp.backend.rbac.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.rbac.domain.Role;
import com.aiadvent.mcp.backend.rbac.matcher.RbacPathMatcher;
import com.aiadvent.mcp.backend.rbac.service.AuditLogService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class RbacWebFilterTest {

    @Mock
    private RbacPathMatcher pathMatcher;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private WebFilterChain filterChain;

    private RbacWebFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RbacWebFilter(pathMatcher, auditLogService);
    }

    @Test
    void filter_shouldSkipNonMutationMethods() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        MockServerHttpResponse response = exchange.getResponse();

        when(pathMatcher.shouldSkipMethod("GET")).thenReturn(true);
        when(filterChain.filter(exchange)).thenReturn(Mono.empty());

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        result.block();
        verify(filterChain).filter(exchange);
        verify(auditLogService, never()).logAccess(any(), any(), any(), any(), any(), anyBoolean(), anySet());
    }

    @Test
    void filter_shouldSkipPublicPaths() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/public/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(pathMatcher.shouldSkipMethod("POST")).thenReturn(false);
        when(pathMatcher.isPublicPath("/api/public/test")).thenReturn(true);
        when(filterChain.filter(exchange)).thenReturn(Mono.empty());

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        result.block();
        verify(filterChain).filter(exchange);
        verify(auditLogService, never()).logAccess(any(), any(), any(), any(), any(), anyBoolean(), anySet());
    }

    @Test
    void filter_shouldReturn403_whenMissingProfileKey() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/protected").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(pathMatcher.shouldSkipMethod("POST")).thenReturn(false);
        when(pathMatcher.isPublicPath("/api/protected")).thenReturn(false);
        when(pathMatcher.isMutationMethod("POST")).thenReturn(true);

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        result.block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verify(filterChain, never()).filter(exchange);
    }

    @Test
    void filter_shouldReturn403_whenInvalidProfileKeyFormat() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/protected")
                .header("X-Profile-Key", "invalid-format")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(pathMatcher.shouldSkipMethod("POST")).thenReturn(false);
        when(pathMatcher.isPublicPath("/api/protected")).thenReturn(false);
        when(pathMatcher.isMutationMethod("POST")).thenReturn(true);

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        result.block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verify(filterChain, never()).filter(exchange);
    }

    @Test
    void filter_shouldAllowAccess_whenUserHasRequiredRole() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/admin")
                .header("X-Profile-Key", "github:user123")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(pathMatcher.shouldSkipMethod("POST")).thenReturn(false);
        when(pathMatcher.isPublicPath("/api/admin")).thenReturn(false);
        when(pathMatcher.isMutationMethod("POST")).thenReturn(true);
        when(pathMatcher.getRequiredRoles("/api/admin", "POST")).thenReturn(List.of("ADMIN"));
        when(filterChain.filter(exchange)).thenReturn(Mono.empty());

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        result.block();
        verify(filterChain).filter(exchange);
        verify(auditLogService).logAccess(any(), eq("ACCESS"), eq("/api/admin"), eq("POST"), eq("/api/admin"), eq(true), anySet());
    }

    @Test
    void filter_shouldDenyAccess_whenUserLacksRequiredRole() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.delete("/api/admin")
                .header("X-Profile-Key", "viewer:viewer1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(pathMatcher.shouldSkipMethod("DELETE")).thenReturn(false);
        when(pathMatcher.isPublicPath("/api/admin")).thenReturn(false);
        when(pathMatcher.isMutationMethod("DELETE")).thenReturn(true);
        when(pathMatcher.getRequiredRoles("/api/admin", "DELETE")).thenReturn(List.of("ADMIN"));

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        result.block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verify(filterChain, never()).filter(exchange);
        verify(auditLogService).logDenied(any(), eq("ACCESS"), eq("/api/admin"), eq("DELETE"), eq("/api/admin"), anyString(), anySet());
    }

    @Test
    void filter_shouldAllowAccess_whenNoRequiredRolesConfigured() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.put("/api/resource")
                .header("X-Profile-Key", "local:user456")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(pathMatcher.shouldSkipMethod("PUT")).thenReturn(false);
        when(pathMatcher.isPublicPath("/api/resource")).thenReturn(false);
        when(pathMatcher.isMutationMethod("PUT")).thenReturn(true);
        when(pathMatcher.getRequiredRoles("/api/resource", "PUT")).thenReturn(List.of());
        when(filterChain.filter(exchange)).thenReturn(Mono.empty());

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        result.block();
        verify(filterChain).filter(exchange);
        verify(auditLogService).logAccess(any(), any(), any(), any(), any(), eq(true), anySet());
    }

    @Test
    void filter_shouldHandleProtectedPathRule() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.patch("/api/special")
                .header("X-Profile-Key", "admin:admin1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(pathMatcher.shouldSkipMethod("PATCH")).thenReturn(false);
        when(pathMatcher.isPublicPath("/api/special")).thenReturn(false);
        when(pathMatcher.isMutationMethod("PATCH")).thenReturn(true);
        when(pathMatcher.getRequiredRoles("/api/special", "PATCH")).thenReturn(List.of("ADMIN"));
        when(filterChain.filter(exchange)).thenReturn(Mono.empty());

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        result.block();
        verify(filterChain).filter(exchange);
    }

    @Test
    void filter_shouldHandleBlankProfileKey() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                .header("X-Profile-Key", "   ")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(pathMatcher.shouldSkipMethod("POST")).thenReturn(false);
        when(pathMatcher.isPublicPath("/api/test")).thenReturn(false);
        when(pathMatcher.isMutationMethod("POST")).thenReturn(true);

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        result.block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void filter_shouldHandleEmptyNamespaceInProfileKey() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                .header("X-Profile-Key", ":reference")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(pathMatcher.shouldSkipMethod("POST")).thenReturn(false);
        when(pathMatcher.isPublicPath("/api/test")).thenReturn(false);
        when(pathMatcher.isMutationMethod("POST")).thenReturn(true);

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        result.block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void filter_shouldHandleEmptyReferenceInProfileKey() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                .header("X-Profile-Key", "namespace:")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(pathMatcher.shouldSkipMethod("POST")).thenReturn(false);
        when(pathMatcher.isPublicPath("/api/test")).thenReturn(false);
        when(pathMatcher.isMutationMethod("POST")).thenReturn(true);

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        result.block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }
}
