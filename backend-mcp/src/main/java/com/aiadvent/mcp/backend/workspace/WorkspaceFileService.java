package com.aiadvent.mcp.backend.workspace;

import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService.Workspace;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WorkspaceFileService {

  private static final int DEFAULT_MAX_BYTES = 256 * 1024;
  private static final int MAX_ALLOWED_BYTES = 2 * 1024 * 1024;

  private final TempWorkspaceService workspaceService;

  public WorkspaceFileService(TempWorkspaceService workspaceService) {
    this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
  }

  public Workspace lookupWorkspace(String workspaceId) {
    return workspaceService
        .findWorkspace(sanitizeWorkspaceId(workspaceId))
        .orElseThrow(() -> new IllegalArgumentException("Unknown workspaceId: " + workspaceId));
  }

  public WorkspaceFilePayload readWorkspaceFile(String workspaceId, String path, Integer maxBytes) {
    Workspace workspace = lookupWorkspace(workspaceId);
    Path root = workspace.path().toAbsolutePath().normalize();
    Path target = resolvePath(root, path);
    if (!Files.exists(target)) {
      throw new IllegalArgumentException("File does not exist: " + path);
    }
    if (!Files.isRegularFile(target)) {
      throw new IllegalArgumentException("Path is not a regular file: " + path);
    }

    long size;
    try {
      size = Files.size(target);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to determine file size for " + path, ex);
    }

    int limit = computeLimit(maxBytes);
    byte[] data = readBytes(target, limit);
    boolean truncated = size > data.length;
    FileRepresentation representation = buildRepresentation(data);

    return new WorkspaceFilePayload(
        workspace.workspaceId(),
        path,
        size,
        truncated,
        representation.binary(),
        representation.encoding(),
        representation.text(),
        representation.base64(),
        Instant.now());
  }

  private String sanitizeWorkspaceId(String workspaceId) {
    String sanitized =
        workspaceId == null ? "" : workspaceId.trim();
    if (!StringUtils.hasText(sanitized)) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    return sanitized;
  }

  private Path resolvePath(Path root, String relativePath) {
    if (!StringUtils.hasText(relativePath)) {
      throw new IllegalArgumentException("path must not be blank");
    }
    Path target = root.resolve(relativePath).normalize();
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException("path must be within workspace");
    }
    return target;
  }

  private int computeLimit(Integer requested) {
    int limit =
        requested != null && requested > 0
            ? Math.min(requested, MAX_ALLOWED_BYTES)
            : DEFAULT_MAX_BYTES;
    return Math.max(1024, limit);
  }

  private byte[] readBytes(Path file, int limit) {
    byte[] buffer = new byte[limit];
    int total = 0;
    try (InputStream input = Files.newInputStream(file)) {
      while (total < limit) {
        int read = input.read(buffer, total, limit - total);
        if (read == -1) {
          break;
        }
        total += read;
      }
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to read file: " + file, ex);
    }
    byte[] result = new byte[total];
    System.arraycopy(buffer, 0, result, 0, total);
    return result;
  }

  private FileRepresentation buildRepresentation(byte[] data) {
    if (isBinary(data)) {
      return new FileRepresentation(true, "base64", null, Base64.getEncoder().encodeToString(data));
    }
    var decoder = StandardCharsets.UTF_8.newDecoder();
    decoder.onMalformedInput(CodingErrorAction.REPLACE);
    decoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
    String text;
    try {
      text = decoder.decode(ByteBuffer.wrap(data)).toString();
    } catch (CharacterCodingException ex) {
      text = new String(data, StandardCharsets.UTF_8);
    }
    return new FileRepresentation(false, StandardCharsets.UTF_8.name(), text, null);
  }

  private boolean isBinary(byte[] data) {
    if (data.length == 0) {
      return false;
    }
    int sample = Math.min(data.length, 1024);
    int control = 0;
    for (int i = 0; i < sample; i++) {
      int value = data[i] & 0xFF;
      if (value == 0) {
        return true;
      }
      if (value < 0x09 || (value > 0x0A && value < 0x20)) {
        control++;
      }
    }
    return control > sample * 0.3;
  }

  private record FileRepresentation(boolean binary, String encoding, String text, String base64) {}

  public record WorkspaceFilePayload(
      String workspaceId,
      String path,
      long sizeBytes,
      boolean truncated,
      boolean binary,
      String encoding,
      String content,
      String base64Content,
      Instant readAt) {}
}
