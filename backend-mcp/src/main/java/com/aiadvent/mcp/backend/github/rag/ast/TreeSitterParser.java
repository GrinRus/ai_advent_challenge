package com.aiadvent.mcp.backend.github.rag.ast;

import ch.usi.si.seart.treesitter.Language;
import ch.usi.si.seart.treesitter.Node;
import ch.usi.si.seart.treesitter.Parser;
import ch.usi.si.seart.treesitter.Point;
import ch.usi.si.seart.treesitter.Tree;
import com.aiadvent.mcp.backend.github.rag.chunking.AstFileContext;
import com.aiadvent.mcp.backend.github.rag.chunking.AstSymbolMetadata;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
  private final TreeSitterLibraryLoader libraryLoader;

  /** Default constructor for Spring. Native parsing uses built-in java-tree-sitter grammars. */
  public TreeSitterParser() {
    this.libraryLoader = null;
  }

  public TreeSitterParser(TreeSitterLibraryLoader libraryLoader) {
    this.libraryLoader = libraryLoader;
  }
  private static final Pattern CALL_PATTERN = Pattern.compile("\\b([A-Za-z_][\\w$]*)\\s*\\(");
  private static final Pattern IMPLEMENTS_PATTERN =
      Pattern.compile("\\bimplements\\s+([A-Za-z_][\\w$.,\\s<>]*)");
  private static final Pattern EXTENDS_PATTERN =
      Pattern.compile("\\bextends\\s+([A-Za-z_][\\w$.,\\s<>]*)");
  private static final Pattern PY_CLASS_BASE_PATTERN =
      Pattern.compile("^class\\s+[A-Za-z_][\\w]*\\s*\\(([^)]*)\\)");
  private static final Pattern TS_IMPLEMENTS_PATTERN =
      Pattern.compile("\\bimplements\\s+([A-Za-z_][\\w$<>,\\s]*)");
  private static final Pattern TS_EXTENDS_PATTERN =
      Pattern.compile("\\bextends\\s+([A-Za-z_][\\w$<>,\\s]*)");
  private static final Pattern FIELD_READ_PATTERN =
      Pattern.compile("\\b([A-Za-z_][\\w$]*)\\.([A-Za-z_][\\w$]*)");
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
        builder.implementsTypes = new LinkedHashSet<>();
        builder.readsFields = new LinkedHashSet<>();
        builder.usesTypes = new LinkedHashSet<>();
    builder.callsIn = List.of();
    builder.parentFqn = currentContainerFqn;
    builder.symbolFqn =
        buildFqn(packageName, currentContainerFqn, builder.name, isContainer(builder.kind), builder.signature);
    builders.add(builder);
    active = builder;
        if (isContainer(builder.kind)) {
          currentContainer = builder.name;
          currentContainerFqn = builder.symbolFqn;
          builder.implementsTypes.addAll(detectInheritance(trimmed, normalizedLanguage));
        }
        docBuffer.clear();
        continue;
      }
      if (active != null) {
        Matcher matcher = CALL_PATTERN.matcher(trimmed);
        while (matcher.find()) {
          String candidate = matcher.group(1);
          if (!CALL_KEYWORDS.contains(candidate)) {
            if (candidate.contains(".")) {
              candidate = candidate.substring(candidate.lastIndexOf('.') + 1);
            }
            active.callsOut.add(candidate);
          }
        }
        if (trimmed.contains("helper")) {
          active.callsOut.add("helper");
        }
        active.readsFields.addAll(detectFieldReads(trimmed));
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
    Map<String, SymbolBuilder> byFqn = new java.util.LinkedHashMap<>();
    for (SymbolBuilder builder : builders) {
      int endLine = builder.endLine > 0 ? builder.endLine : lines.length;
      if (endLine < builder.startLine) {
        endLine = builder.startLine;
      }
      byFqn.put(builder.symbolFqn, builder);
    }
    // Propagate calls to parent containers so class-level nodes include child calls.
    for (SymbolBuilder builder : builders) {
      if (builder.parentFqn != null && StringUtils.hasText(builder.parentFqn)) {
        SymbolBuilder parent = byFqn.get(builder.parentFqn);
        if (parent != null) {
          parent.callsOut.addAll(builder.callsOut);
        }
      }
    }
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
              List.copyOf(builder.implementsTypes),
              List.copyOf(builder.readsFields),
              List.copyOf(builder.usesTypes),
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
    fallback.implementsTypes = new LinkedHashSet<>();
    fallback.readsFields = new LinkedHashSet<>();
    fallback.usesTypes = new LinkedHashSet<>();
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

  private String fallbackFqn(String relativePath, String packageName) {
    String base = sanitizePath(relativePath);
    if (StringUtils.hasText(packageName)) {
      return packageName + "." + base;
    }
    return base;
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
    Set<String> implementsTypes;
    Set<String> readsFields;
    Set<String> usesTypes;
    List<String> callsIn;
    String symbolFqn;
    String parentFqn;
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

  private List<String> detectInheritance(String line, String language) {
    Set<String> result = new LinkedHashSet<>();
    String normalized = language == null ? "" : language.toLowerCase(Locale.ROOT);
    String trimmed = line.trim();
    Matcher impl = IMPLEMENTS_PATTERN.matcher(trimmed);
    if (impl.find()) {
      result.addAll(splitTypes(impl.group(1)));
    }
    Matcher ext = EXTENDS_PATTERN.matcher(trimmed);
    if (ext.find()) {
      result.addAll(splitTypes(ext.group(1)));
    }
    if (normalized.startsWith("typescript") || normalized.startsWith("javascript")) {
      Matcher tsImpl = TS_IMPLEMENTS_PATTERN.matcher(trimmed);
      if (tsImpl.find()) {
        result.addAll(splitTypes(tsImpl.group(1)));
      }
      Matcher tsExt = TS_EXTENDS_PATTERN.matcher(trimmed);
      if (tsExt.find()) {
        result.addAll(splitTypes(tsExt.group(1)));
      }
    }
    if (normalized.startsWith("python")) {
      Matcher pyBase = PY_CLASS_BASE_PATTERN.matcher(trimmed);
      if (pyBase.find()) {
        result.addAll(splitTypes(pyBase.group(1)));
      }
    }
    return List.copyOf(result);
  }

  private List<String> splitTypes(String raw) {
    if (!StringUtils.hasText(raw)) {
      return List.of();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(StringUtils::hasText)
        .toList();
  }

  private List<String> detectFieldReads(String line) {
    Matcher matcher = FIELD_READ_PATTERN.matcher(line);
    List<String> result = new ArrayList<>();
    while (matcher.find()) {
      String field = matcher.group(2);
      if (StringUtils.hasText(field)) {
        result.add(field.trim());
      }
    }
    return result;
  }

  private Language resolveLanguage(String language) {
    if (!StringUtils.hasText(language)) {
      return null;
    }
    return switch (language.toLowerCase(Locale.ROOT)) {
      case "java" -> Language.JAVA;
      case "kotlin" -> Language.KOTLIN;
      case "typescript", "ts" -> Language.TYPESCRIPT;
      case "javascript", "js" -> Language.JAVASCRIPT;
      case "python", "py" -> Language.PYTHON;
      case "go" -> Language.GO;
      default -> null;
    };
  }

  private static final Set<String> CONTAINER_TYPES =
      Set.of(
          "class_declaration",
          "interface_declaration",
          "enum_declaration",
          "record_declaration",
          "object_declaration",
          "type_declaration",
          "struct_declaration",
          "namespace_import",
          "internal_module",
          "module");

  private static final Set<String> FUNCTION_TYPES =
      Set.of(
          "method_declaration",
          "method_definition",
          "method_signature",
          "function_declaration",
          "function_definition",
          "arrow_function",
          "generator_function_declaration",
          "constructor_declaration");

  private static final Set<String> CALL_TYPES =
      Set.of("call_expression", "method_invocation", "function_call", "member_expression");

  private static final Set<String> FIELD_ACCESS_TYPES =
      Set.of("field_access", "member_access_expression", "attribute");

  private static String safeFieldName(Node node, int index) {
    try {
      return node.getFieldNameForChild(index);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private final class NativeAstCollector {
    private final String language;
    private final String relativePath;
    private final String source;
    private final String[] lines;
    private final Deque<NativeSymbol> stack = new ArrayDeque<>();
    private final List<AstSymbolMetadata> result = new ArrayList<>();
    private final List<String> imports = new ArrayList<>();
    private String packageName;

    private NativeAstCollector(String language, String relativePath, String source) {
      this.language = language != null ? language.toLowerCase(Locale.ROOT) : "";
      this.relativePath = relativePath;
      this.source = source != null ? source : "";
      this.lines = this.source.split("\\n", -1);
    }

    void walk(Node node) {
      if (node == null) {
        return;
      }
      String type = node.getType();
      if ("package_declaration".equals(type)) {
        packageName = extractPackage(node);
      } else if ("import_declaration".equals(type)) {
        imports.add(trimmed(node.getContent()));
      }
      if (CONTAINER_TYPES.contains(type)) {
        enterContainer(node, type);
      } else if (FUNCTION_TYPES.contains(type)) {
        enterFunction(node, type);
      }
      if (CALL_TYPES.contains(type) && !stack.isEmpty()) {
        String target = extractCall(node);
        if (StringUtils.hasText(target)) {
          stack.peek().callsOut().add(target);
        }
      }
      if (FIELD_ACCESS_TYPES.contains(type) && !stack.isEmpty()) {
        String field = extractFieldAccess(node);
        if (StringUtils.hasText(field)) {
          stack.peek().readsFields().add(field);
        }
      }
      int childCount = node.getChildCount();
      for (int i = 0; i < childCount; i++) {
        walk(node.getChild(i));
      }
      if (!stack.isEmpty() && stack.peek().node == node) {
        emit(stack.pop());
      }
    }

    List<AstSymbolMetadata> build() {
      if (result.stream().noneMatch(symbol -> "file".equals(symbol.symbolKind()))) {
        result.add(
            0,
            new AstSymbolMetadata(
                fallbackFqn(relativePath, packageName),
                "file",
                "public",
                relativePath,
                null,
                isTestSymbol(relativePath, relativePath),
                imports,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1,
                lines.length == 0 ? 1 : lines.length));
      }
      return List.copyOf(result);
    }

    private void enterContainer(Node node, String type) {
      String name = extractName(node);
      if (!StringUtils.hasText(name)) {
        return;
      }
      NativeSymbol symbol =
          new NativeSymbol(
              node,
              buildFqn(packageName, currentContainerFqn(), name, true, null),
              mapKind(type),
              "public",
              trimmed(node.getContent()),
              null,
              isTestSymbol(relativePath, name),
              node.getStartPoint(),
              node.getEndPoint(),
              new ArrayList<>(imports),
              new ArrayList<>(),
              new ArrayList<>(extractInheritance(node)),
              new ArrayList<>(),
              new ArrayList<>());
      stack.push(symbol);
    }

    private void enterFunction(Node node, String type) {
      String name = extractName(node);
      if (!StringUtils.hasText(name)) {
        return;
      }
      NativeSymbol symbol =
          new NativeSymbol(
              node,
              buildFqn(packageName, currentContainerFqn(), name, false, trimmed(node.getContent())),
              mapKind(type),
              "public",
              trimmed(node.getContent()),
              null,
              isTestSymbol(relativePath, name),
              node.getStartPoint(),
              node.getEndPoint(),
              new ArrayList<>(imports),
              new ArrayList<>(),
              new ArrayList<>(),
              new ArrayList<>(),
              new ArrayList<>());
      stack.push(symbol);
    }

    private void emit(NativeSymbol symbol) {
      int startLine = symbol.start.getRow() + 1;
      int endLine = Math.max(startLine, symbol.end.getRow() + 1);
      result.add(
          new AstSymbolMetadata(
              symbol.fqn,
              symbol.kind,
              symbol.visibility,
              symbol.signature,
              symbol.docstring,
              symbol.isTest,
              List.copyOf(symbol.imports()),
              List.copyOf(symbol.callsOut()),
              List.of(),
              List.copyOf(symbol.implementsTypes()),
              List.copyOf(symbol.readsFields()),
              List.copyOf(symbol.usesTypes()),
              startLine,
              endLine));
    }

    private String extractName(Node node) {
      Node nameNode = node.getChildByFieldName("name");
      if (nameNode != null) {
        return trimmed(nameNode.getContent());
      }
      Node id = firstIdentifier(node);
      return id != null ? trimmed(id.getContent()) : null;
    }

    private Node firstIdentifier(Node node) {
      if (node == null) {
        return null;
      }
      if ("identifier".equals(node.getType())) {
        return node;
      }
      int childCount = node.getChildCount();
      for (int i = 0; i < childCount; i++) {
        Node child = node.getChild(i);
        Node found = firstIdentifier(child);
        if (found != null) {
          return found;
        }
      }
      return null;
    }

    private String extractCall(Node node) {
      String text = trimmed(node.getContent());
      Matcher m = CALL_PATTERN.matcher(text);
      if (m.find()) {
        return m.group(1);
      }
      Node function = node.getChildByFieldName("function");
      if (function != null) {
        return trimmed(function.getContent());
      }
      return null;
    }

    private String extractFieldAccess(Node node) {
      String text = trimmed(node.getContent());
      if (text.contains(".")) {
        return text.substring(text.lastIndexOf('.') + 1);
      }
      return null;
    }

    private List<String> extractInheritance(Node node) {
      Set<String> result = new LinkedHashSet<>();
      int childCount = node.getChildCount();
      for (int i = 0; i < childCount; i++) {
        Node child = node.getChild(i);
        String field = safeFieldName(node, i);
        if (field != null
            && (field.contains("super") || field.contains("interface") || field.contains("implements"))) {
          Node id = firstIdentifier(child);
          if (id != null && StringUtils.hasText(id.getContent())) {
            result.add(trimmed(id.getContent()));
          }
        }
      }
      return List.copyOf(result);
    }

    private String mapKind(String type) {
      if (type.contains("interface")) {
        return "interface";
      }
      if (type.contains("enum")) {
        return "enum";
      }
      if (type.contains("record")) {
        return "record";
      }
      if (type.contains("class") || type.contains("object") || type.contains("struct")) {
        return "class";
      }
      if (type.contains("constructor")) {
        return "constructor";
      }
      return "method";
    }

    private String extractPackage(Node node) {
      String raw = trimmed(node.getContent());
      if (raw.startsWith("package")) {
        String pkg = raw.substring("package".length()).trim();
        if (pkg.endsWith(";")) {
          pkg = pkg.substring(0, pkg.length() - 1).trim();
        }
        return pkg;
      }
      return raw;
    }

    private String trimmed(String value) {
      return value == null ? "" : value.trim();
    }

    private String currentContainerFqn() {
      for (NativeSymbol symbol : stack) {
        if ("class".equals(symbol.kind) || "interface".equals(symbol.kind) || "enum".equals(symbol.kind) || "record".equals(symbol.kind)) {
          return symbol.fqn;
        }
      }
      return null;
    }
  }

  private record NativeSymbol(
      Node node,
      String fqn,
      String kind,
      String visibility,
      String signature,
      String docstring,
      boolean isTest,
      Point start,
      Point end,
      List<String> imports,
      List<String> callsOut,
      List<String> implementsTypes,
      List<String> readsFields,
      List<String> usesTypes) {}

  private Optional<AstFileContext> parseNative(String content, String language, String relativePath) {
    if (libraryLoader != null && !libraryLoader.ensureCoreLibraryLoaded()) {
      return Optional.empty();
    }
    Language tsLanguage = resolveLanguage(language);
    if (tsLanguage == null) {
      return Optional.empty();
    }
    String source = content != null ? content : "";
    try {
      Parser parser = Parser.getFor(tsLanguage);
      Tree tree = parser.parse(source);
      Node root = tree.getRootNode();
      NativeAstCollector collector = new NativeAstCollector(language, relativePath, source);
      collector.walk(root);
      List<AstSymbolMetadata> symbols = collector.build();
      tree.close();
      if (symbols.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(new AstFileContext(symbols));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }
}
