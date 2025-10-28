package com.aiadvent.backend.mcp.web;

import com.aiadvent.backend.mcp.service.McpCatalogService;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp/catalog")
public class McpCatalogController {

  private final McpCatalogService catalogService;

  public McpCatalogController(McpCatalogService catalogService) {
    this.catalogService = catalogService;
  }

  @GetMapping
  public McpCatalogResponse getCatalog() {
    return catalogService.getCatalog();
  }
}

