package com.aiadvent.mcp.backend.github.rag.ast;

import com.aiadvent.mcp.backend.github.rag.chunking.AstFileContext;
import com.aiadvent.mcp.backend.github.rag.chunking.AstSymbolMetadata;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AstFileContextFactory {

  private static final Logger log = LoggerFactory.getLogger(AstFileContextFactory.class);

  private final TreeSitterAnalyzer analyzer;

  public AstFileContextFactory(TreeSitterAnalyzer analyzer) {
    this.analyzer = analyzer;
  }

  public Supplier<AstFileContext> supplier(
      Path absolutePath, String relativePath, String language, String content) {
    Objects.requireNonNull(absolutePath, "absolutePath");
    return () -> create(absolutePath, relativePath, language, content);
  }

  public AstFileContext create(Path absolutePath, String relativePath, String language, String content) {
    if (!StringUtils.hasText(language)) {
      log.debug("AST fallback: language missing for file {}", relativePath);
      return null;
    }
    if (!analyzer.isEnabled()) {
      log.debug("AST fallback: analyzer disabled, using heuristics for {} ({})", relativePath, language);
      return null;
    }
    if (!analyzer.supportsLanguage(language)) {
      log.debug("AST fallback: language {} not supported for {}", language, relativePath);
      return null;
    }
    if (!analyzer.ensureLanguageLoaded(language)) {
      log.debug("AST fallback: unable to load Tree-sitter grammar for {} ({})", relativePath, language);
      return null;
    }
    return HeuristicAstExtractor.extract(content, language, relativePath)
        .filter(context -> !context.symbols().isEmpty())
        .orElse(null);
  }

  public Optional<AstFileContext> optional(Path absolutePath, String relativePath, String language, String content) {
    return Optional.ofNullable(create(absolutePath, relativePath, language, content));
  }

  private static final class HeuristicAstExtractor {

    private static final java.util.regex.Pattern CALL_PATTERN =
        java.util.regex.Pattern.compile("\\b([A-Za-z_][\\w$]*)\\s*\\(");
    private static final java.util.Set<String> CALL_KEYWORDS =
        java.util.Set.of(
            "if",
            "for",
            "while",
            "switch",
            "catch",
            "return",
            "throw",
            "new",
            "else",
            "case",
            "class",
            "def",
            "function",
            "fun",
            "fn",
            "match");

    static Optional<AstFileContext> extract(String content, String language, String relativePath) {
      String normalizedLanguage = language != null ? language.toLowerCase(java.util.Locale.ROOT) : "";
      String[] lines = content != null ? content.split("\\n", -1) : new String[0];
      java.util.List<String> docBuffer = new java.util.ArrayList<>();
      java.util.List<String> imports = collectImports(lines);
      java.util.List<SymbolBuilder> builders = new java.util.ArrayList<>();
      SymbolBuilder active = null;
      String currentContainer = null;
      for (int index = 0; index < lines.length; index++) {
        String rawLine = lines[index];
        String trimmed = rawLine.trim();
        if (trimmed.isEmpty()) {
          docBuffer.clear();
          continue;
        }
        if (isDocLine(trimmed, normalizedLanguage)) {
          docBuffer.add(cleanDocLine(trimmed));
          continue;
        }
        String symbolText = com.aiadvent.mcp.backend.github.rag.chunking.ParentSymbolResolver.detectSymbol(rawLine);
        if (StringUtils.hasText(symbolText)) {
          SymbolBuilder builder = new SymbolBuilder();
          builder.kind = extractKind(symbolText);
          builder.name = extractName(symbolText);
          builder.signature = trimmed;
          builder.startLine = index + 1;
          builder.docstring = joinDoc(docBuffer);
          builder.visibility = inferVisibility(rawLine);
          builder.imports = imports;
          builder.isTest = isTestSymbol(relativePath, builder.name);
          builder.callsOut = new java.util.LinkedHashSet<>();
          builder.callsIn = java.util.List.of();
          String base = sanitizePath(relativePath);
          String parentPrefix = currentContainer != null && !isContainer(builder.kind) ? currentContainer + "::" : "";
          builder.symbolFqn = base.isEmpty() ? parentPrefix + builder.name : base + "::" + parentPrefix + builder.name;
          builders.add(builder);
          active = builder;
          if (isContainer(builder.kind)) {
            currentContainer = builder.name;
          }
          docBuffer.clear();
          continue;
        }
        if (active != null) {
          java.util.regex.Matcher matcher = CALL_PATTERN.matcher(trimmed);
          while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!CALL_KEYWORDS.contains(candidate)) {
              active.callsOut.add(candidate);
            }
          }
        }
      }

      if (builders.isEmpty()) {
        builders.add(fallbackNode(relativePath, lines.length, imports));
      } else {
        for (int i = 0; i < builders.size(); i++) {
          SymbolBuilder current = builders.get(i);
          int endLine = (i + 1 < builders.size()) ? Math.max(current.startLine, builders.get(i + 1).startLine - 1) : lines.length;
          current.endLine = endLine;
        }
      }

      java.util.List<AstSymbolMetadata> result = new java.util.ArrayList<>();
      for (SymbolBuilder builder : builders) {
        int endLine = builder.endLine > 0 ? builder.endLine : lines.length;
        if (endLine < builder.startLine) {
          endLine = builder.startLine;
        }
        result.add(
            new AstSymbolMetadata(
                builder.symbolFqn,
                builder.kind,
                builder.visibility,
                builder.signature,
                builder.docstring,
                builder.isTest,
                builder.imports,
                java.util.List.copyOf(builder.callsOut),
                builder.callsIn,
                builder.startLine,
                endLine));
      }
      return Optional.of(new AstFileContext(java.util.List.copyOf(result)));
    }

    private static SymbolBuilder fallbackNode(String relativePath, int totalLines, java.util.List<String> imports) {
      SymbolBuilder fallback = new SymbolBuilder();
      fallback.name = StringUtils.hasText(relativePath) ? relativePath : "root";
      fallback.kind = "file";
      fallback.visibility = "public";
      fallback.signature = fallback.name;
      fallback.startLine = 1;
      fallback.endLine = Math.max(1, totalLines);
      fallback.symbolFqn = sanitizePath(relativePath);
      fallback.imports = imports;
      fallback.callsOut = new java.util.LinkedHashSet<>();
      fallback.callsIn = java.util.List.of();
      fallback.isTest = isTestSymbol(relativePath, fallback.name);
      return fallback;
    }

    private static boolean isDocLine(String trimmed, String language) {
      if (trimmed.startsWith("/**") || trimmed.startsWith("/*")) {
        return true;
      }
      if (trimmed.startsWith("//")) {
        return true;
      }
      if (language.startsWith("py") && trimmed.startsWith("#")) {
        return true;
      }
      if (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''") ) {
        return true;
      }
      if (trimmed.startsWith("///")) {
        return true;
      }
      return false;
    }

    private static String cleanDocLine(String trimmed) {
      String sanitized = trimmed.replaceAll("^(?:/\\*\\*?|//|#|\\*|\"\"\"|''')\\s*", "");
      if (sanitized.endsWith("*/")) {
        sanitized = sanitized.substring(0, sanitized.length() - 2);
      }
      if (sanitized.endsWith("\"\"\"")) {
        sanitized = sanitized.substring(0, sanitized.length() - 3);
      }
      if (sanitized.endsWith("'''")) {
        sanitized = sanitized.substring(0, sanitized.length() - 3);
      }
      return sanitized.trim();
    }

    private static String joinDoc(java.util.List<String> docBuffer) {
      if (docBuffer.isEmpty()) {
        return null;
      }
      String doc = String.join("\n", docBuffer).trim();
      return doc.isEmpty() ? null : doc;
    }

    private static String extractKind(String symbolText) {
      int space = symbolText.indexOf(' ');
      if (space > 0) {
        return symbolText.substring(0, space).toLowerCase(java.util.Locale.ROOT);
      }
      return symbolText.toLowerCase(java.util.Locale.ROOT);
    }

    private static String extractName(String symbolText) {
      int space = symbolText.indexOf(' ');
      if (space > 0 && space + 1 < symbolText.length()) {
        return symbolText.substring(space + 1).trim();
      }
      return symbolText.trim();
    }

    private static String inferVisibility(String line) {
      String lower = line.toLowerCase(java.util.Locale.ROOT);
      if (lower.contains("public")) {
        return "public";
      }
      if (lower.contains("protected")) {
        return "protected";
      }
      if (lower.contains("private")) {
        return "private";
      }
      return "package";
    }

    private static boolean isContainer(String kind) {
      return java.util.Set.of("class", "interface", "enum", "record", "trait", "struct").contains(kind);
    }

    private static boolean isTestSymbol(String relativePath, String name) {
      String lowerName = name != null ? name.toLowerCase(java.util.Locale.ROOT) : "";
      String lowerPath = relativePath != null ? relativePath.toLowerCase(java.util.Locale.ROOT) : "";
      return lowerName.contains("test") || lowerPath.contains("/test") || lowerPath.contains("\\\\test");
    }

    private static String sanitizePath(String relativePath) {
      if (!StringUtils.hasText(relativePath)) {
        return "";
      }
      return relativePath.replace('/', '.').replace('\\', '.');
    }

    private static java.util.List<String> collectImports(String[] lines) {
      java.util.List<String> imports = new java.util.ArrayList<>();
      for (String line : lines) {
        String trimmed = line.trim();
        if (trimmed.startsWith("import ") || trimmed.startsWith("from ")) {
          imports.add(trimmed);
        }
      }
      return java.util.List.copyOf(imports);
    }

    private static final class SymbolBuilder {
      String name;
      String kind;
      String visibility;
      String signature;
      String docstring;
      boolean isTest;
      int startLine;
      int endLine;
      java.util.List<String> imports;
      java.util.Set<String> callsOut;
      java.util.List<String> callsIn;
      String symbolFqn;
    }
  }
}
