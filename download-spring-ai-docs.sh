#!/usr/bin/env bash
set -e

# === Настройки ===
DOCS_URL="https://docs.spring.io/spring-ai/reference/"
TARGET_DIR="./local-rag/spring-ai"

echo "📥 Скачиваю документацию Spring AI из: $DOCS_URL"
echo "📂 Целевая папка: $TARGET_DIR"
mkdir -p "$TARGET_DIR"

# === Скачивание сайта ===
wget \
  --mirror \
  --convert-links \
  --adjust-extension \
  --page-requisites \
  --no-parent \
  --show-progress \
  --directory-prefix="$TARGET_DIR" \
  "$DOCS_URL"

echo "✅ Документация успешно загружена в: $TARGET_DIR"

# === Очистка ===
find "$TARGET_DIR" -type f -name "*.tmp" -delete
find "$TARGET_DIR" -type f -name "*.bak" -delete

# === README ===
cat > "$TARGET_DIR/README.md" <<EOF
# Spring AI Documentation (локальная копия)

Эта папка содержит зеркальную копию официальной документации:
[$DOCS_URL]($DOCS_URL)

Скачано: $(date)

Чтобы обновить — просто снова запусти \`download-spring-ai-docs.sh\`.
EOF

echo "📚 Готово!"
