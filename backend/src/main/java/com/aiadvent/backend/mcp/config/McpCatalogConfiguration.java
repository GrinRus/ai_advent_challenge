package com.aiadvent.backend.mcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(McpCatalogProperties.class)
public class McpCatalogConfiguration {}

