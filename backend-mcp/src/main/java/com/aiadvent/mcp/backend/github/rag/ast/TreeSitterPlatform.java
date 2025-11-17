package com.aiadvent.mcp.backend.github.rag.ast;

import java.util.Locale;

/**
 * Represents the host platform. Helps resolve library names/directories for Tree-sitter binaries.
 */
public final class TreeSitterPlatform {

  private final String os;
  private final String arch;
  private final String libraryPrefix;
  private final String libraryExtension;

  private TreeSitterPlatform(String os, String arch, String libraryPrefix, String libraryExtension) {
    this.os = os;
    this.arch = arch;
    this.libraryPrefix = libraryPrefix;
    this.libraryExtension = libraryExtension;
  }

  public static TreeSitterPlatform detect() {
    String osName = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
    String archName = System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT);

    String normalizedOs;
    String prefix = "lib";
    String extension = ".so";
    if (osName.contains("mac") || osName.contains("darwin")) {
      normalizedOs = "macos";
      extension = ".dylib";
    } else if (osName.contains("win")) {
      normalizedOs = "windows";
      prefix = "";
      extension = ".dll";
    } else {
      normalizedOs = "linux";
    }

    String normalizedArch;
    if (archName.contains("aarch64") || archName.contains("arm64")) {
      normalizedArch = "arm64";
    } else if (archName.contains("x86_64") || archName.contains("amd64")) {
      normalizedArch = "x86_64";
    } else if (archName.contains("x86") || archName.contains("i386")) {
      normalizedArch = "x86";
    } else {
      normalizedArch = archName;
    }

    return new TreeSitterPlatform(normalizedOs, normalizedArch, prefix, extension);
  }

  public String os() {
    return os;
  }

  public String arch() {
    return arch;
  }

  public String libraryFileName(String baseName) {
    return libraryPrefix + baseName + libraryExtension;
  }

  public String libraryExtension() {
    return libraryExtension;
  }
}
