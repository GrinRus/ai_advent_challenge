package com.aiadvent.mcp.backend.github.rag.chunking;

import java.util.Arrays;
import java.util.List;

public final class LineIndex {

  private final int[] offsets;
  private final int totalLength;

  public LineIndex(List<String> lines) {
    offsets = new int[lines.size()];
    int offset = 0;
    for (int i = 0; i < lines.size(); i++) {
      offsets[i] = offset;
      offset += lines.get(i).length();
      if (i < lines.size() - 1) {
        offset++;
      }
    }
    this.totalLength = offset;
  }

  public LineRange rangeForSpan(int start, int end) {
    if (offsets.length == 0) {
      return new LineRange(1, 1);
    }
    int normalizedStart = Math.max(0, start);
    int normalizedEnd = Math.max(normalizedStart, end - 1);
    return new LineRange(lineForIndex(normalizedStart), lineForIndex(normalizedEnd));
  }

  private int lineForIndex(int index) {
    if (offsets.length == 0) {
      return 1;
    }
    if (index <= 0) {
      return 1;
    }
    if (index >= totalLength) {
      return offsets.length;
    }
    int position = Arrays.binarySearch(offsets, index);
    if (position >= 0) {
      return position + 1;
    }
    int insertionPoint = -position - 2;
    if (insertionPoint < 0) {
      return 1;
    }
    return insertionPoint + 1;
  }

  public record LineRange(int start, int end) {}
}
