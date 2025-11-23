package com.aiadvent.mcp.backend.github.rag.ast;

import io.github.treesitter.jtreesitter.Language;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LanguageRegistry {

  private static final Logger log = LoggerFactory.getLogger(LanguageRegistry.class);

  private final TreeSitterLibraryLoader libraryLoader;
  private final Map<String, Language> byId = new ConcurrentHashMap<>();

  public LanguageRegistry(TreeSitterLibraryLoader libraryLoader) {
    this.libraryLoader = libraryLoader;
  }

  public Optional<Language> language(String languageId) {
    if (languageId == null) {
      return Optional.empty();
    }
    String key = languageId.trim().toLowerCase(Locale.ROOT);
    Language existing = byId.get(key);
    if (existing != null) {
      return Optional.of(existing);
    }
    return libraryLoader
        .loadLanguage(key)
        .map(loaded -> {
          byId.putIfAbsent(key, loaded.languageHandle());
          return loaded.languageHandle();
        });
  }
}
