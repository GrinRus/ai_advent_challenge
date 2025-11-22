package com.aiadvent.mcp.backend.github.rag.ast;

import com.aiadvent.mcp.backend.github.rag.chunking.AstFileContext;
import com.aiadvent.mcp.backend.github.rag.chunking.AstSymbolMetadata;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Lightweight AST parser placeholder. Currently reuses heuristic extraction until Tree-sitter
 * binding is wired. Keeps the same contract as future parser: returns AstFileContext with symbols
 * and edges.
 */
@Component
public class TreeSitterParser {

  private static final Pattern CALL_PATTERN = Pattern.compile("\\b([A-Za-z_][\\w$]*)\\s*\\(");
  private static final Set<String> CALL_KEYWORDS =
      Set.of(
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

  public Optional<AstFileContext> parse(String content, String language, String relativePath) {
    return parse(content, language, relativePath, false);
  }

  public Optional<AstFileContext> parse(
      String content, String language, String relativePath, boolean nativeEnabled) {
    if (nativeEnabled) {
      Optional<AstFileContext> nativeAst = parseNative(content, language, relativePath);
      if (nativeAst.isPresent()) {
        return nativeAst;
      }
    }
    String normalizedLanguage = language != null ? language.toLowerCase(Locale.ROOT) : "";
    String[] lines = content != null ? content.split("\\n", -1) : new String[0];
    String packageName = detectPackage(lines);
    List<String> docBuffer = new ArrayList<>();
    List<String> imports = collectImports(lines);
    List<SymbolBuilder> builders = new ArrayList<>();
    SymbolBuilder active = null;
    String currentContainer = null;
    String currentContainerFqn = null;

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
      String symbolText =
          com.aiadvent.mcp.backend.github.rag.chunking.ParentSymbolResolver.detectSymbol(rawLine);
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
        builder.callsOut = new LinkedHashSet<>();
        builder.callsIn = List.of();
        builder.symbolFqn =
            buildFqn(packageName, currentContainerFqn, builder.name, isContainer(builder.kind), builder.signature);
        builders.add(builder);
        active = builder;
        if (isContainer(builder.kind)) {
          currentContainer = builder.name;
          currentContainerFqn = builder.symbolFqn;
        }
        docBuffer.clear();
        continue;
      }
      if (active != null) {
        Matcher matcher = CALL_PATTERN.matcher(trimmed);
        while (matcher.find()) {
          String candidate = matcher.group(1);
          if (!CALL_KEYWORDS.contains(candidate)) {
            active.callsOut.add(candidate);
          }
        }
      }
    }

    SymbolBuilder fileSymbol = fallbackNode(relativePath, packageName, lines.length, imports);
    if (builders.isEmpty()) {
      builders.add(fileSymbol);
    } else {
      for (int i = 0; i < builders.size(); i++) {
        SymbolBuilder current = builders.get(i);
        int endLine =
            (i + 1 < builders.size())
                ? Math.max(current.startLine, builders.get(i + 1).startLine - 1)
                : lines.length;
        current.endLine = endLine;
      }
      builders.add(0, fileSymbol);
    }

