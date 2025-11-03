package com.aiadvent.mcp.backend.analysis;

import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.AggregateFindingsRequest;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.AggregateFindingsResponse;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ListHotspotsRequest;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ListHotspotsResponse;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ScanNextSegmentRequest;
import com.aiadvent.mcp.backend.analysis.RepoAnalysisModels.ScanNextSegmentResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class RepoAnalysisTools {

  private final RepoAnalysisService service;

  RepoAnalysisTools(RepoAnalysisService service) {
    this.service = service;
  }

  @Tool(
      name = "repo_analysis.scan_next_segment",
      description =
          "Возвращает следующий сегмент кода из подготовленного workspace. "
              + "Используйте analysisId для сохранения прогресса; установите reset=true для нового прохода. "
              + "Ответ содержит ограниченный по размеру фрагмент, сводку и прогресс по очереди файлов.")
  ScanNextSegmentResponse scanNextSegment(ScanNextSegmentRequest request) {
    return service.scanNextSegment(request);
  }

  @Tool(
      name = "repo_analysis.aggregate_findings",
      description =
          "Добавляет новые находки анализа и возвращает агрегированную сводку по файлам. "
              + "Передавайте path/line/severity/tags, чтобы строить hotspots для последующего обзора.")
  AggregateFindingsResponse aggregateFindings(AggregateFindingsRequest request) {
    return service.aggregateFindings(request);
  }

  @Tool(
      name = "repo_analysis.list_hotspots",
      description =
          "Возвращает список горячих точек (files) с наибольшим количеством/серьёзностью находок. "
              + "Параметр includeDetails=true добавляет краткие выдержки по каждой точке.")
  ListHotspotsResponse listHotspots(ListHotspotsRequest request) {
    return service.listHotspots(request);
  }
}
