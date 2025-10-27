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

RUN apk add --no-cache nodejs npm \
 && npm config set update-notifier false \
 && npm install -g @perplexity-ai/mcp-server@latest \
 && which perplexity-mcp && perplexity-mcp --help >/dev/null 2>&1 || true

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="" \
    PATH="/usr/local/bin:${PATH}"

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]