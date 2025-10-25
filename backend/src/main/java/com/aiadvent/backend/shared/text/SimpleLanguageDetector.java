package com.aiadvent.backend.shared.text;

import org.springframework.util.StringUtils;

/**
 * Lightweight heuristic-based detector that only differentiates between the most common scripts
 * we encounter in the project (Latin ↔ Cyrillic). Falls back to {@code und} for everything else
 * to avoid false positives and unnecessary dependencies.
 */
public final class SimpleLanguageDetector {

  private SimpleLanguageDetector() {}

  public static String detectLanguage(String text) {
    if (!StringUtils.hasText(text)) {
      return "und";
    }
    int latin = 0;
    int cyrillic = 0;
    int other = 0;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (!Character.isLetter(ch)) {
        continue;
      }
      Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
      if (block == null) {
        continue;
      }
      if (isCyrillic(block)) {
        cyrillic++;
      } else if (isLatin(block)) {
        latin++;
      } else {
        other++;
      }
    }
    if (cyrillic > 0 && cyrillic >= latin && cyrillic >= other) {
      return "ru";
    }
    if (latin > 0 && latin >= cyrillic && latin >= other) {
      return "en";
    }
    return "und";
  }

  private static boolean isLatin(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.BASIC_LATIN
        || block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
        || block == Character.UnicodeBlock.LATIN_EXTENDED_A
        || block == Character.UnicodeBlock.LATIN_EXTENDED_B
        || block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL;
  }

  private static boolean isCyrillic(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.CYRILLIC
        || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY
        || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_A
        || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_B;
  }
}