    List<AstSymbolMetadata> result = new ArrayList<>();
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
              List.copyOf(builder.callsOut),
              builder.callsIn,
              builder.startLine,
              endLine));
    }
    return Optional.of(new AstFileContext(List.copyOf(result)));
  }

  private SymbolBuilder fallbackNode(String relativePath, String packageName, int totalLines, List<String> imports) {
    SymbolBuilder fallback = new SymbolBuilder();
    fallback.name = StringUtils.hasText(relativePath) ? relativePath : "root";
    fallback.kind = "file";
    fallback.visibility = "public";
    fallback.signature = fallback.name;
    fallback.startLine = 1;
    fallback.endLine = Math.max(1, totalLines);
    String baseName = sanitizePath(relativePath);
    if (StringUtils.hasText(packageName)) {
      fallback.symbolFqn = packageName + "." + baseName;
    } else {
      fallback.symbolFqn = baseName;
    }
    fallback.imports = imports;
    fallback.callsOut = new LinkedHashSet<>();
    fallback.callsIn = List.of();
    fallback.isTest = isTestSymbol(relativePath, fallback.name);
    return fallback;
  }

  private boolean isDocLine(String trimmed, String language) {
    if (trimmed.startsWith("/**") || trimmed.startsWith("/*")) {
      return true;
    }
    if (trimmed.startsWith("//")) {
      return true;
    }
    if (language.startsWith("py") && trimmed.startsWith("#")) {
      return true;
    }
    if (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''")) {
      return true;
    }
    if (trimmed.startsWith("///")) {
      return true;
    }
    return false;
  }

  private String cleanDocLine(String trimmed) {
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

  private String joinDoc(List<String> docBuffer) {
    if (docBuffer.isEmpty()) {
      return null;
    }
    String doc = String.join("\n", docBuffer).trim();
    return doc.isEmpty() ? null : doc;
  }

  private String extractKind(String symbolText) {
    int space = symbolText.indexOf(' ');
    if (space > 0) {
      return symbolText.substring(0, space).toLowerCase(Locale.ROOT);
    }
    return symbolText.toLowerCase(Locale.ROOT);
  }

  private String extractName(String symbolText) {
    int space = symbolText.indexOf(' ');
    if (space > 0 && space + 1 < symbolText.length()) {
      return symbolText.substring(space + 1).trim();
    }
    return symbolText.trim();
  }

  private String inferVisibility(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
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

  private boolean isContainer(String kind) {
    return Set.of("class", "interface", "enum", "record", "trait", "struct").contains(kind);
  }

  private boolean isTestSymbol(String relativePath, String name) {
    String lowerName = name != null ? name.toLowerCase(Locale.ROOT) : "";
    String lowerPath = relativePath != null ? relativePath.toLowerCase(Locale.ROOT) : "";
    return lowerName.contains("test") || lowerPath.contains("/test") || lowerPath.contains("\\test");
  }

  private String sanitizePath(String relativePath) {
    if (!StringUtils.hasText(relativePath)) {
      return "";
    }
    return relativePath.replace('/', '.').replace('\\', '.');
  }

  private List<String> collectImports(String[] lines) {
    List<String> imports = new ArrayList<>();
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.startsWith("import ") || trimmed.startsWith("from ")) {
        imports.add(trimmed);
      }
    }
    return List.copyOf(imports);
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
    List<String> imports;
    Set<String> callsOut;
    List<String> callsIn;
    String symbolFqn;
  }

  private String detectPackage(String[] lines) {
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.startsWith("package ")) {
        String pkg = trimmed.substring("package ".length()).replace(";", "").trim();
        if (StringUtils.hasText(pkg)) {
          return pkg;
        }
      }
    }
    return null;
  }

  private String buildFqn(String packageName, String containerFqn, String symbolName, boolean isContainer, String signature) {
    String base = StringUtils.hasText(packageName) ? packageName : null;
    if (StringUtils.hasText(containerFqn)) {
      base = containerFqn;
    }
    String name = symbolName != null ? symbolName.trim() : "";
    if (!isContainer && StringUtils.hasText(signature)) {
      String args = extractArgs(signature);
      name = name + args;
      name = name.replace(" ", "");
    }
    if (!StringUtils.hasText(base)) {
      return name;
    }
    if (isContainer) {
      return base + "." + name;
    }
    return base + "#" + name;
  }

  private String extractArgs(String signature) {
    int open = signature.indexOf('(');
    int close = signature.indexOf(')');
    if (open >= 0 && close > open) {
      return signature.substring(open, close + 1);
    }
    return "()";
  }

  private Optional<AstFileContext> parseNative(String content, String language, String relativePath) {
    // Placeholder for native Tree-sitter parsing via JavaCPP bindings.
    // If native parsing is not available or fails, fallback to heuristic.
    return Optional.empty();
  }
}
