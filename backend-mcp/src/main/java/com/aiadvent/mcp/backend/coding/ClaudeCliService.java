package com.aiadvent.mcp.backend.coding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class ClaudeCliService {

  private static final Logger log = LoggerFactory.getLogger(ClaudeCliService.class);

  private final CodingAssistantProperties properties;

  ClaudeCliService(CodingAssistantProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  ClaudeCliInvocation invoke(Path workspacePath, String prompt) {
    Objects.requireNonNull(workspacePath, "workspacePath");
    Objects.requireNonNull(prompt, "prompt");
    CodingAssistantProperties.ClaudeCliProperties cli =
        Objects.requireNonNull(properties.getClaude(), "claude");
    if (!StringUtils.hasText(cli.getApiKey())) {
      throw new IllegalStateException(
          "ZHIPU_API_KEY (coding.claude.api-key) must be provided to run Claude CLI");
    }
    List<String> command = buildCommand(cli, prompt);
    int attempts = Math.max(1, cli.getMaxRetries());
    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        return runOnce(command, workspacePath, cli);
      } catch (RuntimeException ex) {
        lastFailure = ex;
        log.warn(
            "Claude CLI invocation attempt {}/{} failed: {}",
            attempt,
            attempts,
            ex.getMessage());
        if (attempt == attempts) {
          throw ex;
        }
      }
    }
    throw lastFailure;
  }

  private List<String> buildCommand(
      CodingAssistantProperties.ClaudeCliProperties cli, String prompt) {
    List<String> command = new ArrayList<>();
    String binary =
        StringUtils.hasText(cli.getCliBin()) ? cli.getCliBin().trim() : "claude";
    command.add(binary);
    command.add("--output-format");
    command.add("json");
    if (StringUtils.hasText(cli.getModel())) {
      command.add("--model");
      command.add(cli.getModel());
    }
    command.add("-p");
    command.add(prompt);
    return command;
  }

  private ClaudeCliInvocation runOnce(
      List<String> command, Path workspacePath, CodingAssistantProperties.ClaudeCliProperties cli) {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workspacePath.toFile());
    Map<String, String> env = builder.environment();
    env.put("ANTHROPIC_API_KEY", cli.getApiKey());
    env.put("ANTHROPIC_AUTH_TOKEN", cli.getApiKey());
    env.put("ANTHROPIC_BASE_URL", cli.getBaseUrl());
    if (cli.getApiTimeout() != null) {
      env.put("API_TIMEOUT_MS", String.valueOf(cli.getApiTimeout().toMillis()));
    }
    if (StringUtils.hasText(cli.getDefaultOpusModel())) {
      env.put("ANTHROPIC_DEFAULT_OPUS_MODEL", cli.getDefaultOpusModel());
    }
    if (StringUtils.hasText(cli.getDefaultSonnetModel())) {
      env.put("ANTHROPIC_DEFAULT_SONNET_MODEL", cli.getDefaultSonnetModel());
    }
    if (StringUtils.hasText(cli.getDefaultHaikuModel())) {
      env.put("ANTHROPIC_DEFAULT_HAIKU_MODEL", cli.getDefaultHaikuModel());
    }
    env.put("CLAUDE_NO_TELEMETRY", "1");
    env.put("LANG", "en_US.UTF-8");

    Process process;
    try {
      process = builder.start();
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to start Claude CLI process", ex);
    }

    Instant startedAt = Instant.now();
    StreamCollector stdout = new StreamCollector();
    StreamCollector stderr = new StreamCollector();
    Thread stdoutThread = new Thread(() -> stdout.collect(process.getInputStream()));
    Thread stderrThread = new Thread(() -> stderr.collect(process.getErrorStream()));
    stdoutThread.start();
    stderrThread.start();

    Duration timeout = cli.getCliTimeout() == null ? Duration.ofMinutes(2) : cli.getCliTimeout();
    boolean finished;
    try {
      finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new IllegalStateException("Claude CLI invocation interrupted", ex);
    }
    if (!finished) {
      process.destroyForcibly();
      throw new IllegalStateException(
          "Claude CLI timed out after " + timeout.toSeconds() + " seconds");
    }
    try {
      stdoutThread.join();
      stderrThread.join();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    int exit = process.exitValue();
    Duration duration = Duration.between(startedAt, Instant.now());
    String stderrText = stderr.content();
    if (exit != 0) {
      throw new IllegalStateException(
          "Claude CLI exited with code "
              + exit
              + ": "
              + (StringUtils.hasText(stderrText) ? stderrText : stdout.content()));
    }
    return new ClaudeCliInvocation(stdout.content(), stderrText, duration);
  }

  record ClaudeCliInvocation(String stdout, String stderr, Duration duration) {}

  private static final class StreamCollector {
    private final StringBuilder buffer = new StringBuilder();

    void collect(java.io.InputStream stream) {
      try (stream) {
        byte[] data = stream.readAllBytes();
        buffer.append(new String(data, StandardCharsets.UTF_8));
      } catch (IOException ex) {
        buffer.append(ex.getMessage());
      }
    }

    String content() {
      return buffer.toString();
    }
  }
}
