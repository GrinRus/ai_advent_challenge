package com.aiadvent.mcp.backend.github.rag.ast;

import com.aiadvent.mcp.backend.github.rag.chunking.AstFileContext;
import com.aiadvent.mcp.backend.github.rag.chunking.AstSymbolMetadata;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterQueryRegistry.LanguageQueries;
import io.github.treesitter.jtreesitter.InputEncoding;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Tree;
import io.github.treesitter.jtreesitter.Query;
import io.github.treesitter.jtreesitter.QueryCapture;
import io.github.treesitter.jtreesitter.QueryCursor;
import io.github.treesitter.jtreesitter.QueryMatch;
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
import org.springframework.beans.factory.annotation.Autowired;
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
  private final LanguageRegistry languageRegistry;

  @Autowired
  public TreeSitterParser(TreeSitterLibraryLoader libraryLoader, LanguageRegistry languageRegistry) {
    this.libraryLoader = libraryLoader;
    this.languageRegistry = languageRegistry;
  }

  /** Convenience constructor for tests. */
  public TreeSitterParser(TreeSitterLibraryLoader libraryLoader) {
    this.libraryLoader = libraryLoader;
    this.languageRegistry = new LanguageRegistry(libraryLoader);
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
    return parse(content, language, relativePath, false, TreeSitterQueryRegistry.empty());
  }

  public Optional<AstFileContext> parse(
      String content, String language, String relativePath, boolean nativeEnabled) {
    return parse(content, language, relativePath, nativeEnabled, TreeSitterQueryRegistry.empty());
  }

  public Optional<AstFileContext> parse(
      String content,
      String language,
      String relativePath,
      boolean nativeEnabled,
      LanguageQueries queries) {
    if (nativeEnabled) {
      Optional<AstFileContext> nativeAst = parseNative(content, language, relativePath, queries);
      if (nativeAst.isPresent()) {
        return nativeAst;
      }
    }
    String normalizedLanguage = language != null ? language.toLowerCase(Locale.ROOT) : "";
    String[] lines = content != null ? content.split("\\n", -1) : new String[0];
    String packageName = detectModuleOrPackage(lines, relativePath);
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

  private String detectModuleOrPackage(String[] lines, String relativePath) {
    String pkg = detectPackage(lines);
    if (StringUtils.hasText(pkg)) {
      return pkg;
    }
    if (!StringUtils.hasText(relativePath)) {
      return null;
    }
    String sanitized = relativePath.replace('\\', '/');
    int idx = sanitized.lastIndexOf('.');
    if (idx > 0) {
      sanitized = sanitized.substring(0, idx);
    }
    return sanitized.replace('/', '.');
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

  private Optional<AstFileContext> parseNative(String content, String language, String relativePath) {
    return parseNative(content, language, relativePath, TreeSitterQueryRegistry.empty());
  }

  private Optional<AstFileContext> parseNative(
      String content, String language, String relativePath, LanguageQueries queries) {
    if ((libraryLoader == null && languageRegistry == null) || !StringUtils.hasText(language) || content == null) {
      return Optional.empty();
    }
    Optional<io.github.treesitter.jtreesitter.Language> lang =
        languageRegistry != null
            ? languageRegistry.language(language)
            : libraryLoader != null
                ? libraryLoader.loadLanguage(language).map(TreeSitterLibraryLoader.LoadedLibrary::languageHandle)
                : Optional.empty();
    if (lang.isEmpty()) {
      return Optional.empty();
    }
    try (Parser parser = new Parser(lang.get())) {
      Optional<Tree> tree = parser.parse(content, InputEncoding.UTF_8);
      if (tree.isEmpty()) {
        return Optional.empty();
      }
      Tree parsedTree = tree.get();
      if (parsedTree.getRootNode() == null || parsedTree.getRootNode().hasError()) {
        return Optional.empty();
      }
      NativeAstExtractor extractor =
          new NativeAstExtractor(
              parsedTree.getRootNode(),
              content,
              language,
              relativePath,
              detectPackage(content.split("\\n", -1)),
              queries);
      AstFileContext nativeContext = extractor.extract();
      if (nativeContext != null && nativeContext.symbols() != null && !nativeContext.symbols().isEmpty()) {
        return Optional.of(nativeContext);
      }
      return Optional.empty();
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  /**
   * Lightweight native AST extractor that walks the Tree-sitter tree and builds symbol metadata
   * using generic heuristics. If we fail to find symbols, callers fall back to text heuristics.
   */
  private final class NativeAstExtractor {
    private final Node root;
    private final String content;
    private final String language;
    private final String relativePath;
    private final String packageName;
    private final List<String> imports;
    private final LanguageQueries queries;
    private final Map<String, String> importIndex;
    private final String[] contentLines;

    NativeAstExtractor(
        Node root,
        String content,
        String language,
        String relativePath,
        String packageName,
        LanguageQueries queries) {
      this.root = root;
      this.content = content;
      this.language = language != null ? language.toLowerCase(Locale.ROOT) : "";
      this.relativePath = relativePath;
      this.packageName = packageName;
      this.imports = collectImports(content.split("\\n", -1));
      this.queries = queries != null ? queries : TreeSitterQueryRegistry.empty();
      this.importIndex = buildImportIndex(this.imports);
      this.contentLines = content != null ? content.split("\\n", -1) : new String[0];
    }

    AstFileContext extract() {
      List<SymbolBuilder> builders = new ArrayList<>();
      if (queries.symbols().isPresent()) {
        builders.addAll(extractSymbolsViaQueries(root, queries.symbols().get()));
      } else {
        Deque<String> containerStack = new ArrayDeque<>();
        walk(root, builders, containerStack);
      }
      if (builders.isEmpty()) {
        return null;
      }
      if (queries.calls().isPresent()) {
        enrichCallsWithQueries(root, queries.calls().get(), builders);
      }
      if (queries.heritage().isPresent()) {
        Map<String, SymbolBuilder> byName =
            builders.stream()
                .collect(java.util.stream.Collectors.toMap(b -> b.name, b -> b, (a, b) -> a, java.util.LinkedHashMap::new));
        enrichHeritageWithQueries(root, queries.heritage().get(), byName);
      }
      if (queries.fields().isPresent()) {
        enrichFieldsWithQueries(root, queries.fields().get(), builders);
      }
      assignParents(builders);

      List<AstSymbolMetadata> result = new ArrayList<>();
      Map<String, SymbolBuilder> byFqn = new java.util.LinkedHashMap<>();
      for (SymbolBuilder builder : builders) {
        if (!StringUtils.hasText(builder.symbolFqn)) {
          continue;
        }
        int endLine = builder.endLine > 0 ? builder.endLine : builder.startLine;
        builder.endLine = endLine;
        byFqn.put(builder.symbolFqn, builder);
      }
      for (SymbolBuilder builder : builders) {
        if (builder.parentFqn != null && StringUtils.hasText(builder.parentFqn)) {
          SymbolBuilder parent = byFqn.get(builder.parentFqn);
          if (parent != null) {
            parent.callsOut.addAll(builder.callsOut);
          }
        }
      }
      for (SymbolBuilder builder : builders) {
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
                builder.endLine > 0 ? builder.endLine : builder.startLine));
      }
      return new AstFileContext(List.copyOf(result));
    }

    private List<SymbolBuilder> extractSymbolsViaQueries(Node rootNode, Query symbolsQuery) {
      List<SymbolBuilder> builders = new ArrayList<>();
      try (QueryCursor cursor = new QueryCursor(symbolsQuery)) {
        cursor.findMatches(rootNode)
            .forEach(
                match -> {
                  Node decl = null;
                  Node nameNode = null;
                  Node paramsNode = null;
                  for (QueryCapture capture : match.captures()) {
                    String capName = capture.name();
                    if ("symbol.decl".equals(capName)) {
                      decl = capture.node();
                    } else if ("symbol.name".equals(capName)) {
                      nameNode = capture.node();
                    } else if ("symbol.params".equals(capName)) {
                      paramsNode = capture.node();
                    }
                  }
                  if (decl == null || nameNode == null) {
                    return;
                  }
                  String name = cleanupIdentifier(nameNode.getText());
                  if (!StringUtils.hasText(name)) {
                    return;
                  }
                  SymbolBuilder builder = new SymbolBuilder();
                  builder.name = name;
                  builder.kind = deriveKind(decl.getType());
                  builder.signature = buildSignature(name, paramsNode);
                  builder.startLine = decl.getStartPoint().row() + 1;
                  builder.endLine = decl.getEndPoint().row() + 1;
                  builder.docstring = findDocstring(builder.startLine);
                  builder.visibility = inferVisibility(decl.getText());
                  builder.imports = imports;
                  builder.isTest = isTestSymbol(relativePath, name);
                  builder.callsOut = new LinkedHashSet<>();
                  builder.implementsTypes = new LinkedHashSet<>();
                  builder.readsFields = new LinkedHashSet<>();
                  builder.usesTypes = new LinkedHashSet<>();
                  builder.callsIn = List.of();
                  builder.parentFqn = null;
                  builder.symbolFqn =
                      buildFqn(
                          packageName, null, builder.name, isContainer(builder.kind), builder.signature);
                  builders.add(builder);
                });
      }
      return builders;
    }

    private void enrichCallsWithQueries(Node rootNode, Query callsQuery, List<SymbolBuilder> builders) {
      try (QueryCursor cursor = new QueryCursor(callsQuery)) {
        cursor.findMatches(rootNode)
            .forEach(
                match -> {
                  Node callNode = null;
                  Node nameNode = null;
                  for (QueryCapture capture : match.captures()) {
                    String capName = capture.name();
                    if ("call.expr".equals(capName)) {
                      callNode = capture.node();
                    } else if ("call.name".equals(capName)) {
                      nameNode = capture.node();
                    }
                  }
                  if (callNode == null || nameNode == null) {
                    return;
                  }
                  String name = cleanupIdentifier(nameNode.getText());
                  SymbolBuilder owner = findOwner(builders, callNode);
                  if (owner != null && StringUtils.hasText(name)) {
                    String resolved = resolveReference(name, builders);
                    if (StringUtils.hasText(resolved)) {
                      owner.callsOut.add(resolved);
                    }
                  }
                });
      }
    }

    private void enrichHeritageWithQueries(Node rootNode, Query heritageQuery, Map<String, SymbolBuilder> byName) {
      try (QueryCursor cursor = new QueryCursor(heritageQuery)) {
        cursor.findMatches(rootNode)
            .forEach(
                match -> {
                  Node childNode = null;
                  Node baseNode = null;
                  for (QueryCapture capture : match.captures()) {
                    String capName = capture.name();
                    if ("heritage.child".equals(capName)) {
                      childNode = capture.node();
                    } else if ("heritage.base".equals(capName)) {
                      baseNode = capture.node();
                    }
                  }
                  if (childNode == null || baseNode == null) {
                    return;
                  }
                  String child = cleanupIdentifier(childNode.getText());
                  String base = cleanupIdentifier(baseNode.getText());
                  SymbolBuilder builder = byName.get(child);
                  if (builder != null && StringUtils.hasText(base)) {
                    String resolved = resolveReference(base, byName.values().stream().toList());
                    if (StringUtils.hasText(resolved)) {
                      builder.implementsTypes.add(resolved);
                    }
                  }
                });
      }
    }

    private void enrichFieldsWithQueries(Node rootNode, Query fieldsQuery, List<SymbolBuilder> builders) {
      try (QueryCursor cursor = new QueryCursor(fieldsQuery)) {
        cursor.findMatches(rootNode)
            .forEach(
                match -> {
                  Node accessNode = null;
                  Node fieldNode = null;
                  for (QueryCapture capture : match.captures()) {
                    String capName = capture.name();
                    if ("field.access".equals(capName)) {
                      accessNode = capture.node();
                    } else if ("field.name".equals(capName)) {
                      fieldNode = capture.node();
                    }
                  }
                  if (accessNode == null || fieldNode == null) {
                    return;
                  }
                  String field = cleanupIdentifier(fieldNode.getText());
                  SymbolBuilder owner = findOwner(builders, accessNode);
                  if (owner != null && StringUtils.hasText(field)) {
                    String resolved = resolveReference(field, builders);
                    if (StringUtils.hasText(resolved)) {
                      owner.readsFields.add(resolved);
                    }
                  }
                });
      }
    }

    private SymbolBuilder findOwner(List<SymbolBuilder> builders, Node node) {
      int start = node.getStartPoint().row() + 1;
      int end = node.getEndPoint().row() + 1;
      SymbolBuilder best = null;
      for (SymbolBuilder builder : builders) {
        if (builder.startLine <= start && builder.endLine >= end) {
          if (best == null || span(builder) < span(best)) {
            best = builder;
          }
        }
      }
      return best;
    }

    private void assignParents(List<SymbolBuilder> builders) {
      List<SymbolBuilder> containers = builders.stream().filter(b -> isContainer(b.kind)).toList();
      for (SymbolBuilder builder : builders) {
        if (isContainer(builder.kind)) {
          continue;
        }
        SymbolBuilder parent = findOwner(containers, builder.startLine, builder.endLine);
        if (parent != null) {
          builder.parentFqn = parent.symbolFqn;
          builder.symbolFqn = buildFqn(packageName, parent.symbolFqn, builder.name, false, builder.signature);
        }
      }
    }

    private SymbolBuilder findOwner(List<SymbolBuilder> containers, int start, int end) {
      SymbolBuilder best = null;
      for (SymbolBuilder container : containers) {
        if (container.startLine <= start && container.endLine >= end) {
          if (best == null || span(container) < span(best)) {
            best = container;
          }
        }
      }
      return best;
    }

    private int span(SymbolBuilder builder) {
      if (builder == null) {
        return Integer.MAX_VALUE;
      }
      return Math.max(0, builder.endLine - builder.startLine);
    }

    private String deriveKind(String declType) {
      String lower = declType != null ? declType.toLowerCase(Locale.ROOT) : "";
      if (lower.contains("interface")) {
        return "interface";
      }
      if (lower.contains("enum")) {
        return "enum";
      }
      if (lower.contains("class")) {
        return "class";
      }
      if (lower.contains("object")) {
        return "object";
      }
      if (lower.contains("function")) {
        return "function";
      }
      if (lower.contains("method")) {
        return "method";
      }
      if (lower.contains("constructor")) {
        return "constructor";
      }
      return "symbol";
    }

    private String buildSignature(String name, Node paramsNode) {
      if (paramsNode == null) {
        return name + "()";
      }
      String params = paramsNode.getText();
      return (name + "(" + params.replaceAll("[\\r\\n]+", " ").trim() + ")").replaceAll("\\s+", " ");
    }

    private String findDocstring(int startLine) {
      if (contentLines.length == 0 || startLine <= 1) {
        return null;
      }
      List<String> buffer = new ArrayList<>();
      for (int line = startLine - 2; line >= 0; line--) {
        String trimmed = contentLines[line].trim();
        if (trimmed.isEmpty()) {
          if (!buffer.isEmpty()) {
            break;
          }
          continue;
        }
        if (trimmed.startsWith("///")
            || trimmed.startsWith("//")
            || trimmed.startsWith("#")
            || trimmed.startsWith("/*")
            || trimmed.startsWith("*")
            || trimmed.startsWith("\"\"\"")
            || trimmed.startsWith("'''")) {
          buffer.add(trimmed);
        } else {
          break;
        }
      }
      if (buffer.isEmpty()) {
        return null;
      }
      java.util.Collections.reverse(buffer);
      return buffer.stream()
          .map(this::cleanDocLine)
          .filter(StringUtils::hasText)
          .reduce((a, b) -> a + "\n" + b)
          .orElse(null);
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

    private Map<String, String> buildImportIndex(List<String> imports) {
      Map<String, String> map = new java.util.LinkedHashMap<>();
      for (String imp : imports) {
        if (!StringUtils.hasText(imp)) {
          continue;
        }
        String cleaned = imp.replace("import", " ").replace("from", " ").replace(";", " ").trim();
        cleaned = cleaned.replaceAll("\\{", " ").replaceAll("}", " ");
        String[] tokens = cleaned.split(",");
        for (String token : tokens) {
          String t = token.trim();
          if (!StringUtils.hasText(t)) {
            continue;
          }
          String[] parts = t.split("\\s+");
          String candidate = parts[0];
          if (candidate.contains(".")) {
            String shortName = candidate.substring(candidate.lastIndexOf('.') + 1);
            map.put(shortName, candidate);
          } else {
            map.putIfAbsent(candidate, candidate);
          }
        }
      }
      return map;
    }

    private String resolveReference(String name, List<SymbolBuilder> builders) {
      Map<String, String> local = new java.util.LinkedHashMap<>();
      for (SymbolBuilder builder : builders) {
        if (StringUtils.hasText(builder.name) && StringUtils.hasText(builder.symbolFqn)) {
          local.putIfAbsent(builder.name, builder.symbolFqn);
        }
      }
      return resolveReference(name, local.values().stream().toList(), local);
    }

    private String resolveReference(String name, java.util.Collection<String> knownFqns) {
      Map<String, String> local = new java.util.LinkedHashMap<>();
      for (String fqn : knownFqns) {
        if (StringUtils.hasText(fqn)) {
          String shortName = fqn.contains("#") || fqn.contains(".")
              ? fqn.substring(fqn.contains("#") ? fqn.lastIndexOf('#') + 1 : fqn.lastIndexOf('.') + 1)
              : fqn;
          local.putIfAbsent(shortName, fqn);
        }
      }
      return resolveReference(name, knownFqns, local);
    }

    private String resolveReference(String name, java.util.Collection<String> knownFqns, Map<String, String> localMap) {
      if (!StringUtils.hasText(name)) {
        return null;
      }
      String shortName = cleanupIdentifier(name);
      if (localMap.containsKey(shortName)) {
        return localMap.get(shortName);
      }
      if (importIndex.containsKey(shortName)) {
        return importIndex.get(shortName);
      }
      if (StringUtils.hasText(packageName)) {
        return packageName + "." + shortName;
      }
      return null;
    }

    private void walk(Node node, List<SymbolBuilder> builders, Deque<String> containerStack) {
      if (node == null) {
        return;
      }
      String type = node.getType();
      boolean isContainer = isContainerType(type);
      boolean isCallable = isCallableType(type);
      if (isContainer || isCallable) {
        String name = extractNodeName(node);
        if (StringUtils.hasText(name)) {
          SymbolBuilder builder = new SymbolBuilder();
          builder.kind = isContainer ? normalizeContainerKind(type) : "method";
          builder.name = name;
          builder.signature = buildSignature(node, name, isContainer);
          builder.startLine = node.getStartPoint().row() + 1;
          builder.endLine = node.getEndPoint().row() + 1;
          builder.docstring = null;
          builder.visibility = inferVisibility(node.getText());
          builder.imports = imports;
          builder.isTest = isTestSymbol(relativePath, name);
          builder.callsOut = new LinkedHashSet<>();
          collectCallsFromAst(node, builder.callsOut);
          if (builder.callsOut.isEmpty()) {
            builder.callsOut.addAll(collectCalls(node.getText()));
          }
          builder.implementsTypes = new LinkedHashSet<>(detectInheritance(node.getText(), language));
          collectInheritanceFromAst(node, builder.implementsTypes);
          builder.readsFields = new LinkedHashSet<>(detectFieldReads(node.getText()));
          collectFieldReadsFromAst(node, builder.readsFields);
          builder.usesTypes = new LinkedHashSet<>();
          builder.callsIn = List.of();
          builder.parentFqn = containerStack.peek();
          String containerFqn = containerStack.peek();
          builder.symbolFqn = buildFqn(packageName, containerFqn, name, isContainer, builder.signature);
          builders.add(builder);
          if (isContainer) {
            containerStack.push(builder.symbolFqn);
            node.getNamedChildren().forEach(child -> walk(child, builders, containerStack));
            containerStack.pop();
            return;
          }
        }
      }
      node.getNamedChildren().forEach(child -> walk(child, builders, containerStack));
    }

    private String extractNodeName(Node node) {
      return node.getChildByFieldName("name").map(Node::getText).orElseGet(() -> {
        for (Node child : node.getNamedChildren()) {
          String t = child.getType();
          if ("identifier".equals(t) || t.endsWith("identifier") || t.equals("type_identifier")) {
            return child.getText();
          }
        }
        return null;
      });
    }

    private String buildSignature(Node node, String name, boolean isContainer) {
      if (isContainer) {
        return name;
      }
      return name + extractArgs(node);
    }

    private String extractArgs(Node node) {
      return node.getChildByFieldName("parameters")
          .map(param -> "(" + param.getText().replaceAll("\\s+", " ").trim() + ")")
          .orElse("()");
    }

    private boolean isContainerType(String type) {
      String lower = type.toLowerCase(Locale.ROOT);
      return lower.contains("class")
          || lower.contains("interface")
          || lower.contains("enum")
          || lower.contains("record")
          || lower.contains("struct")
          || lower.contains("trait");
    }

    private boolean isCallableType(String type) {
      String lower = type.toLowerCase(Locale.ROOT);
      return lower.contains("function")
          || lower.contains("method")
          || lower.contains("constructor")
          || lower.contains("lambda")
          || lower.contains("arrow_function");
    }

    private String normalizeContainerKind(String type) {
      String lower = type.toLowerCase(Locale.ROOT);
      if (lower.contains("interface")) {
        return "interface";
      }
      if (lower.contains("enum")) {
        return "enum";
      }
      if (lower.contains("record")) {
        return "record";
      }
      if (lower.contains("struct")) {
        return "struct";
      }
      return "class";
    }

    private Set<String> collectCalls(String text) {
      Set<String> calls = new LinkedHashSet<>();
      Matcher matcher = CALL_PATTERN.matcher(text);
      while (matcher.find()) {
        String candidate = matcher.group(1);
        if (!CALL_KEYWORDS.contains(candidate)) {
          calls.add(candidate);
        }
      }
      return calls;
    }

    private void collectCallsFromAst(Node node, Set<String> target) {
      if (node == null) {
        return;
      }
      String type = node.getType().toLowerCase(Locale.ROOT);
      boolean isCall =
          type.contains("call")
              || type.contains("invocation")
              || type.contains("constructor_invocation")
              || type.contains("new_expression")
              || type.contains("decorator");
      if (isCall) {
        String name = extractCallableName(node);
        if (StringUtils.hasText(name) && !CALL_KEYWORDS.contains(name)) {
          target.add(name);
        }
      }
      for (Node child : node.getNamedChildren()) {
        collectCallsFromAst(child, target);
      }
    }

    private String extractCallableName(Node node) {
      if (node == null) {
        return null;
      }
      Optional<Node> fn =
          node.getChildByFieldName("function")
              .or(() -> node.getChildByFieldName("name"))
              .or(() -> node.getChildByFieldName("member"));
      if (fn.isPresent()) {
        return cleanupIdentifier(fn.get().getText());
      }
      for (Node child : node.getNamedChildren()) {
        String t = child.getType().toLowerCase(Locale.ROOT);
        if (t.contains("identifier") || t.contains("property_identifier")) {
          return cleanupIdentifier(child.getText());
        }
      }
      return null;
    }

    private void collectInheritanceFromAst(Node node, Set<String> target) {
      if (node == null) {
        return;
      }
      String type = node.getType().toLowerCase(Locale.ROOT);
      boolean isHeritage =
          type.contains("heritage_clause")
              || type.contains("superclass")
              || type.contains("interface_list")
              || type.contains("implements_clause")
              || type.contains("extends_clause");
      if (isHeritage) {
        for (Node child : node.getNamedChildren()) {
          String t = child.getType().toLowerCase(Locale.ROOT);
          if (t.contains("identifier") || t.contains("type_identifier")) {
            target.add(cleanupIdentifier(child.getText()));
          }
        }
      }
      for (Node child : node.getNamedChildren()) {
        collectInheritanceFromAst(child, target);
      }
    }

    private void collectFieldReadsFromAst(Node node, Set<String> target) {
      if (node == null) {
        return;
      }
      String type = node.getType().toLowerCase(Locale.ROOT);
      if (type.contains("field_access") || type.contains("member_expression")) {
        String field =
            node.getChildByFieldName("field")
                .map(Node::getText)
                .orElseGet(() -> {
                  for (Node child : node.getNamedChildren()) {
                    if (child.getType().toLowerCase(Locale.ROOT).contains("identifier")) {
                      return child.getText();
                    }
                  }
                  return null;
                });
        if (StringUtils.hasText(field)) {
          target.add(cleanupIdentifier(field));
        }
      }
      for (Node child : node.getNamedChildren()) {
        collectFieldReadsFromAst(child, target);
      }
    }

    private String cleanupIdentifier(String value) {
      if (value == null) {
        return null;
      }
      return value.replaceAll("[^A-Za-z0-9_$.]", "").trim();
    }

    private String inferVisibility(String nodeText) {
      return TreeSitterParser.this.inferVisibility(nodeText);
    }
  }
}
