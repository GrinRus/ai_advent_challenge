package com.aiadvent.mcp.backend.github.rag.chunking;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public final class ParentSymbolResolver {

  private static final Pattern CLASS_PATTERN =
      Pattern.compile(
          "^(?:public\\s+|protected\\s+|private\\s+|abstract\\s+|final\\s+|sealed\\s+)?"
              + "(class|interface|enum|record|struct|trait)\\s+([A-Za-z_][\\w$]*)",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern PY_CLASS_PATTERN =
      Pattern.compile("^class\\s+([A-Za-z_][\\w]*)", Pattern.CASE_INSENSITIVE);

  private static final Pattern DEF_PATTERN =
      Pattern.compile("^(?:async\\s+)?def\\s+([A-Za-z_][\\w]*)\\s*\\(", Pattern.CASE_INSENSITIVE);

  private static final Pattern METHOD_PATTERN =
      Pattern.compile(
          "^(?:public\\s+|protected\\s+|private\\s+|static\\s+|final\\s+|async\\s+|override\\s+|synchronized\\s+)*"
              + "[\\w<>\\[\\],\"'\\s]+\\s+([A-Za-z_][\\w$]*)\\s*\\([^)]*\\)\\s*(?:\\{|=>|throws|default|;)?$",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern FUNC_PATTERN =
      Pattern.compile("^func\\s+([A-Za-z_][\\w]*)\\s*\\(");

  private static final Pattern FN_PATTERN =
      Pattern.compile("^fn\\s+([A-Za-z_][\\w]*)\\s*\\(");

  private static final Pattern FUN_PATTERN =
      Pattern.compile("^fun\\s+([A-Za-z_][\\w]*)\\s*\\(");

  private static final Pattern JS_FUNCTION_PATTERN =
      Pattern.compile("^function\\s+([A-Za-z_$][\\w$]*)\\s*\\(", Pattern.CASE_INSENSITIVE);

  private static final Pattern ARROW_PATTERN =
      Pattern.compile("^(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*\\([^)]*\\)\\s*=>");

  private final Map<Integer, String> symbolByLine = new HashMap<>();

  public ParentSymbolResolver(List<String> lines) {
    String current = null;
    for (int i = 0; i < lines.size(); i++) {
      String candidate = detectSymbol(lines.get(i));
      if (StringUtils.hasText(candidate)) {
        current = candidate;
      }
      symbolByLine.put(i + 1, current);
    }
  }

  public String resolve(int lineNumber) {
    if (symbolByLine.isEmpty()) {
      return null;
    }
    for (int i = Math.min(lineNumber, symbolByLine.size()); i >= 1; i--) {
      String symbol = symbolByLine.get(i);
      if (StringUtils.hasText(symbol)) {
        return symbol;
      }
    }
    return null;
  }

  public static String detectSymbol(String line) {
    if (!StringUtils.hasText(line)) {
      return null;
    }
    String trimmed = line.trim();
    if (trimmed.startsWith("//") || trimmed.startsWith("#")) {
      return null;
    }
    Matcher matcher = CLASS_PATTERN.matcher(trimmed);
    if (matcher.find()) {
      return matcher.group(1).toLowerCase() + " " + matcher.group(2);
    }
    matcher = PY_CLASS_PATTERN.matcher(trimmed);
    if (matcher.find()) {
      return "class " + matcher.group(1);
    }
    matcher = METHOD_PATTERN.matcher(trimmed);
    if (matcher.find()) {
      return "method " + matcher.group(1);
    }
    matcher = DEF_PATTERN.matcher(trimmed);
    if (matcher.find()) {
      return "def " + matcher.group(1);
    }
    matcher = JS_FUNCTION_PATTERN.matcher(trimmed);
    if (matcher.find()) {
      return "function " + matcher.group(1);
    }
    matcher = FUNC_PATTERN.matcher(trimmed);
    if (matcher.find()) {
      return "func " + matcher.group(1);
    }
    matcher = FN_PATTERN.matcher(trimmed);
    if (matcher.find()) {
      return "fn " + matcher.group(1);
    }
    matcher = FUN_PATTERN.matcher(trimmed);
    if (matcher.find()) {
      return "fun " + matcher.group(1);
    }
    matcher = ARROW_PATTERN.matcher(trimmed);
    if (matcher.find()) {
      return "function " + matcher.group(1);
    }
    return null;
  }
}
