package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnBean(Driver.class)
public class GraphQueryService {

  private static final Logger log = LoggerFactory.getLogger(GraphQueryService.class);

  public enum Direction {
    OUTGOING,
    INCOMING,
    BOTH
  }

  private final Driver driver;
  private final GitHubRagProperties.Graph graphProperties;

  public GraphQueryService(Driver driver, GitHubRagProperties properties) {
    this.driver = Objects.requireNonNull(driver, "driver");
    this.graphProperties = properties.getGraph();
  }

  public GraphNeighbors neighbors(
      String namespace, String symbolFqn, Direction direction, Set<String> relations, int limit) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(symbolFqn, "symbolFqn");
    int safeLimit = Math.max(1, Math.min(64, limit > 0 ? limit : 16));
    Set<String> relationFilter =
        CollectionUtils.isEmpty(relations) ? Set.of() : new LinkedHashSet<>(relations);
    SessionConfig config =
        SessionConfig.builder().withDatabase(graphProperties.getDatabase()).build();
    try (Session session = driver.session(config)) {
      String pattern =
          switch (direction != null ? direction : Direction.OUTGOING) {
            case OUTGOING -> "(s)-[r]->(t)";
            case INCOMING -> "(t)-[r]->(s)";
            case BOTH -> "(s)-[r]-(t)";
          };
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("namespace", namespace);
      params.put("fqn", symbolFqn);
      params.put("limit", safeLimit);
      params.put("relations", relationFilter.isEmpty() ? List.of() : List.copyOf(relationFilter));
      String query =
          """
          MATCH (s:Symbol {namespace:$namespace, fqn:$fqn})
          MATCH %s
          WHERE $relations = [] OR type(r) IN $relations
          RETURN s AS source, t AS target, type(r) AS relation, r.chunkHash AS chunkHash, r.chunkIndex AS chunkIndex
          LIMIT $limit
          """
              .formatted(pattern);
      Result result = session.run(query, params);
      List<GraphNode> nodes = new ArrayList<>();
      List<GraphEdge> edges = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();
      while (result.hasNext()) {
        Record record = result.next();
        Node source = record.get("source").asNode();
        Node target = record.get("target").asNode();
        String relation = record.get("relation").asString();
        GraphNode srcNode = toNode(source);
        GraphNode tgtNode = toNode(target);
        if (srcNode != null && seen.add(srcNode.fqn())) {
          nodes.add(srcNode);
        }
        if (tgtNode != null && seen.add(tgtNode.fqn())) {
          nodes.add(tgtNode);
        }
        if (srcNode != null && tgtNode != null) {
          Integer chunkIndex = record.get("chunkIndex").isNull() ? null : record.get("chunkIndex").asInt();
          String chunkHash = record.get("chunkHash").isNull() ? null : record.get("chunkHash").asString();
          edges.add(new GraphEdge(srcNode.fqn(), tgtNode.fqn(), relation, chunkHash, chunkIndex));
        }
      }
      return new GraphNeighbors(List.copyOf(nodes), List.copyOf(edges));
    }
  }

  public GraphNeighbors shortestPath(
      String namespace,
      String sourceFqn,
      String targetFqn,
      Set<String> relations,
      int maxDepth) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(sourceFqn, "sourceFqn");
    Objects.requireNonNull(targetFqn, "targetFqn");
    int depth = Math.max(1, Math.min(8, maxDepth <= 0 ? 4 : maxDepth));
    Set<String> relationFilter =
        CollectionUtils.isEmpty(relations) ? Set.of() : new LinkedHashSet<>(relations);
    SessionConfig config =
        SessionConfig.builder().withDatabase(graphProperties.getDatabase()).build();
    try (Session session = driver.session(config)) {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("namespace", namespace);
      params.put("sourceFqn", sourceFqn);
      params.put("targetFqn", targetFqn);
      params.put("relations", relationFilter.isEmpty() ? List.of() : List.copyOf(relationFilter));
      String depthPattern = depth > 0 ? ".." + depth : "";
      String query =
          """
          MATCH (a:Symbol {namespace:$namespace, fqn:$sourceFqn})
          MATCH (b:Symbol {namespace:$namespace, fqn:$targetFqn})
          MATCH p=shortestPath((a)-[r*%s]-(b))
          WHERE $relations = [] OR ALL(rel IN relationships(p) WHERE type(rel) IN $relations)
          RETURN p
          """
              .formatted(depthPattern);
      Result result = session.run(query, params);
      if (!result.hasNext()) {
        return new GraphNeighbors(List.of(), List.of());
      }
      Record record = result.next();
      Path path = record.get("p").asPath();
      Set<String> seen = new LinkedHashSet<>();
      List<GraphNode> nodes = new ArrayList<>();
      List<GraphEdge> edges = new ArrayList<>();
      Map<Long, String> idToFqn = new LinkedHashMap<>();
      for (Node node : path.nodes()) {
        GraphNode gn = toNode(node);
        if (gn != null) {
          idToFqn.put(node.id(), gn.fqn());
          if (seen.add(gn.fqn())) {
            nodes.add(gn);
          }
        }
      }
      for (Relationship rel : path.relationships()) {
        String from = idToFqn.get(rel.startNodeId());
        String to = idToFqn.get(rel.endNodeId());
        Integer chunkIndex = rel.get("chunkIndex").isNull() ? null : rel.get("chunkIndex").asInt();
        String chunkHash = rel.get("chunkHash").isNull() ? null : rel.get("chunkHash").asString();
        if (StringUtils.hasText(from) && StringUtils.hasText(to)) {
          edges.add(new GraphEdge(from, to, rel.type(), chunkHash, chunkIndex));
        }
      }
      return new GraphNeighbors(List.copyOf(nodes), List.copyOf(edges));
    }
  }

  public GraphNode definition(String namespace, String symbolFqn) {
    SessionConfig config =
        SessionConfig.builder().withDatabase(graphProperties.getDatabase()).build();
    try (Session session = driver.session(config)) {
      String query =
          """
          MATCH (s:Symbol {namespace:$namespace, fqn:$fqn})
          RETURN s
          """;
      Result result =
          session.run(
              query, Map.of("namespace", namespace, "fqn", symbolFqn));
      if (!result.hasNext()) {
        return null;
      }
      Record record = result.next();
      return toNode(record.get("s").asNode());
    }
  }

  private GraphNode toNode(Node node) {
    if (node == null) {
      return null;
    }
    String fqn = text(node.get("fqn"));
    if (!StringUtils.hasText(fqn)) {
      return null;
    }
    return new GraphNode(
        fqn,
        text(node.get("filePath")),
        text(node.get("symbolKind")),
        text(node.get("symbolVisibility")),
        node.get("lineStart").isNull() ? null : node.get("lineStart").asInt(),
        node.get("lineEnd").isNull() ? null : node.get("lineEnd").asInt());
  }

  private String text(Value value) {
    if (value == null || value.isNull()) {
      return null;
    }
    return value.asString();
  }

  public record GraphNode(
      String fqn, String filePath, String kind, String visibility, Integer lineStart, Integer lineEnd) {}

  public record GraphEdge(
      String from, String to, String relation, String chunkHash, Integer chunkIndex) {}

  public record GraphNeighbors(List<GraphNode> nodes, List<GraphEdge> edges) {}
}
