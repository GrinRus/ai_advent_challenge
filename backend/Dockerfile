# syntax=docker/dockerfile:1.7

FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon clean

COPY src src
RUN ./gradlew --no-daemon bootJar

# --- Runtime image ---
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Install Node.js + npm and make sure MCP server is available without network on startup
RUN apk add --no-cache nodejs npm \
    && npm install -g @perplexity-ai/mcp-server npx \
    # sanity checks (won't fail the build if help exits non-zero)
    && mcp-server --help >/dev/null 2>&1 || true \
    && node -v && npm -v && npx --version

ENV PATH="/usr/local/bin:${PATH}" \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS=""

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]