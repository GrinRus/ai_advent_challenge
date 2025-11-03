package com.aiadvent.mcp.backend.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

final class RepoAnalysisIgnoreMatcher {

  private final List<Rule> rules;

  private RepoAnalysisIgnoreMatcher(List<Rule> rules) {
    this.rules = rules;
  }

  static RepoAnalysisIgnoreMatcher load(Path workspaceRoot, Path projectRoot) throws IOException {
    List<Rule> rules = new ArrayList<>();
    Path workspaceIgnore = workspaceRoot.resolve(".mcpignore");
    if (Files.exists(workspaceIgnore)) {
      rules.addAll(parseRules(workspaceIgnore, workspaceRoot));
    }
    if (!workspaceRoot.equals(projectRoot)) {
      Path projectIgnore = projectRoot.resolve(".mcpignore");
      if (Files.exists(projectIgnore)) {
        rules.addAll(parseRules(projectIgnore, projectRoot));
      }
    }
    return new RepoAnalysisIgnoreMatcher(rules);
  }

  boolean isIgnored(Path path, boolean directory) {
    boolean ignored = false;
    for (Rule rule : rules) {
      if (rule.matches(path, directory)) {
        ignored = !rule.negate();
      }
    }
    return ignored;
  }

  private static List<Rule> parseRules(Path ignoreFile, Path base) throws IOException {
    List<Rule> rules = new ArrayList<>();
    for (String line : Files.readAllLines(ignoreFile)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      boolean negate = trimmed.startsWith("!");
      if (negate) {
        trimmed = trimmed.substring(1).trim();
      }
      boolean directoryPattern = trimmed.endsWith("/");
      if (directoryPattern) {
        trimmed = trimmed.substring(0, trimmed.length() - 1);
      }
      if (!StringUtils.hasText(trimmed)) {
        continue;
      }
      trimmed = trimmed.replace('\\', '/');
      if (trimmed.startsWith("/")) {
        trimmed = trimmed.substring(1);
      }
      boolean hasWildcard =
          trimmed.contains("*") || trimmed.contains("?") || trimmed.contains("[");
      String glob = trimmed;
      if (directoryPattern) {
        glob = glob + "/**";
      }
      if (!glob.startsWith("**/") && !glob.startsWith("./") && !glob.contains("/")) {
        glob = "**/" + glob;
      }
      Pattern pattern = Pattern.compile(globToRegex(glob));
      String directoryPrefix =
          directoryPattern && !hasWildcard ? trimmed.toLowerCase(Locale.ROOT) : null;
      rules.add(new Rule(base, pattern, negate, directoryPrefix));
    }
    return rules;
  }

  private static String globToRegex(String glob) {
    StringBuilder regex = new StringBuilder("^");
    for (int i = 0; i < glob.length(); i++) {
      char ch = glob.charAt(i);
      switch (ch) {
        case '*':
          if ((i + 1) < glob.length() && glob.charAt(i + 1) == '*') {
            regex.append(".*");
            i++;
          } else {
            regex.append("[^/]*");
          }
          break;
        case '?':
          regex.append("[^/]");
          break;
        case '.':
          regex.append("\\.");
          break;
        case '/':
          regex.append("/");
          break;
        default:
          regex.append(Pattern.quote(String.valueOf(ch)));
      }
    }
    regex.append("$");
    return regex.toString();
  }

  private record Rule(Path base, Pattern pattern, boolean negate, String directoryPrefix) {
    boolean matches(Path candidate, boolean directory) {
      Path normalized = candidate.normalize();
      if (!normalized.startsWith(base)) {
        return false;
      }
      Path relative = base.relativize(normalized);
      String normalizedPath = relative.toString().replace('\\', '/');
      if (normalizedPath.isEmpty()) {
        normalizedPath = ".";
      }

      if (directoryPrefix != null) {
        String lower = normalizedPath.toLowerCase(Locale.ROOT);
        if (lower.equals(directoryPrefix) || lower.startsWith(directoryPrefix + "/")) {
          return true;
        }
      }
      if (directory && normalizedPath.equals(".")) {
        return pattern.matcher("").matches();
      }
      return pattern.matcher(normalizedPath).matches();
    }
  }
}
